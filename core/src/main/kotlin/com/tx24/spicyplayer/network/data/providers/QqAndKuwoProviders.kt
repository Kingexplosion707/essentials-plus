package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class QqMusicLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("qq_music","QQ Music",80,setOf(LyricsCapability.WORD_SYNC,LyricsCapability.LINE_SYNC),releaseChannel=SourceReleaseChannel.EXTENDED,defaultEnabled=false)
 override suspend fun fetch(request:LyricsLookupRequest):ProviderResult { return try{
  val headers=mapOf("Referer" to "https://y.qq.com/")
  val search=gson.toJson(mapOf("req_1" to mapOf("module" to "music.search.SearchCgiService", "method" to "DoSearchForQQMusicDesktop", "param" to mapOf("num_per_page" to 8, "page_num" to 1, "query" to "${request.artist} ${request.title}", "search_type" to 0))))
  val root=client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer","https://y.qq.com/").post(search.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use { response ->
   if(!response.isSuccessful)throw ProviderHttpException("QQ",response.code)
   gson.fromJson(response.body?.string(),JsonObject::class.java)
  }
  // The search throttles an address for a while (code 2001, an empty list), often for every
  // request in a row; QQ's suggestion search and track details still answer then.
  val code=root.get("code")?.asInt?.takeIf{it!=0}?:root.getAsJsonObject("req_1")?.get("code")?.asInt?.takeIf{it!=0}
  val songs=if(code==null){
   root.getAsJsonObject("req_1")?.getAsJsonObject("data")?.getAsJsonObject("body")?.getAsJsonObject("song")?.getAsJsonArray("list")
    ?.map{it.asJsonObject}?.map{song->QqSong(song.get("name")?.asString.orEmpty(),song.getAsJsonArray("singer")?.joinToString(" "){it.asJsonObject.get("name").asString}.orEmpty(),song.get("interval")?.asInt?:0,song.get("mid")?.asString.orEmpty())}
    ?:return ProviderResult.Miss
  }else{
   suggested("${request.artist} ${request.title}")?:return ProviderResult.Unavailable(ProviderFailureCategory.SERVER,"QQ search code $code",true)
  }
  for(song in songs){val title=song.title;val artists=song.artists;val dur=song.seconds
   if(!CatalogueTitles.sameTitle(title,request.title)||!CatalogueTitles.sameArtist(artists,request.artist,title,request.title,dur,request.durationSeconds)||!request.lengthMatches(dur))continue
   val mid=song.mid;if(mid.isBlank())continue
   fetchQrc(mid)?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics=it)) }
   val lyric="https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder().addQueryParameter("songmid",mid).addQueryParameter("format","json").addQueryParameter("nobase64","1").build()
   val data=getJson(lyric,headers);var text=data.get("lyric")?.asString.orEmpty();if(text.isBlank())continue
   if(!text.trim().startsWith("["))text=runCatching{String(Base64.getDecoder().decode(text),Charsets.UTF_8)}.getOrDefault(text)
   if(text.isNotBlank())return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=text))
  };ProviderResult.Miss
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 /**
  * Songs from QQ's suggestion search (the search box's own), with their lengths from one track
  * details request, or null where either fails. Fewer hits than the real search, but it answers
  * while that one is throttled.
  */
 private suspend fun suggested(query:String):List<QqSong>?{
  val url="https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg".toHttpUrl().newBuilder().addQueryParameter("key",query).addQueryParameter("format","json").build()
  val items=runCatching{getJson(url,mapOf("Referer" to "https://y.qq.com/"))}.getOrNull()
   ?.getAsJsonObject("data")?.getAsJsonObject("song")?.getAsJsonArray("itemlist")?.map{it.asJsonObject}?:return null
  val mids=items.mapNotNull{it.get("mid")?.asString}.ifEmpty{return emptyList()}
  val details=gson.toJson(mapOf("comm" to mapOf("ct" to "19","cv" to "1859","uin" to "0"),"req_1" to mapOf("module" to "music.trackInfo.UniformRuleCtrl","method" to "CgiGetTrackInfo","param" to mapOf("types" to mids.map{0},"mids" to mids))))
  val tracks=client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer","https://y.qq.com/").post(details.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use{r->
   if(!r.isSuccessful)return null
   gson.fromJson(r.body?.string(),JsonObject::class.java)
  }?.getAsJsonObject("req_1")?.takeIf{it.get("code")?.asInt==0}?.getAsJsonObject("data")?.getAsJsonArray("tracks")?.map{it.asJsonObject}?:return null
  return tracks.map{t->QqSong(t.get("name")?.asString.orEmpty(),t.getAsJsonArray("singer")?.joinToString(" "){it.asJsonObject.get("name").asString}.orEmpty(),t.get("interval")?.asInt?:0,t.get("mid")?.asString.orEmpty())}
 }

 private suspend fun getJson(url:okhttp3.HttpUrl,headers:Map<String,String>):JsonObject{val b=Request.Builder().url(url);headers.forEach{(k,v)->b.header(k,v)};return client.newCall(b.get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("QQ",r.code);gson.fromJson(r.body?.string(),JsonObject::class.java)}}
 private suspend fun fetchQrc(songMid:String):String? {
  val payload="""{"comm":{"ct":"19","cv":"1873","uin":"0"},"req":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$songMid","qrc":1,"qrc_t":0,"trans":1,"roma":1,"crypt":1}}}"""
  return client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer","https://y.qq.com/").post(payload.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use { response ->
   if(!response.isSuccessful)return@use null
   val root=gson.fromJson(response.body?.string(),JsonObject::class.java);val value=root.getAsJsonObject("req")?.getAsJsonObject("data")?.get("lyric")?.asString?:return@use null
   val decoded=when { value.trim().startsWith("[") -> value; value.length>64&&value.all{it.isWhitespace()||it in "0123456789abcdefABCDEF"}->QrcCodec.decrypt(value); else->null }
   decoded?.let(QrcCodec::toTtml)
  }
 }
}

