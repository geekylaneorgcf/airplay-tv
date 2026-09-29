package io.github.besliky.airplaytv

import io.github.besliky.airplaytv.core.NativeBridge

/**
 * Application logging with the same categories as the native receiver.
 *
 * Release builds only write warnings and errors to logcat unless verbose logging is
 * enabled in the advanced settings. Informational messages are always kept in the
 * native in-memory history so that they can be exported as diagnostics.
 */
object Log {
    enum class Category(val id: Int) {
        DISCOVERY(0), AIRPLAY(1), SESSION(2), NETWORK(3), VIDEO(4), AUDIO(5), DECODER(6), SERVICE(7), PAIRING(8)
    }

    private const val TAG = "AirPlayTV"

    @Volatile
    var verbose: Boolean = BuildConfig.DEBUG

    fun e(cat: Category, msg: String, t: Throwable? = null) = write(NativeBridge.LOG_ERROR, cat, msg, t)
    fun w(cat: Category, msg: String, t: Throwable? = null) = write(NativeBridge.LOG_WARN, cat, msg, t)
    fun i(cat: Category, msg: String) = write(NativeBridge.LOG_INFO, cat, msg, null)
    fun d(cat: Category, msg: String) {
        if (verbose) write(NativeBridge.LOG_DEBUG, cat, msg, null)
    }

    private fun write(level: Int, cat: Category, msg: String, t: Throwable?) {
        val text = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg
        val line = "[${cat.name}] $text"
        when (level) {
            NativeBridge.LOG_ERROR -> android.util.Log.e(TAG, line, t)
            NativeBridge.LOG_WARN -> android.util.Log.w(TAG, line)
            NativeBridge.LOG_INFO -> if (verbose) android.util.Log.i(TAG, line)
            else -> if (verbose) android.util.Log.d(TAG, line)
        }
        if (NativeBridge.loaded) {
            try {
                NativeBridge.nativeLog(level, cat.id, text)
            } catch (_: Throwable) {
            }
        }
    }
}
