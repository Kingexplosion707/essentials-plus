// Version 2: `[2, "<base64, unpadded>"]`. The format is defined by the
// JS reference encoder; this file mirrors it function for
// function, and every model decision must match it bit for bit.
//
// JS numbers become Long where the JS relies on exact doubles above 2^31 (the
// range coder, coded integers, millisecond values) and Int where it relies on
// int32 wrap-around (hashes, mixer weights). Object.is on doubles is a raw-bits
// comparison, and JS NaN "no value" markers are NONE.
package com.tx24.spicyplayer.network.data.slobjpack

private val SEED_STRINGS = listOf(
    "Type", "StartTime", "EndTime", "Content", "Lines", "Text", "IsPartOfWord",
    "Syllables", "Lead", "Background", "OppositeAligned", "TransliteratedText",
    "TranslatedText", "HasTranslations", "HasTransliterations", "SongWriters",
    "id", "TTMLUploadMetadata", "Uploader", "Maker", "username", "avatar",
    "hasProfileBanner", "source", "source_via", "SourceTTML",
    "Vocal", "Syllable", "Line", "Static", "spl", "aml",
)
private val SEED_SHAPES = listOf(
    listOf("Text", "IsPartOfWord", "StartTime", "EndTime"),
    listOf("Syllables", "StartTime", "EndTime"),
    listOf("Type", "OppositeAligned", "Lead"),
    listOf("Type", "OppositeAligned", "Lead", "Background"),
    listOf("Text"),
    listOf("Type", "OppositeAligned", "Text", "StartTime", "EndTime"),
    listOf("Text", "TransliteratedText", "IsPartOfWord", "StartTime", "EndTime"),
    listOf("Syllables", "StartTime", "EndTime", "TranslatedText"),
    listOf("Syllables", "StartTime", "EndTime", "TransliteratedText"),
    listOf("Type", "OppositeAligned", "Lead", "HasTransliterations"),
    listOf("Type", "OppositeAligned", "Lead", "HasTransliterations", "Background"),
    listOf("Type", "OppositeAligned", "Lead", "HasTranslations"),
    listOf("Type", "OppositeAligned", "Lead", "HasTranslations", "Background"),
    listOf("Type", "OppositeAligned", "Lead", "HasTranslations", "HasTransliterations"),
    listOf("Type", "StartTime", "Content", "EndTime"),
    listOf("Type", "StartTime", "EndTime", "Content"),
    listOf("Type", "SongWriters", "StartTime", "Content", "EndTime"),
    listOf("Type", "Lines"),
    // spicy-lyrics-api responses
    listOf("Type", "SongWriters", "StartTime", "Content", "EndTime", "id", "TTMLUploadMetadata", "source"),
    listOf("Type", "StartTime", "Content", "EndTime", "id", "TTMLUploadMetadata", "source"),
    listOf("Uploader", "Maker"),
    listOf("id", "username", "hasProfileBanner"),
    listOf("id", "username", "avatar", "hasProfileBanner"),
    listOf(),
)
private val SEED_STRING_SET = HashSet(SEED_STRINGS)

private const val KIND_NULL = 0
private const val KIND_BOOL = 1
private const val KIND_NUMBER = 2
private const val KIND_STRING = 3
private const val KIND_ARRAY = 4
private const val KIND_OBJECT = 5

/** Stands in for the JS NaN "no value yet"; real values are within ±MAX_MS. */
private const val NONE = Long.MIN_VALUE

// -------- Adaptive bit probabilities --------

private const val P_HALF = 32768
private const val RATE_LIMIT = 30
private val RATE = IntArray(RATE_LIMIT + 1) { n -> minOf(32767, 131072 / (2 * n + 3)) }

/** 16-bit P(bit = 1) per context (CharArray is the unsigned 16-bit array) and observation counts. */
private class BitModels(size: Int, initial: Int = P_HALF) {
    val p = CharArray(size).also { it.fill(initial.toChar()) }
    val n = ByteArray(size)
}

private fun adapt(p: CharArray, n: ByteArray, i: Int, bit: Int) {
    val count = n[i].toInt()
    val pi = p[i].code
    p[i] = if (bit != 0) (pi + (((65536 - pi) * RATE[count]) shr 16)).toChar()
    else (pi - ((pi * RATE[count]) shr 16)).toChar()
    if (count < RATE_LIMIT) n[i] = (count + 1).toByte()
}

private fun clampP(p: Int): Int = if (p < 48) 48 else if (p > 65488) 65488 else p

// -------- Range coder (LZMA's carry-less scheme, 16-bit probabilities) --------

private abstract class Coder {
    abstract val encoding: Boolean

    /** Codes `bit` with P(1) = p1 / 65536 (the decoder ignores `bit` and returns the decoded one). */
    abstract fun codeP(p1: Int, bit: Int): Int

    fun bit(m: BitModels, i: Int, bit: Int): Int {
        val b = codeP(clampP(m.p[i].code), bit)
        adapt(m.p, m.n, i, b)
        return b
    }
}

