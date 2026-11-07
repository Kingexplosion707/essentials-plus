// SLObjPack, the encoding of `lyrics.content` in Spicy Lyrics and Unison API responses.
//
// Values are plain trees: null, Boolean, Double (any Number on input), String,
// List (or Array) and Map with String keys. Decoded objects are LinkedHashMaps
// in coded order and arrays are ArrayLists.
//
// Version 2 packs and unpacks, and must stay bit-identical to the JS encoder
// (SLObjPackTest compares every packed byte of the golden documents). Version 1 is
// unpack only; servers that still need to produce it do so in JS.
package com.tx24.spicyplayer.network.data.slobjpack

import java.util.IdentityHashMap

const val PACK_V1 = 1
const val PACK_V2 = 2
const val LATEST_PACK_VERSION = PACK_V2

class SLObjPackException(message: String) : RuntimeException(message)

data class Limits(
    val depth: Int = 512,
    val arrayLength: Int = 1 shl 20, // ~1M items
    val objectKeys: Int = 1 shl 16, // 64K keys
    val streamLength: Int = 1 shl 24, // ~16M stream entries (v1) / payload chars (v2)
    val valuesLength: Int = 1 shl 22, // ~4M unique values
    val decodeOps: Int = 1 shl 22, // Combined budget for schema arrays (v1) / coded values (v2)
    // Literal string bytes (v2). Text is the slowest thing to decode, so keep this tight.
    val textBytes: Int = 1 shl 22, // 4 MiB
)

val DEFAULT_LIMITS = Limits()

// Keys refused in both directions, matching the JS (where they are prototype-pollution hazards).
val DEFAULT_FORBIDDEN_KEYS: Set<String> = setOf("__proto__", "constructor", "prototype")

class SLObjPack(
    val limits: Limits = DEFAULT_LIMITS,
    forbiddenKeys: Set<String> = DEFAULT_FORBIDDEN_KEYS,
) {
    private val forbiddenKeys: Set<String> = HashSet(forbiddenKeys)

    /** Packs [value] as `[2, "<base64>"]`. Only version 2 can be produced here. */
    fun pack(value: Any?, version: Int = PACK_V2): List<Any> {
        if (version != PACK_V2) {
            throw SLObjPackException("SLObjPack pack: Unsupported version $version")
        }
        return listOf(PACK_V2, packV2(snapshot(value, limits, forbiddenKeys), limits))
    }

    /**
     * Unpacks the string half of a v2 payload, for JSON libraries whose arrays
     * aren't Lists (org.json, kotlinx.serialization): `unpackV2(arr.getString(1))`.
     */
    fun unpackV2(base64: String): Any? = com.tx24.spicyplayer.network.data.slobjpack.unpackV2(base64, limits, forbiddenKeys)

    /** Unpacks a v1 or v2 payload, as parsed from JSON (`lyrics.content`) into Lists. */
    fun unpack(packed: Any?): Any? = when (val version = version(packed)) {
        PACK_V1 -> unpackV1(packed as List<*>, limits, forbiddenKeys)
        PACK_V2 -> unpackV2((packed as List<*>)[1], limits, forbiddenKeys)
        else -> throw SLObjPackException("SLObjPack unpack: Unsupported version $version")
    }

    companion object {
        /** The pack version of a payload (v1 carries no header), or throws if it isn't one. */
        @JvmStatic
        fun version(packed: Any?): Int {
            if (packed !is List<*> || packed.size != 2) {
                throw SLObjPackException("SLObjPack unpack: Invalid payload structure")
            }
            val first = packed[0]
            if (first is List<*>) return PACK_V1
            if (first is Number) {
                val d = first.toDouble()
                if (d.isFinite() && d == Math.floor(d) && d > PACK_V1) {
                    if (d > Int.MAX_VALUE) throw SLObjPackException("SLObjPack unpack: Unsupported version " + jsNumber(d))
                    return d.toInt()
                }
            }
            throw SLObjPackException("SLObjPack unpack: Invalid payload structure")
        }
    }
}

// -------- Validating snapshot --------
// Copies the input into the inert tree the encoder walks (Doubles, ArrayLists,
// LinkedHashMaps) and rejects what the JS rejects: cycles, non-finite numbers,
// unsupported types, oversized containers and forbidden keys.
fun snapshot(value: Any?, limits: Limits, forbiddenKeys: Set<String>): Any? {
    val seen = IdentityHashMap<Any, Unit>()

    fun walk(node: Any?, depth: Int): Any? {
        if (depth > limits.depth) throw SLObjPackException("SLObjPack pack: Max depth exceeded")
        when (node) {
            null, is String, is Boolean -> return node
            is Number -> {
                val d = node.toDouble()
                if (!d.isFinite()) throw SLObjPackException("SLObjPack pack: Non-finite number not supported")
                return d
            }
        }
        val items: List<*>? = when (node) {
            is List<*> -> node
            is Array<*> -> node.asList()
            else -> null
        }
        if (items == null && node !is Map<*, *>) {
            throw SLObjPackException("SLObjPack pack: Unsupported value type: " + node!!::class.java.name)
        }
        if (seen.put(node!!, Unit) != null) throw SLObjPackException("SLObjPack pack: Circular reference detected")
        try {
            if (items != null) {
                if (items.size > limits.arrayLength) throw SLObjPackException("SLObjPack pack: Array length exceeds limit")
                val out = ArrayList<Any?>(items.size)
                for (item in items) out.add(walk(item, depth + 1))
                return out
            }
            val map = node as Map<*, *>
            if (map.size > limits.objectKeys) throw SLObjPackException("SLObjPack pack: Object key count exceeds limit")
            val out = LinkedHashMap<String, Any?>(map.size * 2)
            for ((k, v) in map) {
                if (k !is String) throw SLObjPackException("SLObjPack pack: Object keys must be strings")
                if (k in forbiddenKeys) throw SLObjPackException("SLObjPack pack: Forbidden key: $k")
                out[k] = walk(v, depth + 1)
            }
            return out
        } finally {
            seen.remove(node)
        }
    }

    return walk(value, 0)
}

/** How JS prints a number in an error message, for the integral values that appear there. */
fun jsNumber(d: Double): String =
    if (d == Math.floor(d) && Math.abs(d) < 1e21) d.toLong().toString() else d.toString()

fun jsString(v: Any?): String = when (v) {
    null -> "null"
    is Number -> jsNumber(v.toDouble())
    else -> v.toString()
}
