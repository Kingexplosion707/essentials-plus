package com.tx24.spicyplayer.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A published build of this app, from the repository's GitHub Releases. */
data class AppRelease(
    val tag: String,
    /** The tag without its leading "v", e.g. "0.2.0". */
    val version: String,
    val name: String,
    val notes: ReleaseNotes,
    /** When it was published, as "2026-10-29"; null when GitHub doesn't say. */
    val date: String?,
    val prerelease: Boolean,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** The `sha256sum` file published next to the APK, when there is one. */
    val sha256Url: String?,
    val pageUrl: String,
)

object Releases {
    /** Reads the GitHub `releases` API response; drafts and releases without an APK are left out. */
    fun parse(json: String): List<AppRelease> {
        val array = JsonParser.parseString(json) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val release = element as? JsonObject ?: return@mapNotNull null
            if (release.bool("draft")) return@mapNotNull null
            val tag = release.string("tag_name") ?: return@mapNotNull null
            val assets = release.getAsJsonArray("assets")?.mapNotNull { it as? JsonObject }.orEmpty()
            // Only this app's builds, as the release workflow names them ("spicy-lyrics-mobile-v1.2.3.apk",
            // or "spicy-player-v1.2.3.apk" from before the rename and alongside it, for installs that
            // only know that name): the repository name once belonged to an older app with its own releases.
            val apk = assets.firstOrNull { asset ->
                asset.string("name")?.let { name -> APK_PREFIXES.any(name::startsWith) && name.endsWith(".apk") } == true
            } ?: return@mapNotNull null
            val apkName = apk.string("name")!!
            AppRelease(
                tag = tag,
                version = tag.removePrefix("v"),
                name = release.string("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = ReleaseNotes.parse(release.string("body").orEmpty()),
                date = release.string("published_at")?.take(10),
                prerelease = release.bool("prerelease"),
                apkUrl = apk.string("browser_download_url") ?: return@mapNotNull null,
                apkSizeBytes = apk.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                sha256Url = assets.firstOrNull { it.string("name") == "$apkName.sha256" }?.string("browser_download_url"),
                pageUrl = release.string("html_url").orEmpty(),
            )
        }
    }

    /** The newest release above [currentVersion], or null when there is none to offer. */
    fun newest(releases: List<AppRelease>, currentVersion: String, includePrereleases: Boolean): AppRelease? =
        releases
            .filter { includePrereleases || !it.prerelease }
            .filter { compareVersions(it.version, currentVersion) > 0 }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

    /**
     * Compares "1.2.3" style versions, ignoring a leading "v" and a "-debug" suffix. A version
     * with a pre-release label ("1.2.0-beta.1") is older than the same version without one.
     */
    fun compareVersions(a: String, b: String): Int {
        val (coreA, labelA) = split(a)
        val (coreB, labelB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val diff = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            labelA == labelB -> 0
            labelA == null -> 1
            labelB == null -> -1
            else -> compareLabels(labelA, labelB)
        }
    }

    private fun split(version: String): Pair<List<Int>, String?> {
        val clean = version.trim().removePrefix("v").removeSuffix("-debug")
        val core = clean.substringBefore('-')
        val label = clean.substringAfter('-', "").takeIf { it.isNotEmpty() }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to label
    }

    /** "alpha" < "beta" < "rc", and numbered parts compare as numbers ("beta.2" < "beta.10"). */
    private fun compareLabels(a: String, b: String): Int {
        val partsA = a.split('.')
        val partsB = b.split('.')
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val x = partsA.getOrNull(i) ?: return -1
            val y = partsB.getOrNull(i) ?: return 1
            val diff = when {
                x.toIntOrNull() != null && y.toIntOrNull() != null -> x.toInt().compareTo(y.toInt())
                else -> x.compareTo(y)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private val APK_PREFIXES = listOf("spicy-lyrics-mobile-", "spicy-player-")
}