private class RangeEncoder : Coder() {
    override val encoding = true
    private var low = 0L
    private var range = 0xFFFFFFFFL
    private var cache = 0
    private var cacheSize = 1L
    private var buf = ByteArray(4096)
    private var len = 0

    private fun put(byte: Int) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = byte.toByte()
    }

    private fun shiftLow() {
        if (low < 0xFF000000L || low >= 0x100000000L) {
            val carry = if (low >= 0x100000000L) 1 else 0
            var temp = cache
            do {
                put((temp + carry) and 0xFF)
                temp = 0xFF
            } while (--cacheSize != 0L)
            cache = ((low ushr 24) and 0xFF).toInt()
        }
        cacheSize++
        low = (low and 0xFFFFFF) shl 8
    }

    override fun codeP(p1: Int, bit: Int): Int {
        val bound = (range ushr 16) * p1
        if (bit != 0) {
            range = bound
        } else {
            low += bound
            range -= bound
        }
        while (range < 0x1000000L) {
            range = range shl 8
            shiftLow()
        }
        return bit
    }

    fun finish(): ByteArray {
        // Settle on the value in [low, low + range) with the most trailing zero bits.
        for (shift in 32 downTo 1) {
            val step = 1L shl shift
            val value = (low + step - 1) / step * step
            if (value < low + range) {
                low = value
                break
            }
        }
        repeat(5) { shiftLow() }
        var end = len
        var dropped = 0
        while (dropped < 4 && end > 1 && buf[end - 1].toInt() == 0) {
            end--
            dropped++
        }
        return buf.copyOfRange(1, end)
    }
}

private class RangeDecoder(private val buf: ByteArray, start: Int) : Coder() {
    override val encoding = false
    private var pos = start
    private val end = buf.size
    private var range = 0xFFFFFFFFL
    private var code = 0L

    init {
        repeat(4) { code = (code shl 8) + next() }
    }

    private fun next(): Int {
        if (pos < end) return buf[pos++].toInt() and 0xFF
        // The encoder drops at most four trailing zero bytes.
        if (++pos > end + 4) throw SLObjPackException("SLObjPack unpack: Unexpected end of data")
        return 0
    }

    override fun codeP(p1: Int, bit: Int): Int {
        val bound = (range ushr 16) * p1
        val out: Int
        if (code < bound) {
            range = bound
            out = 1
        } else {
            code -= bound
            range -= bound
            out = 0
        }
        while (range < 0x1000000L) {
            range = range shl 8
            code = (code shl 8) + next()
        }
        return out
    }

    /** True once every stored byte has been consumed. */
    fun done(): Boolean = pos >= end
}

// -------- Integers --------

