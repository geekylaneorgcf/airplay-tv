package io.github.besliky.airplaytv.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.service.ArtworkColors
import io.github.besliky.airplaytv.service.DacpClient
import io.github.besliky.airplaytv.service.ReceiverState
import io.github.besliky.airplaytv.service.RemoteControl
import io.github.besliky.airplaytv.service.SleepService
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * What is playing while a sender streams audio: cover, title, artist and album, a progress bar with
 * elapsed and remaining time, transport buttons and a volume bar, laid out like the iPhone's player.
 * It opens by itself when audio starts and closes when the session ends.
 *
 * - The remote controls the sender: play/pause or OK, next or right, previous or left. Down opens the
 *   lyrics when they are switched on, Up or Back closes them. Back otherwise closes the screen and the
 *   audio keeps playing.
 * - Tracks change with a slide and fade in the direction of travel, the cover shrinks while paused, and
 *   the backdrop takes a dark tint from the cover.
 * - To protect OLED panels the whole layout drifts slowly, dims when nobody touches the remote, turns
 *   into a small drifting card on black, and finally goes fully dark while paused.
 */
class NowPlayingActivity : Activity() {

    private enum class Presence { ACTIVE, DIM, AMBIENT, BLANK, GOODNIGHT }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var goodnightEnd = 0L
    private var latest = ReceiverState.Snapshot()
    private var rendered = false
    private var lastTrackSeq = 0
    private var lastArtworkSeq = 0
    private var lastArtworkAt = 0L
    private var lastVolumeAt = 0L
    private var lastLyricsKey: Any? = null
    private var previousAlbum = ""
    private var shownPlaying = true
    private var lyricsOpen = false
    private var preview: String? = null

    private var presence = Presence.ACTIVE
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var pausedSince = 0L
    private val startedAt = SystemClock.elapsedRealtime()

    // ---- views ----
    private lateinit var backdrop: BackdropView
    private lateinit var orbitLayer: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var artHolder: FrameLayout
    private lateinit var artA: ImageView
    private lateinit var artB: ImageView
    private lateinit var artFront: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var progressBar: PillBar
    private lateinit var backwardButton: ImageView
    private lateinit var playPauseButton: FrameLayout
    private lateinit var pauseIcon: ImageView
    private lateinit var playIcon: ImageView
    private lateinit var forwardButton: ImageView
    private lateinit var speakerLow: ImageView
    private lateinit var speakerHigh: ImageView
    private lateinit var volumeBar: PillBar
    private lateinit var hintView: TextView
    private lateinit var lyricsPage: FrameLayout
    private lateinit var lyricsArt: ImageView
    private lateinit var lyricsTitle: TextView
    private lateinit var lyricsArtist: TextView
    private lateinit var lyricsView: LyricsView
    private lateinit var lyricsStatus: TextView
    private lateinit var lyricsElapsed: TextView
    private lateinit var lyricsRemaining: TextView
    private lateinit var lyricsProgress: PillBar
    private lateinit var lyricsVolume: LinearLayout
    private lateinit var lyricsVolumeBar: PillBar
    private lateinit var ambient: LinearLayout
    private lateinit var ambientArt: ImageView
    private lateinit var ambientTitle: TextView
    private lateinit var ambientArtist: TextView
    private lateinit var goodnightView: TextView

    // ---- colours ----
    private var shownTop = NEUTRAL_TOP
    private var shownBottom = NEUTRAL_BOTTOM
    private var shownAccent = ArtworkColors.NEUTRAL.accent
    private var paletteAnimator: ValueAnimator? = null
    private var artworkColors: ArtworkColors = ArtworkColors.NEUTRAL

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val presenceTick = object : Runnable {
        override fun run() {
            evaluatePresence()
            handler.postDelayed(this, PRESENCE_TICK_MS)
        }
    }

    private val orbitTick = object : Runnable {
        override fun run() {
            orbit()
            handler.postDelayed(this, ORBIT_STEP_MS)
        }
    }

    private val driftTick = object : Runnable {
        override fun run() {
            drift()
            handler.postDelayed(this, DRIFT_STEP_MS)
        }
    }

