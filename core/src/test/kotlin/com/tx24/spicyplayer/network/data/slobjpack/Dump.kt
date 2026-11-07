// Reader for the typed dumps written by fixtures/make-fixtures.ts, and an exact
// tree comparison (raw double bits, key order).
package com.tx24.spicyplayer.network.data.slobjpack

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

class DumpReader(input: InputStream) : AutoCloseable {
    private val data = DataInputStream(BufferedInputStream(input, 1 shl 20))

    /** True if another record follows. */
    fun hasMore(): Boolean {
        data.mark(1)
        val next = data.read()
        data.reset()
        return next >= 0
    }

    fun u8(): Int = data.readUnsignedByte()

    fun string(): String {
        val len = data.readInt()
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = data.readChar()
        return String(chars)
    }

    fun value(): Any? = when (val tag = data.readUnsignedByte()) {
        0 -> null
        1 -> false
        2 -> true
        3 -> Double.fromBits(data.readLong())
        4 -> string()
        5 -> {
            val n = data.readInt()
            ArrayList<Any?>(n).also { list -> repeat(n) { list.add(value()) } }
        }
        6 -> {
            val n = data.readInt()
            LinkedHashMap<String, Any?>(n * 2).also { map -> repeat(n) { map[string()] = value() } }
        }
        else -> throw EOFException("bad dump tag $tag")
    }

    override fun close() = data.close()
}

/** One record of a corpus/golden fixture. */
class Record(val doc: Any?, val v1: Any?, val v2: String)

fun DumpReader.record(): Record = Record(value(), value(), string())

/**
 * Null if [a] and [b] are the same tree (doubles compared by raw bits, keys in
 * order), otherwise the path of the first difference. [zeroSigned] = false lets
 * -0 equal 0, which version 1 doesn't preserve.
 */
fun difference(a: Any?, b: Any?, path: String = "$", zeroSigned: Boolean = true): String? {
    when {
        a is Double && b is Double -> {
            val same = a.toRawBits() == b.toRawBits() || (!zeroSigned && a == 0.0 && b == 0.0)
            return if (same) null else "$path: $a != $b"
        }
        a is List<*> && b is List<*> -> {
            if (a.size != b.size) return "$path: length ${a.size} != ${b.size}"
            for (i in a.indices) difference(a[i], b[i], "$path[$i]", zeroSigned)?.let { return it }
            return null
        }
        a is Map<*, *> && b is Map<*, *> -> {
            val ka = a.keys.toList()
            val kb = b.keys.toList()
            if (ka != kb) return "$path: keys $ka != $kb"
            for (k in ka) difference(a[k], b[k], "$path.$k", zeroSigned)?.let { return it }
            return null
        }
        else -> return if (a == b && (a == null) == (b == null)) null else "$path: ${describe(a)} != ${describe(b)}"
    }
}

private fun describe(v: Any?): String = if (v == null) "null" else "${v::class.simpleName}(${v.toString().take(60)})"