private fun bitLength(x: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(x)

private const val U_LEN = 0
private const val U_TOP = 24
private const val U_LOW = U_TOP + 32 * 8
private const val U_SIZE = U_LOW + 53

/** Codes v ≥ 0 (encoder) or decodes one (decoder; `v` is ignored). */
private fun codeUInt(c: Coder, m: BitModels, o: Int, v: Long): Long {
    val encoding = c.encoding
    val n1 = if (encoding) v + 1 else 0L
    val k = if (encoding) bitLength(n1) - 1 else 0
    var len = 0
    while (c.bit(m, o + U_LEN + (if (len < 23) len else 23), if (len < k) 1 else 0) != 0) {
        if (++len > 52) throw SLObjPackException("SLObjPack unpack: Integer too large")
    }
    val top = o + U_TOP + (if (len < 31) len else 31) * 8
    var n = 1L
    for (j in len - 1 downTo 0) {
        val bit = if (encoding) ((n1 ushr j) and 1L).toInt() else 0
        n = n * 2 + c.bit(m, if (len - j < 4) top + (if (n < 8) n.toInt() else 0) else o + U_LOW + j, bit)
    }
    return n - 1
}

private const val S_ZERO = 0
private const val S_SIGN = 8
private const val S_MAG = 9
private const val S_SIZE = S_MAG + U_SIZE

private fun codeSInt(c: Coder, m: BitModels, o: Int, zeroCtx: Int, v: Long): Long {
    if (c.bit(m, o + S_ZERO + zeroCtx, if (v == 0L) 1 else 0) != 0) return 0
    val negative = c.bit(m, o + S_SIGN, if (v < 0) 1 else 0)
    val magnitude = codeUInt(c, m, o + S_MAG, if (c.encoding) Math.abs(v) - 1 else 0L) + 1
    return if (negative != 0) -magnitude else magnitude
}

// -------- Per-slot model layout --------
private const val K_KIND = 0
private const val K_BOOL = 56
private const val K_SHAPE = 65
private const val K_COUNT = K_SHAPE + U_SIZE
private const val K_NUM_FORM = K_COUNT + U_SIZE
private const val K_NUM_DELTA = K_NUM_FORM + 16
private const val K_STR_HIT = K_NUM_DELTA + S_SIZE
private const val K_STR_OLD = K_STR_HIT + 6
private const val K_STR_RECENT = K_STR_OLD + 2
private const val K_STR_RANK = K_STR_RECENT + 1
private const val K_STR_ID = K_STR_RANK + U_SIZE
private const val K_STR_LEN = K_STR_ID + U_SIZE
private const val SLOT_SIZE = K_STR_LEN + U_SIZE

private const val G_KIND_SAME = 0
private const val G_SHAPE_SAME = 6
private const val G_NUM_ESC = 8
private const val G_SIZE = 9

private const val PREDICTORS = 5
private const val MAX_SLOTS = 1024
private const val RECENT_MAX = 256
private const val MAX_MS = 1L shl 50

private fun pairKey(a: Int, b: Int): Long = a.toLong() * 0x100000000L + b

private class Slot {
    val m = BitModels(SLOT_SIZE)
    var lastKind = 6
    var lastBool = 2
    var lastShape = -1
    var shapeSame = 1
    var lastForm = 3
    var lastK = NONE
    val costs = IntArray(PREDICTORS)
    var prevStr = -1
    var prevStr2 = -1
    val succ2 = HashMap<Long, Int>()
    var lastHit = 0
    val succ = HashMap<Int, Int>()
    val recent = IntArray(RECENT_MAX)
    var recentLen = 0
    var elem = -1
    val members = HashMap<Int, IntArray>()

    fun recentIndexOf(id: Int): Int {
        for (i in 0 until recentLen) if (recent[i] == id) return i
        return -1
    }
}

/** One per array or object being coded: inputs to the number predictors. */
private class Frame {
    var prevK = NONE
    var min = Long.MAX_VALUE // JS Infinity
    var max = NONE // JS -Infinity
    var anchor = -1

    fun close(parent: Frame) {
        if (min < parent.min) parent.min = min
        if (max > parent.max) parent.max = max
    }
}

private fun same(a: Double, b: Double): Boolean = a.toRawBits() == b.toRawBits()

/** The doubles that whole-millisecond k can stand for (see the JS). */
private fun timeForms(k: Long, out: DoubleArray): Int {
    val kd = k.toDouble()
    out[0] = kd / 1000.0
    var count = 1
    if (k >= 60000) {
        val minutes = Math.floor(kd / 60000.0)
        val viaMinutes = (kd - minutes * 60000.0) / 1000.0 + minutes * 60.0
        if (!same(viaMinutes, out[0])) out[count++] = viaMinutes
        if (k >= 3600000) {
            val hours = Math.floor(kd / 3600000.0)
            val rest = kd - hours * 3600000.0
            val m = Math.floor(rest / 60000.0)
            val viaHours = (rest - m * 60000.0) / 1000.0 + m * 60.0 + hours * 3600.0
            if (!same(viaHours, out[0]) && !same(viaHours, out[count - 1])) out[count++] = viaHours
        }
    }
    return count
}

// -------- Text: bytewise context mixing --------

private val SQUASH_T = intArrayOf(
    1, 2, 3, 6, 10, 16, 27, 45, 73, 120, 194, 310, 488, 747, 1101, 1546,
    2047, 2549, 2994, 3348, 3607, 3785, 3901, 3975, 4022, 4050, 4068, 4079, 4085, 4089, 4092, 4093, 4094,
)

private fun squash(d: Int): Int {
    if (d > 2047) return 4095
    if (d < -2047) return 1
    val w = d and 127
    val i = (d shr 7) + 16
    return (SQUASH_T[i] * (128 - w) + SQUASH_T[i + 1] * w + 64) shr 7
}

private val STRETCH = IntArray(4096).also { stretch ->
    var pi = 0
    for (x in -2047..2047) {
        val v = squash(x)
        for (i in pi..v) stretch[i] = x
        pi = v + 1
    }
    for (i in pi until 4096) stretch[i] = 2047
}

private fun mix32(input: Int): Int {
    var h = input
    h = h xor (h ushr 16)
    h *= 0x85ebca6b.toInt()
    h = h xor (h ushr 13)
    h *= 0xc2b2ae35.toInt()
    h = h xor (h ushr 16)
    return h
}

private val PARTIAL_HASH = IntArray(256) { mix32(it + 0x51ed27) }

private const val TM_INPUTS = 6
private const val TM_W_INIT = 20000
private const val TM_LR = 5

private class TextModel(private val c: Coder, private val bits: Int) {
    private val mask = (1 shl bits) - 1
    private val p = CharArray(TM_INPUTS shl bits).also { it.fill(P_HALF.toChar()) }
    private val n = ByteArray(TM_INPUTS shl bits)
    private val ctx = IntArray(TM_INPUTS)
    private val idx = IntArray(TM_INPUTS)
    private val st = IntArray(TM_INPUTS + 1)
    private val w = IntArray(8 * (TM_INPUTS + 1)).also { it.fill(TM_W_INIT) }
    private var hist = 0
    private var word = 0
    private var slot = 0

    init {
        refresh()
    }

    /** Starts a new literal: a separator byte in the history, and the slot as context. */
    fun begin(slotId: Int) {
        slot = slotId
        push(0)
    }

    private fun push(byte: Int) {
        hist = (hist shl 8) or byte
        val letter = (byte in 97..122) || (byte in 65..90) || byte >= 128
        word = if (letter) mix32(word + (byte or 32)) else 0
        refresh()
    }

    private fun refresh() {
        val h = hist
        ctx[0] = mix32(slot + 0x1000000)
        ctx[1] = mix32((h and 0xFF) + 0x2000000)
        ctx[2] = mix32((h and 0xFFFF) xor 0x3000000)
        ctx[3] = mix32(((h and 0xFFFFFF) * 3) xor 0x4000000)
        ctx[4] = mix32(h xor 0x5EED5EED)
        ctx[5] = mix32(word xor 0x6D2B79F5)
    }

    fun code(byte: Int): Int {
        var c0 = 1
        for (j in 7 downTo 0) {
            val partial = PARTIAL_HASH[c0]
            val wo = (7 - j) * (TM_INPUTS + 1)
            var dot = 0L
            for (i in 0 until TM_INPUTS) {
                val ix = (i shl bits) or ((ctx[i] xor partial) and mask)
                idx[i] = ix
                val x = STRETCH[p[ix].code shr 4]
                st[i] = x
                dot += w[wo + i].toLong() * x
            }
            st[TM_INPUTS] = 256
            dot += w[wo + TM_INPUTS].toLong() * 256
            val pr = squash((dot / 65536).toInt())
            val bit = c.codeP(pr shl 4, (byte shr j) and 1)
            val err = ((bit shl 12) - pr) * TM_LR
            for (i in 0..TM_INPUTS) w[wo + i] += (st[i] * err) shr 12
            for (i in 0 until TM_INPUTS) adapt(p, n, idx[i], bit)
            c0 = (c0 shl 1) or bit
        }
        val out = c0 and 255
        push(out)
        return out
    }
}

private fun textBitsFor(textBytes: Int): Int {
    val bits = bitLength(textBytes.toLong()) + 3
    return if (bits < 10) 10 else if (bits > 20) 20 else bits
}

// -------- WTF-8: UTF-8 that also carries lone surrogates, so any string round-trips --------

private fun wtf8Length(str: String): Int {
    var len = 0
    var i = 0
    while (i < str.length) {
        val u = str[i].code
        if (u < 0x80) len += 1
        else if (u < 0x800) len += 2
        else if (u in 0xD800..0xDBFF && i + 1 < str.length && str[i + 1].code in 0xDC00..0xDFFF) {
            len += 4
            i++
        } else len += 3
        i++
    }
    return len
}

private fun wtf8Encode(str: String, out: ByteArray): Int {
    var o = 0
    var i = 0
    while (i < str.length) {
        var u = str[i].code
        if (u < 0x80) {
            out[o++] = u.toByte()
        } else if (u < 0x800) {
            out[o++] = (0xC0 or (u shr 6)).toByte()
            out[o++] = (0x80 or (u and 63)).toByte()
        } else if (u in 0xD800..0xDBFF && i + 1 < str.length && str[i + 1].code in 0xDC00..0xDFFF) {
            u = 0x10000 + ((u - 0xD800) shl 10) + (str[i + 1].code - 0xDC00)
            i++
            out[o++] = (0xF0 or (u shr 18)).toByte()
            out[o++] = (0x80 or ((u shr 12) and 63)).toByte()
            out[o++] = (0x80 or ((u shr 6) and 63)).toByte()
            out[o++] = (0x80 or (u and 63)).toByte()
        } else {
            out[o++] = (0xE0 or (u shr 12)).toByte()
            out[o++] = (0x80 or ((u shr 6) and 63)).toByte()
            out[o++] = (0x80 or (u and 63)).toByte()
        }
        i++
    }
    return o
}

/** Lenient: bytes no encoder produced decode to U+FFFD rather than throwing. */
private fun wtf8Decode(bytes: ByteArray, len: Int): String {
    val out = StringBuilder(len)
    var i = 0
    while (i < len) {
        val b = bytes[i].toInt() and 0xFF
        var cp = 0xFFFD
        var size = 1
        if (b < 0x80) {
            cp = b
        } else if (b in 0xC0..0xDF && i + 1 < len) {
            cp = ((b and 31) shl 6) or (bytes[i + 1].toInt() and 63)
            size = 2
        } else if (b in 0xE0..0xEF && i + 2 < len) {
            cp = ((b and 15) shl 12) or ((bytes[i + 1].toInt() and 63) shl 6) or (bytes[i + 2].toInt() and 63)
            size = 3
        } else if (b in 0xF0..0xF7 && i + 3 < len) {
            cp = ((b and 7) shl 18) or ((bytes[i + 1].toInt() and 63) shl 12) or
                ((bytes[i + 2].toInt() and 63) shl 6) or (bytes[i + 3].toInt() and 63)
            size = 4
            if (cp > 0x10FFFF) cp = 0xFFFD
        }
        i += size
        if (cp >= 0x10000) {
            cp -= 0x10000
            out.append((0xD800 + (cp shr 10)).toChar())
            out.append((0xDC00 + (cp and 1023)).toChar())
        } else {
            out.append(cp.toChar())
        }
    }
    return out.toString()
}

// -------- Base64 (standard alphabet, unpadded) --------
// Hand-rolled: java.util.Base64 needs Android API 26, and this must accept and
// reject exactly what the JS does.

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private val B64_INDEX = IntArray(128) { -1 }.also { for (i in 0 until 64) it[B64[i].code] = i }

private fun base64Encode(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
        out.append(B64[v shr 18]).append(B64[(v shr 12) and 63]).append(B64[(v shr 6) and 63]).append(B64[v and 63])
        i += 3
    }
    val rest = bytes.size - i
    if (rest == 1) {
        val v = (bytes[i].toInt() and 0xFF) shl 16
        out.append(B64[v shr 18]).append(B64[(v shr 12) and 63])
    } else if (rest == 2) {
        val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
        out.append(B64[v shr 18]).append(B64[(v shr 12) and 63]).append(B64[(v shr 6) and 63])
    }
    return out.toString()
}