    private val settleVolume = Runnable { volumeBar.setActive(false) }
    private val hideLyricsVolume = Runnable { lyricsVolume.animate().alpha(0f).setDuration(300).start() }
    private val hideHint = Runnable { hintView.animate().alpha(0f).setDuration(300).start() }
    private val clearArtwork = Runnable {
        showArtwork(null, latest.trackDirection, animate = true)
        applyArtworkColors(ArtworkColors.NEUTRAL, animate = true)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A sender can connect while the device sleeps: turn the screen on for it.
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            )
        }

        settings = Settings(this)
        preview = intent.getStringExtra(EXTRA_PREVIEW)
        preview?.let { PreviewMode.begin(it) }

        setContentView(buildContent())
        lastVolumeAt = ReceiverState.current.volumeAtMs
    }

    override fun onStart() {
        super.onStart()
        ReceiverState.observe(stateListener)
        handler.post(tick)
        handler.postDelayed(presenceTick, PRESENCE_TICK_MS)
        handler.postDelayed(orbitTick, ORBIT_STEP_MS)
        handler.postDelayed(driftTick, 1000)
        noteInteraction()
        preview?.let { applyPreviewMode(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val mode = intent.getStringExtra(EXTRA_PREVIEW) ?: return
        preview = mode
        PreviewMode.begin(mode)
        lastVolumeAt = ReceiverState.current.volumeAtMs
        setPresence(Presence.ACTIVE)
        closeLyrics()
        applyPreviewMode(mode)
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        if (preview != null) PreviewMode.end()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun label(sizeSp: Float, color: Int, face: Typeface = Typeface.DEFAULT, lines: Int = 1): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = face
            maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private fun icon(res: Int, padDp: Int, tint: Int? = null): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
    }

    private fun roundOutline(radiusDp: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dpf(radiusDp.toFloat()))
        }
    }

    private fun buildContent(): View {
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val light = Typeface.create("sans-serif", Typeface.NORMAL)

        backdrop = BackdropView(this)
        val root = FrameLayout(this)
        root.clipChildren = false
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- artwork: two layers so one cover can slide out while the next slides in
        artA = coverView()
        artB = coverView()
        artB.alpha = 0f
        artFront = artA
        artHolder = FrameLayout(this).apply {
            outlineProvider = roundOutline(ART_RADIUS_DP)
            clipToOutline = true
            elevation = dpf(28f)
            addView(artA, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(artB, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // ---- text
        titleView = label(36f, Color.WHITE, medium, lines = 2)
        artistView = label(24f, 0xE6FFFFFF.toInt(), light)
        albumView = label(19f, SECONDARY, light)

        // ---- progress row: elapsed, bar, remaining
        elapsedView = timeLabel(Gravity.START)
        remainingView = timeLabel(Gravity.END)
        progressBar = PillBar(this)
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsedView, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(0, dp(16), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            addView(remainingView, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- transport row
        backwardButton = icon(R.drawable.ic_ctrl_backward, 11)
        pauseIcon = icon(R.drawable.ic_ctrl_pause, 22)
        playIcon = icon(R.drawable.ic_ctrl_play, 22).apply { alpha = 0f }
        playPauseButton = FrameLayout(this).apply {
            addView(pauseIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(playIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        forwardButton = icon(R.drawable.ic_ctrl_forward, 11)
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(backwardButton, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(playPauseButton, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                marginStart = dp(26)
                marginEnd = dp(26)
            })
            addView(forwardButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        }

        // ---- volume row: quiet speaker, bar, loud speaker
        speakerLow = icon(R.drawable.ic_speaker_low, 0, SECONDARY)
        speakerHigh = icon(R.drawable.ic_speaker_high, 0, SECONDARY)
        volumeBar = PillBar(this)
        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false // the loud speaker swells on every change and sits at the row's right edge
            addView(speakerLow, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(volumeBar, LinearLayout.LayoutParams(0, dp(16), 1f).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            })
            addView(speakerHigh, LinearLayout.LayoutParams(dp(26), dp(26)))
        }

        hintView = label(15f, SECONDARY, light).apply { alpha = 0f }

        // The text sits at the top and the controls at the bottom of a column as tall as the cover, so a
        // title that wraps to two lines never moves the controls.
        val textGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView, wide())
            addView(artistView, wide(top = 8))
            addView(albumView, wide(top = 4))
        }
        val controlsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(progressRow, wide())
            addView(transport, wide(top = 14))
            addView(volumeRow, wide(top = 12))
            addView(hintView, wide(top = 14))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(textGroup, wide())
            addView(View(this@NowPlayingActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(controlsGroup, wide())
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(72), dp(40), dp(72), dp(40))
            addView(artHolder, LinearLayout.LayoutParams(dp(ART_DP), dp(ART_DP)))
            addView(column, LinearLayout.LayoutParams(0, dp(ART_DP), 1f).apply {
                marginStart = dp(60)
            })
        }

        // ---- lyrics page
        lyricsArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ART_PLACEHOLDER)
            outlineProvider = roundOutline(10)
            clipToOutline = true
        }
        lyricsTitle = label(22f, Color.WHITE, medium)
        lyricsArtist = label(17f, SECONDARY, light)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lyricsArt, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(LinearLayout(this@NowPlayingActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(lyricsTitle, wide())
                addView(lyricsArtist, wide(top = 4))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(18) })
        }
        lyricsView = LyricsView(this)
        lyricsStatus = label(22f, SECONDARY, light).apply { gravity = Gravity.CENTER }
        lyricsElapsed = timeLabel(Gravity.START)
        lyricsRemaining = timeLabel(Gravity.END)
        lyricsProgress = PillBar(this)
        val lyricsProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lyricsElapsed, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(lyricsProgress, LinearLayout.LayoutParams(0, dp(16), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            addView(lyricsRemaining, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        lyricsVolumeBar = PillBar(this)
        lyricsVolume = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            setPadding(dp(22), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                setColor(0xE62C2C30.toInt())
                cornerRadius = dpf(28f)
            }
            addView(icon(R.drawable.ic_speaker_low, 0, SECONDARY), LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(lyricsVolumeBar, LinearLayout.LayoutParams(dp(240), dp(16)).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            })
            addView(icon(R.drawable.ic_speaker_high, 0, SECONDARY), LinearLayout.LayoutParams(dp(26), dp(26)))
        }
        lyricsPage = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP).apply {
                setMargins(dp(72), dp(40), dp(72), 0)
            })
            addView(lyricsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(72), dp(140), dp(72), dp(96))
            })
            addView(lyricsStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
            addView(lyricsProgressRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM).apply {
                setMargins(dp(72), 0, dp(72), dp(40))
            })
            addView(lyricsVolume, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(100)
            })
        }

        content.clipChildren = false
        content.clipToPadding = false
        stage = FrameLayout(this).apply {
            clipChildren = false
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lyricsPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // ---- ambient card: a small card that drifts across black while nobody is looking
        ambientArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ART_PLACEHOLDER)
            outlineProvider = roundOutline(10)
            clipToOutline = true
        }
        ambientTitle = label(18f, Color.WHITE, medium)
        ambientArtist = label(15f, SECONDARY, light)
        ambient = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            visibility = View.GONE
            addView(ambientArt, LinearLayout.LayoutParams(dp(AMBIENT_ART_DP), dp(AMBIENT_ART_DP)))
            addView(LinearLayout(this@NowPlayingActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ambientTitle, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ambientArtist, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
            })
        }

        // The orbit layer only ever moves and the stage only ever fades, so their animations never fight.
        orbitLayer = FrameLayout(this).apply {
            clipChildren = false
            addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(orbitLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ambient, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        goodnightView = label(22f, 0xFF8E8E93.toInt(), light).apply {
            alpha = 0f
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        root.addView(goodnightView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        applyPalette()
        return root
    }

    private fun coverView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(ART_PLACEHOLDER)
    }

    private fun timeLabel(gravityFlag: Int) = label(17f, SECONDARY, Typeface.create("sans-serif", Typeface.NORMAL)).apply {
        gravity = gravityFlag or Gravity.CENTER_VERTICAL
        fontFeatureSettings = "tnum"
    }

    private fun wide(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    // ---------------------------------------------------------------- rendering

    private fun displayTitle(s: ReceiverState.Snapshot): String =
        if (s.title.isNotBlank()) s.title else getString(R.string.now_playing_from, s.clientName ?: getString(R.string.now_playing_device))

    private fun render(s: ReceiverState.Snapshot) {
        val previous = latest
        latest = s
        if (s.videoActive) {
            finish()
            return
        }
        if (s.status != ReceiverState.Status.CONNECTED) {
            if (presence == Presence.GOODNIGHT) return // already counting down to sleep
            // The music is over. If AirPlay woke the TV and nobody used the remote, keep a black screen
            // for a while and then put it back to sleep; otherwise just close.
            if (s.wokeDevice && settings.sleepAfterMinutes > 0 && SleepService.isEnabled) {
                startGoodnight(settings.sleepAfterMinutes * 60_000L)
            } else {
                finish()
            }
            return
        }
        if (presence == Presence.GOODNIGHT) setPresence(Presence.ACTIVE) // a new session came back
        val first = !rendered
        rendered = true

        if (first) {
            lastTrackSeq = s.trackSeq
            lastArtworkSeq = s.artworkSeq
            setTextNow(titleView, displayTitle(s))
            setTextNow(artistView, s.artist)
            setTextNow(albumView, s.album)
            artistView.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
            albumView.visibility = if (s.album.isBlank()) View.GONE else View.VISIBLE
            previousAlbum = s.album
            pausedSince = if (s.playing) 0L else SystemClock.elapsedRealtime()
            showArtwork(s.artwork, 1, animate = false)
            artworkColors = s.artColors ?: ArtworkColors.NEUTRAL
            applyArtworkColors(artworkColors, animate = false)
            shownPlaying = s.playing
            applyPlaying(s.playing, animate = false)
            val level = if (s.volume >= 0f) s.volume else 1f
            volumeBar.setFraction(level, false)
            updateSpeakers(level)
            renderAmbient(s)
            renderLyricsHeader(s)
        } else {
            if (s.trackSeq != lastTrackSeq) {
                lastTrackSeq = s.trackSeq
                onTrackChanged(s)
            } else if (s.title != previous.title || s.artist != previous.artist || s.album != previous.album) {
                setTextNow(titleView, displayTitle(s))
                setTextNow(artistView, s.artist)
                setTextNow(albumView, s.album)
                artistView.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
                albumView.visibility = if (s.album.isBlank()) View.GONE else View.VISIBLE
                renderAmbient(s)
                renderLyricsHeader(s)
            }
            if (s.artworkSeq != lastArtworkSeq) {
                lastArtworkSeq = s.artworkSeq
                onArtworkArrived(s)
            }
            if (s.playing != shownPlaying) {
                shownPlaying = s.playing
                applyPlaying(s.playing, animate = true)
                pausedSince = if (s.playing) 0L else SystemClock.elapsedRealtime()
                if (s.playing && presence == Presence.BLANK) setPresence(Presence.AMBIENT)
            }
        }

        if (s.volumeAtMs != lastVolumeAt) {
            lastVolumeAt = s.volumeAtMs
            if (s.volume >= 0f) onVolumeChanged(s.volume)
        }

        val key = Pair(s.lyricsState, s.lyrics)
        if (key != lastLyricsKey) {
            lastLyricsKey = key
            onLyricsChanged(s)
        }
        updateProgress()
    }

    // ---- track changes

    private fun onTrackChanged(s: ReceiverState.Snapshot) {
        val dir = s.trackDirection
        swapText(titleView, displayTitle(s), dir, 0)
        swapText(artistView, s.artist, dir, 50)
        swapText(albumView, s.album, dir, 100)
        artistView.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
        albumView.visibility = if (s.album.isBlank()) View.GONE else View.VISIBLE
        progressBar.setFraction(0f, false)
        lyricsProgress.setFraction(0f, false)
        renderAmbient(s)
        renderLyricsHeader(s)
        if (presence == Presence.DIM) setPresence(Presence.ACTIVE)

        // The cover normally arrives within a moment of the track info; if it does not, the new track
        // has none and the old one has to go. A cover that arrived just before the info is kept.
        handler.removeCallbacks(clearArtwork)
        val sameAlbum = s.album.isNotBlank() && s.album == previousAlbum
        if (!sameAlbum && SystemClock.elapsedRealtime() - lastArtworkAt > ARTWORK_GRACE_MS) {
            handler.postDelayed(clearArtwork, ARTWORK_WAIT_MS)
        }
        previousAlbum = s.album
    }

    private fun onArtworkArrived(s: ReceiverState.Snapshot) {
        handler.removeCallbacks(clearArtwork)
        lastArtworkAt = SystemClock.elapsedRealtime()
        showArtwork(s.artwork, s.trackDirection, animate = true)
        artworkColors = s.artColors ?: ArtworkColors.NEUTRAL
        applyArtworkColors(artworkColors, animate = true)
        renderAmbient(s)
    }

    /** Sets [text] at once and drops any slide that is still running. */
    private fun setTextNow(view: TextView, text: String) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.tag = text
        view.text = text
    }

    /** Slides the old text out and the new text in, against the direction of travel. */
    private fun swapText(view: TextView, text: String, direction: Int, delayMs: Long) {
        if (view.tag == text) return
        view.tag = text
        val shift = dpf(TEXT_SHIFT_DP) * direction
        view.animate().cancel()
        view.animate().setStartDelay(0).alpha(0f).translationX(-shift).setDuration(TEXT_OUT_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                view.text = text
                view.translationX = shift
                view.animate().setStartDelay(delayMs).alpha(1f).translationX(0f).setDuration(TEXT_IN_MS)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }.start()
    }

    /** Cross-slides to a new cover; the direction follows next/previous. */
    private fun showArtwork(bitmap: Bitmap?, direction: Int, animate: Boolean) {
        val outgoing = artFront
        val incoming = if (outgoing === artA) artB else artA
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bitmap)
        lyricsArt.setImageBitmap(bitmap)
        ambientArt.setImageBitmap(bitmap)
        incoming.bringToFront()
        artFront = incoming
        if (!animate) {
            incoming.alpha = 1f
            incoming.translationX = 0f
            incoming.scaleX = 1f
            incoming.scaleY = 1f
            outgoing.alpha = 0f
            return
        }
        val shift = dpf(ART_SHIFT_DP) * direction
        incoming.alpha = 0f
        incoming.translationX = shift
        incoming.scaleX = 0.92f
        incoming.scaleY = 0.92f
        incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(ART_IN_MS)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
        outgoing.animate().alpha(0f).translationX(-shift * 0.6f).scaleX(0.92f).scaleY(0.92f).setDuration(ART_OUT_MS).start()
    }

    // ---- colours

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * a).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * a).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a).toInt(),
        )
    }

    private fun applyPalette() {
        backdrop.setColors(shownTop, shownBottom)
        val fill = blend(shownAccent, Color.WHITE, 0.45f)
        val track = blend(0xFF2E2E32.toInt(), shownAccent, 0.14f)
        for (bar in arrayOf(progressBar, volumeBar, lyricsProgress, lyricsVolumeBar)) {
            bar.fillColor = fill
            bar.trackColor = track
        }
    }

    /** Moves the backdrop and the bars to [colors]; on black (ambient/blank) the backdrop stays pure black. */
    private fun applyArtworkColors(colors: ArtworkColors, animate: Boolean) {
        artworkColors = colors
        val dark = presence == Presence.AMBIENT || presence == Presence.BLANK || presence == Presence.GOODNIGHT
        animatePaletteTo(
            if (dark) Color.BLACK else colors.backdropTop,
            if (dark) Color.BLACK else colors.backdropBottom,
            colors.accent,
            animate,
        )
    }

    private fun animatePaletteTo(top: Int, bottom: Int, accent: Int, animate: Boolean) {
        paletteAnimator?.cancel()
        val fromTop = shownTop
        val fromBottom = shownBottom
        val fromAccent = shownAccent
        if (!animate) {
            shownTop = top
            shownBottom = bottom
            shownAccent = accent
            applyPalette()
            return
        }
        val evaluator = ArgbEvaluator()
        paletteAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PALETTE_MS
            addUpdateListener {
                val p = it.animatedValue as Float
                shownTop = evaluator.evaluate(p, fromTop, top) as Int
                shownBottom = evaluator.evaluate(p, fromBottom, bottom) as Int
                shownAccent = evaluator.evaluate(p, fromAccent, accent) as Int
                applyPalette()
            }
            start()
        }
    }

    // ---- play / pause, progress, volume

    private fun applyPlaying(playing: Boolean, animate: Boolean) {
        val scale = if (playing) 1f else PAUSED_ART_SCALE
        // Both icons stay in place and cross-fade, so a key-press pulse on the holder never fights this.
        val shown = if (playing) pauseIcon else playIcon
        val hidden = if (playing) playIcon else pauseIcon
        if (!animate) {
            shown.alpha = 1f
            hidden.alpha = 0f
            artHolder.scaleX = scale
            artHolder.scaleY = scale
            return
        }
        shown.animate().cancel()
        hidden.animate().cancel()
        shown.scaleX = 0.82f
        shown.scaleY = 0.82f
        shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        hidden.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        artHolder.animate().cancel()
        artHolder.animate().scaleX(scale).scaleY(scale)
            .setDuration(if (playing) 420 else 300)
            .setInterpolator(if (playing) OvershootInterpolator(1.5f) else DecelerateInterpolator(1.4f))
            .start()
    }

    private fun positionNow(): Long {
        val s = latest
        if (s.durationMs <= 0 || s.positionMs < 0) return -1
        var position = s.positionMs
        if (s.audioActive && s.playing) position += SystemClock.elapsedRealtime() - s.positionAtMs
        return position.coerceIn(0L, s.durationMs)
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
        } else {
            String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        }
    }

    private fun updateProgress() {
        val s = latest
        val position = positionNow()
        if (position < 0) {
            elapsedView.text = ""
            remainingView.text = ""
            lyricsElapsed.text = ""
            lyricsRemaining.text = ""
            progressBar.setFraction(0f, false)
            lyricsProgress.setFraction(0f, false)
            return
        }
        val fraction = position.toFloat() / s.durationMs
        val elapsed = formatTime(position)
        val remaining = MINUS + formatTime(s.durationMs - position)
        progressBar.setFraction(fraction, false)
        elapsedView.text = elapsed
        remainingView.text = remaining
        if (lyricsOpen) {
            lyricsProgress.setFraction(fraction, false)
            lyricsElapsed.text = elapsed
            lyricsRemaining.text = remaining
            lyricsView.setPosition(position + LYRICS_LEAD_MS)
            lyricsView.setProgress(fraction)
        }
    }

    private fun updateSpeakers(level: Float) {
        speakerLow.setImageResource(if (level <= 0f) R.drawable.ic_speaker_muted else R.drawable.ic_speaker_low)
    }

    private fun onVolumeChanged(level: Float) {
        wakeUp()
        volumeBar.setFraction(level, true)
        volumeBar.setActive(true)
        updateSpeakers(level)
        speakerHigh.animate().cancel()
        speakerHigh.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90).withEndAction {
            speakerHigh.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
        handler.removeCallbacks(settleVolume)
        handler.postDelayed(settleVolume, VOLUME_SETTLE_MS)
        if (lyricsOpen) {
            lyricsVolumeBar.setFraction(level, true)
            lyricsVolume.animate().cancel()
            lyricsVolume.animate().alpha(1f).setDuration(160).start()
            handler.removeCallbacks(hideLyricsVolume)
            handler.postDelayed(hideLyricsVolume, VOLUME_SETTLE_MS + 400)
        }
    }

    // ---------------------------------------------------------------- lyrics

    private fun renderLyricsHeader(s: ReceiverState.Snapshot) {
        lyricsTitle.text = displayTitle(s)
        lyricsArtist.text = s.artist
    }

    private fun onLyricsChanged(s: ReceiverState.Snapshot) {
        lyricsView.setLyrics(s.lyrics)
        val status = when (s.lyricsState) {
            ReceiverState.LyricsState.OFF -> getString(R.string.lyrics_off)
            ReceiverState.LyricsState.LOADING -> getString(R.string.lyrics_loading)
            ReceiverState.LyricsState.NOT_FOUND -> getString(R.string.lyrics_not_found)
            ReceiverState.LyricsState.UNAVAILABLE -> getString(R.string.lyrics_unavailable)
            ReceiverState.LyricsState.FOUND -> if (s.lyrics?.instrumental == true) getString(R.string.lyrics_instrumental) else ""
        }
        lyricsStatus.text = status
        lyricsStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        val available = s.lyricsState == ReceiverState.LyricsState.FOUND && s.lyrics?.instrumental != true
        if (available && !lyricsOpen) showHint(getString(R.string.lyrics_hint), HINT_MS) else if (!available) hintView.alpha = 0f
    }

    private fun showHint(text: String, durationMs: Long) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.animate().alpha(0.75f).setDuration(250).start()
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, durationMs)
    }

    private fun openLyrics() {
        if (latest.lyricsState == ReceiverState.LyricsState.OFF) {
            showHint(getString(R.string.lyrics_turn_on), 4000)
            return
        }
        if (lyricsOpen) return
        lyricsOpen = true
        lyricsPage.animate().cancel()
        content.animate().cancel()
        lyricsPage.visibility = View.VISIBLE
        lyricsPage.animate().alpha(1f).setDuration(320).start()
        content.animate().alpha(0f).setDuration(260).start()
        hintView.alpha = 0f
        updateProgress()
    }

    private fun closeLyrics() {
        if (!lyricsOpen) return
        lyricsOpen = false
        lyricsPage.animate().cancel()
        content.animate().cancel()
        content.animate().alpha(1f).setDuration(320).start()
        lyricsPage.animate().alpha(0f).setDuration(260).withEndAction { lyricsPage.visibility = View.GONE }.start()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (presence == Presence.GOODNIGHT) {
            // Someone is here after all: stay awake and go back to the home screen.
            ReceiverState.update { it.copy(wokeDevice = false) }
            finish()
            return true
        }
        if (latest.wokeDevice) ReceiverState.update { it.copy(wokeDevice = false) }
        val wasDark = presence == Presence.AMBIENT || presence == Presence.BLANK
        noteInteraction()
        if (wasDark && keyCode != KeyEvent.KEYCODE_BACK) return true // the first press only wakes the screen
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                openLyrics()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                closeLyrics()
                return true
            }
        }
        val command = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> DacpClient.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT -> DacpClient.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT -> DacpClient.PREVIOUS
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            RemoteControl.send(command)
            pulse(
                when (command) {
                    DacpClient.NEXT -> forwardButton
                    DacpClient.PREVIOUS -> backwardButton
                    else -> playPauseButton
                },
            )
        }
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (lyricsOpen) closeLyrics() else super.onBackPressed()
    }

    /** A short pulse acknowledges a key press before the sender has answered. */
    private fun pulse(view: View) {
        view.animate().cancel()
        view.animate().scaleX(1.22f).scaleY(1.22f).setDuration(110).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.5f)).start()
        }.start()
    }

    // ---------------------------------------------------------------- OLED care

    private fun noteInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
        if (presence != Presence.ACTIVE) setPresence(Presence.ACTIVE)
    }

    /** Brings the screen back to full brightness for something the viewer should see. */
    private fun wakeUp() {
        lastInteraction = SystemClock.elapsedRealtime()
        if (presence != Presence.ACTIVE) setPresence(Presence.ACTIVE)
    }

    private fun evaluatePresence() {
        if (presence == Presence.GOODNIGHT) return
        if (preview != null && presence != Presence.ACTIVE && SystemClock.elapsedRealtime() - startedAt < PREVIEW_HOLD_MS) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastInteraction
        val pausedFor = if (!latest.playing && pausedSince > 0) now - pausedSince else 0L
        val target = when {
            pausedFor >= BLANK_AFTER_PAUSED_MS && idle >= AMBIENT_AFTER_MS -> Presence.BLANK
            idle >= AMBIENT_AFTER_MS -> if (presence == Presence.BLANK) Presence.BLANK else Presence.AMBIENT
            idle >= DIM_AFTER_MS -> Presence.DIM
            else -> Presence.ACTIVE
        }
        if (target != presence) setPresence(target)
    }

    private fun setPresence(next: Presence) {
        if (next == presence) return
        val leavingDark = (presence == Presence.AMBIENT || presence == Presence.BLANK || presence == Presence.GOODNIGHT) &&
            (next == Presence.ACTIVE || next == Presence.DIM)
        presence = next
        if (next != Presence.GOODNIGHT) hideGoodnight()
        when (next) {
            Presence.ACTIVE -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stage.animate().cancel()
                stage.animate().alpha(1f).setDuration(if (leavingDark) 900 else 300).start()
                hideAmbient()
            }
            Presence.DIM -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stage.animate().cancel()
                stage.animate().alpha(DIM_ALPHA).setDuration(1500).start()
                hideAmbient()
            }
            Presence.AMBIENT -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stage.animate().cancel()
                stage.animate().alpha(0f).setDuration(1800).start()
                showAmbient()
            }
            Presence.BLANK -> {
                // Pure black, and let the system sleep the screen by its own timer.
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stage.animate().cancel()
                stage.animate().alpha(0f).setDuration(1200).start()
                hideAmbient()
            }
            Presence.GOODNIGHT -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stage.animate().cancel()
                stage.animate().alpha(0f).setDuration(1500).start()
                hideAmbient()
                goodnightView.visibility = View.VISIBLE
                goodnightView.animate().alpha(0.55f).setDuration(1500).start()
            }
        }
        applyArtworkColors(artworkColors, animate = true)
    }

    private fun renderAmbient(s: ReceiverState.Snapshot) {
        ambientTitle.text = displayTitle(s)
        ambientArtist.text = s.artist
        ambientArtist.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showAmbient() {
        ambient.visibility = View.VISIBLE
        ambient.animate().cancel()
        ambient.animate().alpha(AMBIENT_ALPHA).setDuration(1800).start()
        placeAmbient(animate = false)
    }

    private fun hideAmbient() {
        ambient.animate().cancel()
        ambient.animate().alpha(0f).setDuration(600).withEndAction {
            if (presence != Presence.AMBIENT) ambient.visibility = View.GONE
        }.start()
    }

    /** Where the ambient card is [aheadMs] from now: a slow loop that covers the whole screen. */
    private fun ambientTarget(aheadMs: Long): Pair<Float, Float> {
        val t = (SystemClock.elapsedRealtime() + aheadMs) / 60000.0
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ambient.measure(unspecified, unspecified)
        val maxX = (ambient.rootView.width - ambient.measuredWidth - dp(80)).coerceAtLeast(0)
        val maxY = (ambient.rootView.height - ambient.measuredHeight - dp(80)).coerceAtLeast(0)
        val x = dp(40) + maxX * (sin(2 * PI * t / AMBIENT_PERIOD_X_MIN) + 1) / 2
        val y = dp(40) + maxY * (sin(2 * PI * t / AMBIENT_PERIOD_Y_MIN + 1.3) + 1) / 2
        return Pair(x.toFloat(), y.toFloat())
    }

    private fun placeAmbient(animate: Boolean) {
        ambient.post {
            val (x, y) = ambientTarget(if (animate) DRIFT_STEP_MS else 0L)
            if (animate) {
                ambient.animate().translationX(x).translationY(y).setDuration(DRIFT_STEP_MS).setInterpolator(LinearInterpolator()).start()
            } else {
                ambient.translationX = x
                ambient.translationY = y
            }
        }
    }

    private fun drift() {
        if (presence == Presence.AMBIENT) placeAmbient(animate = true)
    }

    /** Moves the whole layout a few pixels along a very slow loop, so no pixel stays lit for hours. */
    private fun orbit() {
        val t = (SystemClock.elapsedRealtime() + ORBIT_STEP_MS) / 60000.0
        val x = dpf(ORBIT_X_DP) * sin(2 * PI * t / ORBIT_PERIOD_X_MIN).toFloat()
        val y = dpf(ORBIT_Y_DP) * sin(2 * PI * t / ORBIT_PERIOD_Y_MIN + 0.7).toFloat()
        orbitLayer.animate().translationX(x).translationY(y).setDuration(ORBIT_STEP_MS).setInterpolator(LinearInterpolator()).start()
    }

    // ---------------------------------------------------------------- sleep after music

    private val goodnightTick = object : Runnable {
        override fun run() {
            val left = goodnightEnd - SystemClock.elapsedRealtime()
            if (left <= 0) {
                finishGoodnight()
                return
            }
            goodnightView.text = getString(R.string.goodnight_countdown, formatTime(left + 999))
            handler.postDelayed(this, 1000)
        }
    }

    private fun startGoodnight(durationMs: Long) {
        goodnightEnd = SystemClock.elapsedRealtime() + durationMs
        setPresence(Presence.GOODNIGHT)
        handler.removeCallbacks(goodnightTick)
        handler.post(goodnightTick)
    }

    private fun hideGoodnight() {
        handler.removeCallbacks(goodnightTick)
        goodnightView.animate().cancel()
        goodnightView.animate().alpha(0f).setDuration(300).withEndAction {
            if (presence != Presence.GOODNIGHT) goodnightView.visibility = View.GONE
        }.start()
    }

    /** The time is up: turn the screen off, unless something else is playing music. */
    private fun finishGoodnight() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (preview == null && !am.isMusicActive) SleepService.sleepNow()
        finish()
    }

    // ---------------------------------------------------------------- preview (development)

    private fun applyPreviewMode(mode: String) {
        when (mode) {
            "ambient" -> setPresence(Presence.AMBIENT)
            "dim" -> setPresence(Presence.DIM)
            "blank" -> setPresence(Presence.BLANK)
            "goodnight" -> startGoodnight(5 * 60_000L)
            "lyrics" -> handler.postDelayed({ openLyrics() }, 700)
            "lyricsnet" -> handler.postDelayed({ openLyrics() }, 4500)
            "nolyrics", "lyricsloading", "lyricsdown" -> handler.postDelayed({ openLyrics() }, 700)
        }
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"

        private const val NEUTRAL_TOP = 0xFF17171A.toInt()
        private const val NEUTRAL_BOTTOM = 0xFF0B0B0D.toInt()
        private const val SECONDARY = 0xFF8E8E93.toInt()
        private const val ART_PLACEHOLDER = 0xFF2C2C30.toInt()
        private const val MINUS = "−"

        private const val ART_DP = 380
        private const val ART_RADIUS_DP = 18
        private const val AMBIENT_ART_DP = 88
        private const val PAUSED_ART_SCALE = 0.9f

        private const val TICK_MS = 250L
        private const val PRESENCE_TICK_MS = 5000L
        private const val PREVIEW_HOLD_MS = 120_000L
        private const val LYRICS_LEAD_MS = 150L
        private const val VOLUME_SETTLE_MS = 1400L
        private const val HINT_MS = 7000L

        private const val TEXT_SHIFT_DP = 28f
        private const val TEXT_OUT_MS = 130L
        private const val TEXT_IN_MS = 300L
        private const val ART_SHIFT_DP = 56f
        private const val ART_IN_MS = 460L
        private const val ART_OUT_MS = 380L
        private const val PALETTE_MS = 700L
        private const val ARTWORK_WAIT_MS = 1500L
        private const val ARTWORK_GRACE_MS = 1200L

        // Burn-in care: when each stage starts, and how the layout drifts.
        private const val DIM_AFTER_MS = 90_000L
        private const val AMBIENT_AFTER_MS = 5 * 60_000L
        private const val BLANK_AFTER_PAUSED_MS = 15 * 60_000L
        private const val DIM_ALPHA = 0.45f
        private const val AMBIENT_ALPHA = 0.6f
        private const val ORBIT_STEP_MS = 10_000L
        private const val ORBIT_X_DP = 22f
        private const val ORBIT_Y_DP = 14f
        private const val ORBIT_PERIOD_X_MIN = 11.0
        private const val ORBIT_PERIOD_Y_MIN = 7.0
        private const val DRIFT_STEP_MS = 20_000L
        private const val AMBIENT_PERIOD_X_MIN = 2.9
        private const val AMBIENT_PERIOD_Y_MIN = 4.1
    }
}
