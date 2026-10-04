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

    /** Tells the home screen's TV cell what happened to the TV ([title]) and how it stands now ([detail]); the home screen decides whether to show it. */
    fun cue(title: String, detail: String = "", warn: Boolean = false) {
        val context = app ?: return
        LauncherLink.cue(context, title, detail, warn)
    }

    /** Shows [text] now for [forMs]; [onTv] also puts it on the TV's own screen. Safe from any thread. */
    fun show(text: String, amber: Boolean = false, onTv: Boolean = false, forMs: Long = 0L) {
        val context = app ?: return
        val settings = Settings(context)
        if (!settings.tvNotices) return
        TvOps.onMain { if (forMs > 0L) banner?.show(text, amber, forMs) else banner?.show(text, amber) }
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