private fun base64Decode(str: String): ByteArray {
    if (str.length % 4 == 1) throw SLObjPackException("SLObjPack unpack: Invalid base64 length")
    val out = ByteArray((str.length.toLong() * 3 / 4).toInt())
    var o = 0
    var acc = 0
    var bits = 0
    for (i in str.indices) {
        val ch = str[i].code
        val v = if (ch < 128) B64_INDEX[ch] else -1
        if (v < 0) throw SLObjPackException("SLObjPack unpack: Invalid base64 character")
        acc = ((acc shl 6) or v) and 0xFFFFFF
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[o++] = ((acc shr bits) and 0xFF).toByte()
        }
    }
    return out
}

// -------- Varints (header only) --------

private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
    var v = value
    while (v >= 128) {
        out.write(((v % 128) or 128).toInt())
        v /= 128
    }
    out.write(v.toInt())
}

private class Cursor(var pos: Int)

private fun readVarint(bytes: ByteArray, at: Cursor): Long {
    var v = 0L
    var scale = 1L
    for (i in 0 until 8) {
        if (at.pos >= bytes.size) throw SLObjPackException("SLObjPack unpack: Truncated header")
        val b = bytes[at.pos++].toInt() and 0xFF
        v += (b and 127) * scale
        if (b < 128) return v
        scale *= 128
    }
    throw SLObjPackException("SLObjPack unpack: Invalid header")
}

