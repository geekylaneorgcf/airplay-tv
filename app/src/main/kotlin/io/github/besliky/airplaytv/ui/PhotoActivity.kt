package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.besliky.airplaytv.service.ReceiverState
import kotlin.math.PI
import kotlin.math.sin

/**
 * A photo sent from the iPhone's Photos app, full screen on black and fitted to the screen. A new photo
 * dissolves in over the old one. The screen closes when the sender ends the photo session, and Back
 * closes it too. To protect OLED panels the picture drifts a few pixels, dims when nothing changes for a
 * while, and the screen closes after a long time without a new photo.
 */
class PhotoActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var orbitLayer: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var imageA: ImageView
    private lateinit var imageB: ImageView
    private var front: ImageView? = null
    private var shownSeq = -1
    private var lastChange = SystemClock.elapsedRealtime()
    private var dimmed = false
    private var preview = false

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    // Short hops, not a glide that never stops: a full-screen photo redrawn sixty times a second for as long as it
    // is up keeps the GPU busy for nothing, and a new photo then has no room to dissolve in smoothly.
    private val orbitTick = object : Runnable {
        override fun run() {
            val t = (SystemClock.elapsedRealtime() + ORBIT_HOP_MS) / 60000.0
            val density = resources.displayMetrics.density
            val x = (ORBIT_X_DP * density * sin(2 * PI * t / 11.0)).toFloat()
            val y = (ORBIT_Y_DP * density * sin(2 * PI * t / 7.0 + 0.7)).toFloat()
            orbitLayer.animate().translationX(x).translationY(y).setDuration(ORBIT_HOP_MS)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            handler.postDelayed(this, ORBIT_STEP_MS)
        }
    }

    private val careTick = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastChange
            if (!preview && idle >= CLOSE_AFTER_MS) {
                finish()
                return
            }
            if (!dimmed && idle >= DIM_AFTER_MS) {
                dimmed = true
                stage.animate().alpha(DIM_ALPHA).setDuration(2000).start()
            }
            handler.postDelayed(this, 5000)
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
        preview = intent.getStringExtra(NowPlayingActivity.EXTRA_PREVIEW) != null
        if (preview) PreviewMode.beginPhoto(intent.getStringExtra(NowPlayingActivity.EXTRA_PREVIEW) ?: "photo")

        imageA = fitted()
        imageB = fitted().apply { alpha = 0f }
        stage = FrameLayout(this).apply {
            addView(imageA, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(imageB, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        orbitLayer = FrameLayout(this).apply {
            addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(orbitLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    private fun fitted() = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    override fun onStart() {
        super.onStart()
        ReceiverState.observe(stateListener)
        handler.postDelayed(orbitTick, ORBIT_STEP_MS)
        handler.postDelayed(careTick, 5000)
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        if (preview) PreviewMode.endPhoto()
        super.onDestroy()
    }

    private fun render(s: ReceiverState.Snapshot) {
        val photo = s.photo
        if (photo == null) {
            finish()
            return
        }
        if (s.photoSeq != shownSeq) {
            val first = shownSeq < 0
            shownSeq = s.photoSeq
            show(photo, animate = !first)
            lastChange = SystemClock.elapsedRealtime()
            if (dimmed) {
                dimmed = false
                stage.animate().alpha(1f).setDuration(600).start()
            }
        }
    }

    /** Dissolves from the photo on screen to [bitmap]. */
    private fun show(bitmap: Bitmap, animate: Boolean) {
        val outgoing = front ?: imageB
        val incoming = if (outgoing === imageA) imageB else imageA
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bitmap)
        incoming.bringToFront()
        front = incoming
        if (!animate) {
            incoming.alpha = 1f
            outgoing.alpha = 0f
            return
        }
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(DISSOLVE_MS).setInterpolator(DecelerateInterpolator()).start()
        outgoing.animate().alpha(0f).setDuration(DISSOLVE_MS).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        lastChange = SystemClock.elapsedRealtime()
        if (dimmed) {
            dimmed = false
            stage.animate().alpha(1f).setDuration(400).start()
            if (keyCode != KeyEvent.KEYCODE_BACK) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val DISSOLVE_MS = 700L
        const val ORBIT_STEP_MS = 6_000L
        const val ORBIT_HOP_MS = 700L
        const val ORBIT_X_DP = 14f
        const val ORBIT_Y_DP = 9f
        const val DIM_AFTER_MS = 3 * 60_000L
        const val DIM_ALPHA = 0.5f
        const val CLOSE_AFTER_MS = 45 * 60_000L
    }
}
