package io.github.besliky.airplaytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.AUDIO

/**
 * Hooks for trying the receiver from a computer, installed in debug builds only (the service checks BuildConfig.DEBUG).
 *
 * `adb shell am broadcast -a io.github.besliky.airplaytv.dev.action.STEAL_FOCUS --ei seconds 6` makes this app ask for the sound the way
 * the YouTube app does when it starts to play, hold it for a while and give it back: the receiver's output must go silent, and come
 * back when the sound is given back.
 */
object DebugHooks {

    const val ACTION_STEAL_FOCUS = "io.github.besliky.airplaytv.dev.action.STEAL_FOCUS"

    fun install(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val handler = Handler(Looper.getMainLooper())
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val seconds = intent.getIntExtra("seconds", 6).coerceIn(1, 60)
                val transient = intent.getBooleanExtra("transient", false)
                val request = AudioFocusRequest.Builder(
                    if (transient) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN,
                )
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                val result = manager.requestAudioFocus(request)
                Log.i(AUDIO, "debug: another player takes the sound for $seconds s (${if (transient) "for a moment" else "for good"}, result $result)")
                handler.postDelayed({
                    manager.abandonAudioFocusRequest(request)
                    Log.i(AUDIO, "debug: the other player lets the sound go")
                }, seconds * 1000L)
            }
        }, IntentFilter(ACTION_STEAL_FOCUS))
    }
}