// -------- The model, shared by both directions --------

private class V2Model(
    private val c: Coder,
    textBytes: Int,
    private val limits: Limits,
    private val forbiddenKeys: Set<String>,
) {
    private val slots = ArrayList<Slot>()
    private val slotIds = HashMap<String, Int>()
    val root = newSlot()
    private val keySlot = newSlot()
    private val overflow = newSlot()
    private val strs = ArrayList<String>(SEED_STRINGS)
    private val strIds: HashMap<String, Int>? =
        if (c.encoding) HashMap<String, Int>().also { m -> SEED_STRINGS.forEachIndexed { i, s -> m[s] = i } } else null
    private val shapes = ArrayList<List<String>>(SEED_SHAPES)
    private val shapeIds: HashMap<List<String>, Int>? =
        if (c.encoding) HashMap<List<String>, Int>().also { m -> SEED_SHAPES.forEachIndexed { i, k -> m[k] = i } } else null
    private val boolMem = HashMap<Long, Int>()
    private var lastNum = NONE
    private val cand = LongArray(PREDICTORS)
    private val forms = DoubleArray(3)
    private val raw = BitModels(64)
    private val g = BitModels(G_SIZE, 60000)
    private val text = TextModel(c, textBitsFor(textBytes))
    private var textBuf = ByteArray(256)
    private val textLimit = textBytes
    var textBytes = 0
        private set
    var ops = 0L
        private set
    var opsLimit = Long.MAX_VALUE

    private fun newSlot(): Int {
        slots.add(Slot())
        return slots.size - 1
    }

    private fun childSlot(parent: Int, key: String): Int {
        if (parent == overflow) return parent
        val name = "$parent.$key"
        return slotIds.getOrPut(name) { if (slots.size < MAX_SLOTS) newSlot() else overflow }
    }

    private fun elemSlot(slotId: Int): Int {
        val s = slots[slotId]
        if (s.elem < 0) s.elem = childSlot(slotId, "[")
        return s.elem
    }

    private fun memberSlots(slotId: Int, shapeId: Int): IntArray {
        val s = slots[slotId]
        return s.members.getOrPut(shapeId) {
            val keys = shapes[shapeId]
            IntArray(keys.size).also { for (i in keys.indices) it[i] = childSlot(slotId, "." + keys[i]) }
        }
    }

    private fun tick() {
        if (++ops > opsLimit) throw SLObjPackException("SLObjPack unpack: Value count exceeds header")
    }

    private fun kind(s: Slot, kind: Int): Int {
        if (s.lastKind < 6 && c.bit(g, G_KIND_SAME + s.lastKind, if (kind == s.lastKind) 1 else 0) != 0) return s.lastKind
        val base = K_KIND + s.lastKind * 8
        var node = 1
        for (i in 2 downTo 0) node = node * 2 + c.bit(s.m, base + node, (kind shr i) and 1)
        val coded = node - 8
        if (coded > KIND_OBJECT) throw SLObjPackException("SLObjPack unpack: Invalid value kind")
        s.lastKind = coded
        return coded
    }

    private fun count(s: Slot, n: Int, max: Long, label: String): Int {
        val coded = codeUInt(c, s.m, K_COUNT, n.toLong())
        if (coded > max) throw SLObjPackException("SLObjPack unpack: Invalid $label count: $coded")
        return coded.toInt()
    }

    private fun bool(s: Slot, slotId: Int, frame: Frame, v: Boolean): Boolean {
        val memKey = if (frame.anchor >= 0) pairKey(slotId, frame.anchor) else -1L
        val mem = if (memKey >= 0) boolMem[memKey] ?: 2 else 2
        val bit = c.bit(s.m, K_BOOL + s.lastBool * 3 + mem, if (v) 1 else 0)
        s.lastBool = bit
        if (memKey >= 0) boolMem[memKey] = bit
        return bit == 1
    }

    private fun shape(s: Slot, keys: List<String>?): Int {
        var id = -1
        if (c.encoding) id = shapeIds!![keys!!] ?: -1
        if (s.lastShape >= 0) {
            val same = c.bit(g, G_SHAPE_SAME + s.shapeSame, if (id == s.lastShape) 1 else 0)
            s.shapeSame = same
            if (same != 0) return s.lastShape
        }
        val code = codeUInt(c, s.m, K_SHAPE, (id + 1).toLong())
        if (code > 0) {
            if (code - 1 >= shapes.size) throw SLObjPackException("SLObjPack unpack: Invalid shape reference")
            id = (code - 1).toInt()
        } else {
            val ks = slots[keySlot]
            val n = count(ks, if (c.encoding) keys!!.size else 0, limits.objectKeys.toLong(), "object key")
            val list = ArrayList<String>(minOf(n, 64))
            val seen = if (c.encoding) null else HashSet<String>()
            val frame = Frame()
            for (i in 0 until n) {
                tick()
                val key = string(ks, keySlot, frame, if (c.encoding) keys!![i] else null)
                if (seen != null) {
                    if (key in forbiddenKeys) throw SLObjPackException("SLObjPack unpack: Forbidden key: $key")
                    if (!seen.add(key)) throw SLObjPackException("SLObjPack unpack: Duplicate key: $key")
                }
                list.add(key)
            }
            id = shapes.size
            shapes.add(list)
            shapeIds?.put(list, id)
        }
        s.lastShape = id
        return id
    }

    private fun number(s: Slot, frame: Frame, v: Double): Double {
        var k = 0L
        var form = -1
        if (c.encoding) {
            // Java's Math.round rounds half up like JS, and never yields -0, so -0 matches no form.
            k = Math.round(v * 1000)
            if (k >= -MAX_MS && k <= MAX_MS) {
                val count = timeForms(k, forms)
                for (i in 0 until count) {
                    if (same(forms[i], v)) {
                        form = i
                        break
                    }
                }
            }
        }
        if (c.bit(g, G_NUM_ESC, if (form < 0) 1 else 0) != 0) return rawNumber(v)

        cand[0] = lastNum
        cand[1] = s.lastK
        cand[2] = frame.prevK
        cand[3] = if (frame.min == Long.MAX_VALUE) NONE else frame.min
        cand[4] = frame.max
        val costs = s.costs
        var best = -1
        for (i in 0 until PREDICTORS) {
            if (cand[i] != NONE && (best < 0 || costs[i] < costs[best])) best = i
        }
        val pred = if (best < 0) 0L else cand[best]
        k = pred + codeSInt(c, s.m, K_NUM_DELTA, best + 1, k - pred)
        if (k < -MAX_MS || k > MAX_MS) throw SLObjPackException("SLObjPack unpack: Invalid number")
        for (i in 0 until PREDICTORS) {
            val miss = if (cand[i] != NONE) bitLength(Math.abs(k - cand[i])) else 40
            costs[i] += miss * 16 - (costs[i] shr 3)
        }

        val count = timeForms(k, forms)
        if (count > 1) {
            val base = K_NUM_FORM + s.lastForm * 4
            var f = c.bit(s.m, base + 1, if (form > 0) 1 else 0)
            if (f != 0 && count > 2) f += c.bit(s.m, base + 2, if (form > 1) 1 else 0)
            form = f
        } else {
            form = 0
        }
        if (!c.encoding && form >= count) throw SLObjPackException("SLObjPack unpack: Invalid number form")
        s.lastForm = form

        s.lastK = k
        lastNum = k
        frame.prevK = k
        if (k < frame.min) frame.min = k
        if (k > frame.max) frame.max = k
        return forms[form]
    }

    private fun rawNumber(v: Double): Double {
        // Little-endian byte order, most significant bit of each byte first, as the JS DataView.
        val known = if (c.encoding) v.toRawBits() else 0L
        var bits = 0L
        for (byte in 0 until 8) {
            val knownByte = ((known ushr (byte * 8)) and 0xFF).toInt()
            var value = 0
            for (j in 7 downTo 0) value = (value shl 1) or c.bit(raw, byte * 8 + 7 - j, (knownByte shr j) and 1)
            bits = bits or (value.toLong() shl (byte * 8))
        }
        val out = Double.fromBits(bits)
        if (!out.isFinite()) throw SLObjPackException("SLObjPack unpack: Non-finite number")
        return out
    }

    private fun string(s: Slot, slotId: Int, frame: Frame, str: String?): String {
        var id = -1
        if (c.encoding) id = strIds!![str!!] ?: -1
        // Guess what followed the previous two strings last time, then what followed the previous one.
        val pair = if (s.prevStr2 >= 0) pairKey(s.prevStr2, s.prevStr) else -1L
        val guess2 = if (pair >= 0) s.succ2[pair] ?: -1 else -1
        val guess1 = if (s.prevStr >= 0) s.succ[s.prevStr] ?: -1 else -1
        var hit = 0
        if (guess2 >= 0) {
            hit = c.bit(s.m, K_STR_HIT + s.lastHit, if (id == guess2) 1 else 0)
            if (hit != 0) id = guess2
        }
        if (hit == 0 && guess1 >= 0 && guess1 != guess2) {
            hit = c.bit(s.m, K_STR_HIT + 2 + (if (guess2 >= 0) 2 else 0) + s.lastHit, if (id == guess1) 1 else 0)
            if (hit != 0) id = guess1
        }
        val guessed = guess2 >= 0 || guess1 >= 0
        if (guessed) s.lastHit = hit
        val recent = s.recent
        var rank = -1
        if (hit == 0) {
            if (c.bit(s.m, K_STR_OLD + (if (guessed) 1 else 0), if (id >= 0) 1 else 0) != 0) {
                if (c.encoding) rank = s.recentIndexOf(id)
                if (c.bit(s.m, K_STR_RECENT, if (rank >= 0) 1 else 0) != 0) {
                    val coded = codeUInt(c, s.m, K_STR_RANK, rank.toLong())
                    if (coded >= s.recentLen) throw SLObjPackException("SLObjPack unpack: Invalid string reference")
                    rank = coded.toInt()
                    id = recent[rank]
                } else {
                    val coded = codeUInt(c, s.m, K_STR_ID, id.toLong())
                    if (coded >= strs.size) throw SLObjPackException("SLObjPack unpack: Invalid string reference")
                    id = coded.toInt()
                }
            } else {
                id = strs.size
                strs.add(literal(s, slotId, str))
                strIds?.put(str!!, id)
            }
        }

        // Move to front, as the JS's splice/pop/unshift.
        if (rank < 0) rank = s.recentIndexOf(id)
        if (rank != 0) {
            val shift = if (rank > 0) rank else if (s.recentLen >= RECENT_MAX) RECENT_MAX - 1 else s.recentLen
            System.arraycopy(recent, 0, recent, 1, shift)
            recent[0] = id
            if (rank < 0 && s.recentLen < RECENT_MAX) s.recentLen++
        }
        if (s.prevStr >= 0) s.succ[s.prevStr] = id
        if (pair >= 0) s.succ2[pair] = id
        s.prevStr2 = s.prevStr
        s.prevStr = id
        if (frame.anchor < 0) frame.anchor = id
        return strs[id]
    }

    private fun literal(s: Slot, slotId: Int, str: String?): String {
        var len = if (c.encoding) wtf8Length(str!!) else 0
        if (textBuf.size < len) textBuf = ByteArray(len * 2)
        if (c.encoding) wtf8Encode(str!!, textBuf)
        val coded = codeUInt(c, s.m, K_STR_LEN, len.toLong())
        if (coded > textLimit - textBytes) throw SLObjPackException("SLObjPack unpack: Text exceeds header")
        len = coded.toInt()
        textBytes += len
        if (textBuf.size < len) textBuf = ByteArray(len * 2)
        val buf = textBuf
        text.begin(slotId)
        for (i in 0 until len) buf[i] = text.code(buf[i].toInt() and 0xFF).toByte()
        return if (c.encoding) str!! else wtf8Decode(buf, len)
    }

    fun encode(slotId: Int, frame: Frame, v: Any?) {
        tick()
        val s = slots[slotId]
        when (v) {
            null -> {
                kind(s, KIND_NULL)
                return
            }
            is Boolean -> {
                kind(s, KIND_BOOL)
                bool(s, slotId, frame, v)
                return
            }
            is Double -> {
                kind(s, KIND_NUMBER)
                number(s, frame, v)
                return
            }
            is String -> {
                kind(s, KIND_STRING)
                string(s, slotId, frame, v)
                return
            }
        }
        val inner = Frame()
        if (v is List<*>) {
            kind(s, KIND_ARRAY)
            count(s, v.size, Long.MAX_VALUE, "array item")
            val elem = elemSlot(slotId)
            for (item in v) encode(elem, inner, item)
        } else {
            val map = v as Map<*, *>
            kind(s, KIND_OBJECT)
            @Suppress("UNCHECKED_CAST")
            val keys = ArrayList(map.keys as Set<String>)
            val members = memberSlots(slotId, shape(s, keys))
            var i = 0
            for (value in map.values) encode(members[i++], inner, value)
        }
        inner.close(frame)
    }

    fun decode(slotId: Int, frame: Frame, depth: Int): Any? {
        if (depth > limits.depth) throw SLObjPackException("SLObjPack unpack: Max depth exceeded")
        tick()
        val s = slots[slotId]
        when (kind(s, 0)) {
            KIND_NULL -> return null
            KIND_BOOL -> return bool(s, slotId, frame, false)
            KIND_NUMBER -> return number(s, frame, 0.0)
            KIND_STRING -> return string(s, slotId, frame, null)
            KIND_ARRAY -> {
                val n = count(s, 0, minOf(limits.arrayLength.toLong(), opsLimit - ops), "array item")
                val inner = Frame()
                val elem = elemSlot(slotId)
                // Grown as values decode rather than presized from the coded count.
                val arr = ArrayList<Any?>(minOf(n, 1024))
                for (i in 0 until n) arr.add(decode(elem, inner, depth + 1))
                inner.close(frame)
                return arr
            }
            else -> {
                val shapeId = shape(s, null)
                val keys = shapes[shapeId]
                val members = memberSlots(slotId, shapeId)
                val inner = Frame()
                val obj = LinkedHashMap<String, Any?>(keys.size * 2)
                for (i in keys.indices) obj[keys[i]] = decode(members[i], inner, depth + 1)
                inner.close(frame)
                return obj
            }
        }
    }
}

