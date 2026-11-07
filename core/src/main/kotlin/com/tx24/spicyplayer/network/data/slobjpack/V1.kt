// Version 1 unpack: `[valuesList, stream]`, a value table plus an opcode stream.
package com.tx24.spicyplayer.network.data.slobjpack

fun unpackV1(packed: List<*>, limits: Limits, forbiddenKeys: Set<String>): Any? {
    val valuesRaw = packed[0]
    val streamRaw = packed[1]
    if (valuesRaw !is List<*> || streamRaw !is List<*>) {
        throw SLObjPackException("SLObjPack unpack: Invalid payload structure")
    }
    if (valuesRaw.size > limits.valuesLength) throw SLObjPackException("SLObjPack unpack: valuesList exceeds limit")
    if (streamRaw.size > limits.streamLength) throw SLObjPackException("SLObjPack unpack: stream exceeds limit")

    // Numbers come back as Doubles whatever the JSON parser produced.
    val values = arrayOfNulls<Any?>(valuesRaw.size)
    for ((i, v) in valuesRaw.withIndex()) {
        values[i] = when (v) {
            null, is String, is Boolean -> v
            is Number -> {
                val d = v.toDouble()
                if (!d.isFinite()) throw SLObjPackException("SLObjPack unpack: Non-finite number in valuesList at $i")
                d
            }
            else -> throw SLObjPackException("SLObjPack unpack: Invalid valuesList entry at $i (type ${jsType(v)})")
        }
    }
    val stream = streamRaw
    val streamLen = stream.size
    var cursor = 0

    fun readStream(): Any? {
        if (cursor >= streamLen) throw SLObjPackException("SLObjPack unpack: Unexpected end of stream")
        return stream[cursor++]
    }

    /** The integer an entry holds, or null if it isn't an integral number. */
    fun integer(v: Any?): Long? {
        if (v !is Number) return null
        val d = v.toDouble()
        return if (d.isFinite() && d == Math.floor(d) && Math.abs(d) <= 9007199254740991.0) d.toLong() else null
    }

    fun resolvePointer(ptr: Any?): Any? {
        val i = integer(ptr)
        if (i == null || i < 0 || i >= values.size) {
            throw SLObjPackException("SLObjPack unpack: Invalid value pointer " + jsString(ptr))
        }
        return values[i.toInt()]
    }

    fun readKey(): String {
        val key = resolvePointer(readStream())
        if (key !is String) throw SLObjPackException("SLObjPack unpack: Keys must be strings, got " + jsType(key))
        if (key in forbiddenKeys) throw SLObjPackException("SLObjPack unpack: Forbidden key: $key")
        return key
    }

    fun validateCount(n: Any?, max: Int, label: String): Int {
        val i = integer(n)
        if (i == null || i < 0 || i > max) throw SLObjPackException("SLObjPack unpack: Invalid $label count: " + jsString(n))
        return i.toInt()
    }

    fun requireStream(min: Long, label: String) {
        if (min > streamLen - cursor) throw SLObjPackException("SLObjPack unpack: $label exceeds remaining stream")
    }

    fun decode(depth: Int): Any? {
        if (depth > limits.depth) throw SLObjPackException("SLObjPack unpack: Max depth exceeded")
        val raw = readStream()
        val op = integer(raw) ?: throw SLObjPackException("SLObjPack unpack: Invalid opcode " + jsString(raw))
        if (op >= 0) return resolvePointer(raw)
        when (op) {
            -1L -> {
                val numKeys = validateCount(readStream(), limits.objectKeys, "object key")
                requireStream(numKeys * 2L, "object")
                val keys = Array(numKeys) { readKey() }
                val obj = LinkedHashMap<String, Any?>(numKeys * 2)
                for (key in keys) obj[key] = decode(depth + 1)
                return obj
            }
            -2L -> {
                val numItems = validateCount(readStream(), limits.arrayLength, "array item")
                requireStream(numItems.toLong(), "array")
                val arr = ArrayList<Any?>(numItems)
                repeat(numItems) { arr.add(decode(depth + 1)) }
                return arr
            }
            -3L -> {
                val numItems = validateCount(readStream(), limits.arrayLength, "schema array item")
                val numKeys = validateCount(readStream(), limits.objectKeys, "schema key")
                if (numItems.toLong() * numKeys > limits.decodeOps) {
                    throw SLObjPackException("SLObjPack unpack: Schema array decode budget exceeded")
                }
                requireStream(numKeys + numItems.toLong() * numKeys, "schema array")
                val keys = Array(numKeys) { readKey() }
                val arr = ArrayList<Any?>(numItems)
                repeat(numItems) {
                    val obj = LinkedHashMap<String, Any?>(numKeys * 2)
                    for (key in keys) obj[key] = decode(depth + 1)
                    arr.add(obj)
                }
                return arr
            }
            -4L -> return ArrayList<Any?>()
            -5L -> return arrayListOf(decode(depth + 1))
            -6L -> return LinkedHashMap<String, Any?>()
            else -> throw SLObjPackException("SLObjPack unpack: Unknown opcode " + jsString(raw))
        }
    }

    val result = decode(0)
    if (cursor != streamLen) throw SLObjPackException("SLObjPack unpack: Extra data after decoding")
    return result
}

/** JS `typeof` names, for error messages. */
private fun jsType(v: Any?): String = when (v) {
    null -> "object"
    is String -> "string"
    is Boolean -> "boolean"
    is Number -> "number"
    else -> "object"
}
