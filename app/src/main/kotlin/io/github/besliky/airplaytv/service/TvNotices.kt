package io.github.besliky.airplaytv.service

import android.content.Context
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.TvControl

/**
 * The short notices about what the stick does to the TV. They show over the stick's own screen (see [TvBanner]) while the key service
 * is on; the ones that matter when the TV shows something else (a sleep timer running out while a game is on) are also sent to the TV
 * as its own toast. Switched off with the TV Notices setting.
 */
object TvNotices {

    @Volatile
    private var banner: TvBanner? = null

    @Volatile
    private var app: Context? = null

    fun attach(context: Context, banner: TvBanner) {
        this.app = context.applicationContext
        this.banner = banner
    }

    fun detach() {
        banner?.remove()
        banner = null
    }

    /** Shows [text] now; [onTv] also puts it on the TV's own screen. Safe from any thread. */
    fun show(text: String, amber: Boolean = false, onTv: Boolean = false) {
        val context = app ?: return
        val settings = Settings(context)
        if (!settings.tvNotices) return
        TvOps.onMain { banner?.show(text, amber) }
        if (onTv) {
            TvOps.run("toast") {
                val tv = TvControl.open(settings) ?: return@run
                try {
                    tv.toast(text)
                } finally {
                    tv.close()
                }
            }
        }
    }
}