/** Total bytes of the strings (values and keys) the encoder will code as literals. */
private fun literalBytes(safe: Any?): Long {
    val seen = HashSet<String>(SEED_STRING_SET)
    var total = 0L
    fun visit(str: String) {
        if (seen.add(str)) total += wtf8Length(str)
    }
    fun walk(node: Any?) {
        when (node) {
            is String -> visit(node)
            is List<*> -> for (item in node) walk(item)
            is Map<*, *> -> for ((k, v) in node) {
                visit(k as String)
                walk(v)
            }
        }
    }
    walk(safe)
    return total
}

/** Packs a snapshot tree; returns the base64 half of `[2, "<base64>"]`. */
fun packV2(safe: Any?, limits: Limits): String {
    val textBytes = literalBytes(safe)
    if (textBytes > limits.textBytes) throw SLObjPackException("SLObjPack pack: Text exceeds limit")
    val coder = RangeEncoder()
    val model = V2Model(coder, textBytes.toInt(), DEFAULT_LIMITS, DEFAULT_FORBIDDEN_KEYS)
    model.encode(model.root, Frame(), safe)
    if (model.textBytes.toLong() != textBytes) {
        throw SLObjPackException("SLObjPack pack: Internal error — literal byte count mismatch")
    }
    if (model.ops > limits.decodeOps) throw SLObjPackException("SLObjPack pack: Value count exceeds limit")
    val body = coder.finish()
    val out = java.io.ByteArrayOutputStream(body.size + 8)
    writeVarint(out, model.ops)
    writeVarint(out, textBytes)
    out.write(body)
    return base64Encode(out.toByteArray())
}

