// Comparisons against the JS reference, shared by the unit tests and the
// corpus/fuzz runs.
package com.tx24.spicyplayer.network.data.slobjpack

/** Problems found with one JS record: Kotlin must pack it to the same bytes and unpack both versions exactly. */
fun checkRecord(packer: SLObjPack, rec: Record): List<String> {
    val problems = ArrayList<String>()
    val packed = runCatching { packer.pack(rec.doc)[1] as String }
    packed.exceptionOrNull()?.let { problems.add("pack threw: $it") }
    packed.getOrNull()?.let { if (it != rec.v2) problems.add("v2 bytes differ from JS at char ${firstDiff(it, rec.v2)} (${it.length} vs ${rec.v2.length} chars)") }
    runCatching { packer.unpack(listOf(2.0, rec.v2)) }.fold(
        { back -> difference(rec.doc, back)?.let { problems.add("v2 unpack: $it") } },
        { problems.add("v2 unpack threw: $it") },
    )
    runCatching { packer.unpack(rec.v1) }.fold(
        { back -> difference(rec.doc, back, zeroSigned = false)?.let { problems.add("v1 unpack: $it") } },
        { problems.add("v1 unpack threw: $it") },
    )
    return problems
}

private fun firstDiff(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) if (a[i] != b[i]) return i
    return n
}

/** Unpacks one JS fuzz case; null if Kotlin did exactly what JS did. */
fun checkFuzzCase(packer: SLObjPack, reader: DumpReader): Pair<String, String>? {
    val payload = reader.string()
    val jsThrew = reader.u8() == 1
    val expected: Any? = if (jsThrew) reader.string() else reader.value()
    val result = try {
        Result.success(packer.unpack(listOf(2, payload)))
    } catch (error: Throwable) {
        Result.failure(error)
    }
    val error = result.exceptionOrNull()
    val problem = when {
        error != null && error !is SLObjPackException -> "Kotlin threw $error"
        jsThrew && result.isSuccess -> "JS threw \"$expected\", Kotlin returned a value"
        jsThrew && result.exceptionOrNull()!!.message != expected -> "JS threw \"$expected\", Kotlin threw \"${result.exceptionOrNull()!!.message}\""
        !jsThrew && result.isFailure -> "JS returned a value, Kotlin threw \"${result.exceptionOrNull()!!.message}\""
        !jsThrew -> difference(expected, result.getOrNull())?.let { "values differ: $it" }
        else -> null
    }
    return problem?.let { payload to it }
}
