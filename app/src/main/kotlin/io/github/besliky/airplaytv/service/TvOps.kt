package io.github.besliky.airplaytv.service

import android.os.Handler
import android.os.Looper
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import java.util.concurrent.Executors

/** The one thread every quick-panel, scene and timer call to the TV runs on (they wait for the TV, so never the main thread), and the way back to the main thread. */
object TvOps {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tv-ops").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun run(label: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(SERVICE, "TV $label failed", e)
            }
        }
    }

    fun onMain(block: () -> Unit) {
        main.post(block)
    }

    fun onMainDelayed(delayMs: Long, block: Runnable) {
        main.postDelayed(block, delayMs)
    }

    fun cancel(block: Runnable) {
        main.removeCallbacks(block)
    }
}
