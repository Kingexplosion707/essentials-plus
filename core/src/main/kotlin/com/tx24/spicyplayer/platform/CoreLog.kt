package com.tx24.spicyplayer.platform

/**
 * Debug logging for code that runs without Android. Silent until the app sets [sink]: the
 * Android app sends it to logcat. Messages are built only when there is a sink.
 */
object CoreLog {
    enum class Level { DEBUG, WARN }

    @Volatile
    var sink: ((level: Level, tag: String, message: String) -> Unit)? = null

    fun d(tag: String, message: () -> String) {
        sink?.invoke(Level.DEBUG, tag, message())
    }

    fun w(tag: String, message: () -> String) {
        sink?.invoke(Level.WARN, tag, message())
    }
}