fun unpackV2(data: Any?, limits: Limits, forbiddenKeys: Set<String>): Any? {
    if (data !is String) throw SLObjPackException("SLObjPack unpack: Invalid payload structure")
    if (data.length > limits.streamLength) throw SLObjPackException("SLObjPack unpack: Payload exceeds limit")
    val bytes = base64Decode(data)
    val at = Cursor(0)
    val ops = readVarint(bytes, at)
    val textBytes = readVarint(bytes, at)
    if (ops > limits.decodeOps) throw SLObjPackException("SLObjPack unpack: Value count exceeds limit")
    if (textBytes > limits.textBytes) throw SLObjPackException("SLObjPack unpack: Text exceeds limit")
    val coder = RangeDecoder(bytes, at.pos)
    val model = V2Model(coder, textBytes.toInt(), limits, forbiddenKeys)
    model.opsLimit = ops
    val result = model.decode(model.root, Frame(), 0)
    if (model.ops != ops || model.textBytes.toLong() != textBytes) {
        throw SLObjPackException("SLObjPack unpack: Payload doesn't match its header")
    }
    // Trailing garbage rejection — prevents hidden payloads tacked on.
    if (!coder.done()) throw SLObjPackException("SLObjPack unpack: Extra data after decoding")
    return result
}