@Singleton
class KuwoLyricsProvider @Inject constructor(private val client:OkHttpClient):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("kuwo","Kuwo",130,setOf(LyricsCapability.WORD_SYNC,LyricsCapability.LINE_SYNC),releaseChannel=SourceReleaseChannel.EXTENDED,defaultEnabled=false)
 override suspend fun fetch(request:LyricsLookupRequest):ProviderResult { return try{
  val url="https://search.kuwo.cn/r.s".toHttpUrl().newBuilder().addQueryParameter("all","${request.artist} ${request.title}").addQueryParameter("ft","music").addQueryParameter("client","kt").addQueryParameter("cluster","0").addQueryParameter("pn","0").addQueryParameter("rn","8").addQueryParameter("rformat","json").addQueryParameter("encoding","utf8")
   // Without these the search leaves out licensed originals and returns only covers and remixes.
   .addQueryParameter("vipver","1").addQueryParameter("strategy","2012").addQueryParameter("mobi","1").addQueryParameter("issubtitle","1").addQueryParameter("show_copyright_off","1").build()
  var fallback:ProviderResult.Hit?=null
  val matched=kuwoSearchResults(getBytes(url).toString(Charsets.UTF_8)).filter{song->
   CatalogueTitles.sameTitle(song.title,request.title)&&CatalogueTitles.sameArtist(song.artist,request.artist,song.title,request.title,song.durationSeconds,request.durationSeconds)&&
    request.lengthMatches(song.durationSeconds)&&(!KuwoLyrics.CUT.containsMatchIn(song.title)||KuwoLyrics.CUT.containsMatchIn(request.title))
  }
  // Up to three copies: the first word-timed one, else the first that has lyrics at all.
  for(song in matched.take(KuwoLyrics.TRIES)){
   val query=Base64.getEncoder().encodeToString(KuwoLyrics.xor("user=12345,web,web,web&requester=localhost&req=1&rid=${song.rid}&lrcx=1".toByteArray()))
   val bytes=getBytes("https://newlyric.kuwo.cn/newlyric.lrc?$query".toHttpUrl());val split=bytes.indexOfSequence("\r\n\r\n".toByteArray());if(split<0)continue
   if(!bytes.copyOfRange(0,split).toString(Charsets.US_ASCII).startsWith("tp=content"))continue
   val text=KuwoLyrics.text(InflaterInputStream(ByteArrayInputStream(bytes.copyOfRange(split+4,bytes.size))).readBytes())
   KuwoLyrics.krc(text)?.let(KrcCodec::toTtml)?.let{return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics=it))}
   val lrc=KuwoLyrics.lrc(text)
   if(lrc.isNotBlank()&&fallback==null)fallback=ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=lrc))
  };fallback?:ProviderResult.Miss
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 private suspend fun getBytes(url:okhttp3.HttpUrl):ByteArray=client.newCall(Request.Builder().url(url).header("Referer","https://www.kuwo.cn/").get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("Kuwo",r.code);r.body?.bytes() ?: byteArrayOf()}
 private fun ByteArray.indexOfSequence(needle:ByteArray):Int{outer@for(i in 0..size-needle.size){for(j in needle.indices)if(this[i+j]!=needle[j])continue@outer;return i};return -1}
}

data class QqSong(val title: String, val artists: String, val seconds: Int, val mid: String)

