package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.VIDEO
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.service.VideoPlayback

/**
 * Plays a video the sender gave the address of (AirPlay video: the cast button in Safari, YouTube and other apps). The phone only
 * says where the stream is and when to play, pause or move; this screen fetches and plays it with the platform's player (which
 * takes HLS and ordinary files over https) and keeps telling the receiver where it is, which the phone asks for about once a second.
 * Back ends it; OK pauses and plays; left and right move ten seconds.
 */
class VideoPlayerActivity : Activity(), SurfaceHolder.Callback, VideoPlayback.Controller {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var url = ""
    private var startSeconds = -1.0
    private var startFraction = 0.0
    private var prepared = false
    private var failed = false
    private var rate = 1.0
    private var endedBySender = false
    private lateinit var message: TextView
    private lateinit var surfaceView: SurfaceView

    private val report = object : Runnable {
        override fun run() {
            reportToSender()
            handler.postDelayed(this, REPORT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        read(intent)
        val root = FrameLayout(this)
        val surface = SurfaceView(this)
        surfaceView = surface
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        message = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        VideoPlayback.controller = this
    }

    private fun read(intent: android.content.Intent) {
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        startSeconds = intent.getDoubleExtra(EXTRA_START, -1.0)
        startFraction = intent.getDoubleExtra(EXTRA_FRACTION, 0.0)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
        releasePlayer()
        startPlayback()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        if (VideoPlayback.controller === this) VideoPlayback.controller = null
        if (!endedBySender) VideoPlayback.onScreenEnded?.invoke()
        super.onDestroy()
    }

    // ---- the surface and the player

    override fun surfaceCreated(holder: SurfaceHolder) {
        startPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releasePlayer()
    }

    private fun startPlayback() {
        if (url.isEmpty() || player != null) return
        val holder = surfaceView.holder
        failed = false
        prepared = false
        rate = 1.0
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
            created.setDisplay(holder)
            created.setOnPreparedListener { prepare(it) }
            created.setOnCompletionListener {
                Log.i(VIDEO, "video: the stream ended")
                rate = 0.0
                reportToSender()
            }
            created.setOnErrorListener { _, what, extra ->
                fail("the player stopped ($what, $extra)")
                true
            }
            created.setDataSource(this, Uri.parse(url))
            created.prepareAsync()
            Log.i(VIDEO, "video: loading from ${VideoPlayback.hostOf(url)}")
        } catch (e: Exception) {
            fail("cannot open the stream: ${e.javaClass.simpleName}: ${e.message}")
        }
        handler.removeCallbacks(report)
        handler.post(report)
    }

    private fun prepare(mp: MediaPlayer) {
        prepared = true
        val duration = mp.duration
        val start = when {
            startSeconds >= 0 -> (startSeconds * 1000).toInt()
            startFraction > 0 && duration > 0 -> (startFraction * duration).toInt()
            else -> 0
        }
        if (start > 0) mp.seekTo(start)
        applyRate(rate)
        Log.i(VIDEO, "video: ready, ${duration / 1000} s long")
        reportToSender()
    }

    private fun applyRate(newRate: Double) {
        rate = newRate
        val mp = player ?: return
        if (!prepared) return
        try {
            if (newRate <= 0.0) {
                if (mp.isPlaying) mp.pause()
            } else {
                if (newRate != 1.0 && Build.VERSION.SDK_INT >= 23) mp.playbackParams = PlaybackParams().setSpeed(newRate.toFloat())
                if (!mp.isPlaying) mp.start()
            }
        } catch (e: IllegalStateException) {
            Log.w(VIDEO, "video: the player did not take the rate", e)
        }
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        prepared = false
        try {
            mp.release()
        } catch (_: RuntimeException) {
            // already gone
        }
    }

    private fun fail(why: String) {
        if (failed) return
        failed = true
        Log.w(VIDEO, "video: $why")
        message.text = getString(R.string.video_failed)
        message.visibility = android.view.View.VISIBLE
        rate = 0.0
        reportToSender()
        handler.postDelayed({ if (!isFinishing) finish() }, FAIL_SHOWN_MS)
    }

    /** Tells the receiver where the video is; the sender reads it with GET /playback-info. */
    private fun reportToSender() {
        if (!NativeBridge.loaded) return
        val mp = player
        var duration = 0.0
        var position = 0.0
        if (mp != null && prepared) {
            try {
                duration = mp.duration / 1000.0
                position = mp.currentPosition / 1000.0
            } catch (_: IllegalStateException) {
                // the player is being released
            }
        } else if (startSeconds > 0) {
            position = startSeconds
        }
        val playing = mp != null && prepared && !failed && (try { mp.isPlaying } catch (_: IllegalStateException) { false })
        NativeBridge.nativeSetPlayback(duration, position, if (playing) maxOf(rate, 1.0) else 0.0, prepared && !failed)
    }

    // ---- what the sender asks for

    override fun setRate(rate: Double) {
        handler.post { applyRate(rate) }
    }

    override fun seekTo(seconds: Double) {
        handler.post {
            val mp = player ?: return@post
            if (prepared) {
                try {
                    mp.seekTo((seconds * 1000).toInt())
                } catch (_: IllegalStateException) {
                    // not ready to move
                }
            } else {
                startSeconds = seconds
            }
        }
    }

    override fun stop() {
        endedBySender = true
        handler.post { if (!isFinishing) finish() }
    }

    // ---- the remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mp = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mp != null && prepared) applyRate(if (mp.isPlaying) 0.0 else 1.0)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                applyRate(1.0)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                applyRate(0.0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (mp != null && prepared) {
                    val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                    val target = (mp.currentPosition + if (forward) STEP_MS else -STEP_MS).coerceIn(0, maxOf(mp.duration - 1000, 0))
                    mp.seekTo(target)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_START = "start"
        const val EXTRA_FRACTION = "fraction"
        private const val REPORT_MS = 500L
        private const val FAIL_SHOWN_MS = 4000L
        private const val STEP_MS = 10_000
    }
}