data class KuwoSong(val title: String, val artist: String, val durationSeconds: Int, val rid: String)

/**
 * Kuwo's search answers in single-quoted JavaScript-style JSON, with `&nbsp;` for spaces in
 * names. Records nest objects of their own, so this parses it leniently rather than by pattern.
 */
fun kuwoSearchResults(raw: String): List<KuwoSong> {
    val reader = com.google.gson.stream.JsonReader(java.io.StringReader(raw)).apply { isLenient = true }
    val root = com.google.gson.JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        .replace("&nbsp;", " ").replace("&amp;", "&").trim()
    return root.getAsJsonArray("abslist")?.mapNotNull { element ->
        val record = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val rid = record.text("MUSICRID").ifBlank { record.text("DC_TARGETID") }.ifBlank { return@mapNotNull null }
        KuwoSong(
            title = record.text("SONGNAME").ifBlank { record.text("NAME") },
            artist = record.text("ARTIST"),
            durationSeconds = record.text("DURATION").toIntOrNull() ?: 0,
            rid = if (rid.startsWith("MUSIC_")) rid else "MUSIC_$rid",
        )
    }.orEmpty()
}

/**
 * Kuwo's lyrics as asked for with `lrcx=1`: word-timed LRCX where it has it. The inflated body is
 * the text itself, or base64 of it XORed with the request key; either way GB18030.
 */
object KuwoLyrics {
    const val TRIES = 3
    /** Kuwo's own version markers (backing track, excerpt, live, cover, ringtone, DJ edit, preview). */
    val CUT = Regex("伴奏|片段|现场|翻唱|铃声|DJ版|试听")
    private val KEY = "yeelion".encodeToByteArray()
    private val TOKEN = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")
    private val LINE = Regex("^\\[(\\d+):(\\d+)(?:\\.(\\d+))?]")
    /** "Translation by …" notes that open some machine-translated lyrics. */
    private val NOTE = Regex("歌词翻译由.{0,24}提供")

    fun xor(raw: ByteArray): ByteArray = ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }

    fun text(inflated: ByteArray): String {
        val plain = if (inflated.dropWhile { it.toInt().toChar().isWhitespace() }.firstOrNull() == '['.code.toByte()) inflated
        else xor(Base64.getDecoder().decode(String(inflated, Charsets.US_ASCII).trim()))
        return String(plain, charset("GB18030")).removePrefix("\uFEFF")
    }

    /**
     * LRCX rewritten as KRC (`[start,length]<offset,length,0>word`), or null without word timing.
     * With k the `[kuwo:]` value read as octal, a word starts |a+b| / 2(k / 10) ms into its line
     * and lasts |a−b| / 2(k % 10) ms. Lines whose words are all `<0,0>` are translations.
     */
    fun krc(text: String): String? {
        if (!TOKEN.containsMatchIn(text)) return null
        val k = Regex("\\[kuwo:(\\d+)]").find(text)?.groupValues?.get(1)?.toIntOrNull(8) ?: return null
        val k1 = k / 10
        val k2 = k % 10
        if (k1 == 0 || k2 == 0) return null
        val out = text.lineSequence().mapNotNull { raw ->
            val m = LINE.find(raw.trimEnd('\r')) ?: return@mapNotNull null
            val tokens = TOKEN.findAll(raw.substring(m.range.last + 1)).map { it.groupValues }.toList()
            if (tokens.isEmpty() || tokens.all { it[1] == "0" && it[2] == "0" }) return@mapNotNull null
            val at = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong()
            val syls = tokens.map { (_, a, b, w) ->
                val x = a.toLong()
                val y = b.toLong()
                Triple(Math.floorDiv(kotlin.math.abs(x + y), 2L * k1), Math.floorDiv(kotlin.math.abs(x - y), 2L * k2), w)
            }
            "[$at,${syls.maxOf { it.first + it.second }}]" + syls.joinToString("") { (o, d, w) -> "<$o,$d,0>$w" }
        }.toList()
        return out.joinToString("\n").takeIf { out.isNotEmpty() }
    }

    /** The text as plain LRC: Kuwo's own tag lines and translation notes dropped. */
    fun lrc(text: String): String = text.lineSequence()
        .map { it.trimEnd('\r') }
        .filterNot { it.startsWith("[kuwo:") || it.startsWith("[ml:") || NOTE.containsMatchIn(it) }
        .joinToString("\n")
}

/** Kuwo's plain LRC (asked without `lrcx`): GB18030 with a byte-order mark, its own tag lines dropped. */
fun decodeKuwoLrc(bytes: ByteArray): String = KuwoLyrics.lrc(String(bytes, charset("GB18030")).removePrefix("\uFEFF"))
