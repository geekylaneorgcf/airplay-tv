package io.github.besliky.airplaytv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.besliky.airplaytv.service.ArtworkColors
import io.github.besliky.airplaytv.service.Lyrics
import io.github.besliky.airplaytv.service.LyricsRepository
import io.github.besliky.airplaytv.service.ReceiverState

/**
 * Fills the shared state with a made-up track so the Now Playing screen can be looked at without a
 * phone: `adb shell am start -n <package>/.ui.MainActivity --es preview now`. The modes are `now`,
 * `pause`, `lyrics`, `dim`, `ambient`, `blank`, and `next`/`previous`, which change track after a
 * few seconds to show the transition. Nothing here touches the network, and the real state is put
 * back when the screen closes.
 */
object PreviewMode {

    private val handler = Handler(Looper.getMainLooper())
    private var saved: ReceiverState.Snapshot? = null
    private var pending: Runnable? = null

    private data class Track(val title: String, val artist: String, val album: String, val hue: Float, val seconds: Long)

    private val tracks = listOf(
        Track("Dil Lagiyan", "Navaan Sandhu", "Naveezy", 215f, 175),
        Track("Midnight on the Harbour", "The Paper Lanterns", "Slow Tide", 340f, 212),
        Track("A Very Long Song Title That Needs Two Lines To Fit", "Various Artists", "Compilation, Vol. 2", 120f, 241),
    )

    fun begin(mode: String) {
        if (saved == null) saved = ReceiverState.current
        val first = tracks[0]
        val now = SystemClock.elapsedRealtime()
        val art = artwork(first.hue)
        ReceiverState.update {
            it.copy(
                status = ReceiverState.Status.CONNECTED,
                clientName = "iPhone",
                audioActive = true,
                videoActive = false,
                title = first.title,
                artist = first.artist,
                album = first.album,
                artwork = art,
                artworkSeq = it.artworkSeq + 1,
                artColors = ArtworkColors.from(art),
                durationMs = first.seconds * 1000,
                positionMs = 93_000,
                positionAtMs = now,
                playing = mode != "pause",
                volume = 0.8f,
                volumeAtMs = now,
                trackSeq = it.trackSeq + 1,
                trackDirection = 1,
                lyricsState = ReceiverState.LyricsState.FOUND,
                lyrics = demoLyrics(),
            )
        }
        if (mode == "lyricsnet") lookUpLyricsForReal(first)
        if (mode == "volume") schedule(3000) { changeVolume(0.35f) }
        if (mode == "next" || mode == "previous") {
            val direction = if (mode == "next") 1 else -1
            schedule(6000) { changeTrack(if (direction > 0) 1 else 2, direction) }
        }
    }

    /** Runs the real lookup (a request to lrclib.net with this made-up track's title and artist) to prove the network path. */
    private fun lookUpLyricsForReal(track: Track) {
        ReceiverState.update { it.copy(lyricsState = ReceiverState.LyricsState.LOADING, lyrics = null) }
        LyricsRepository.load(track.title, track.artist, track.seconds * 1000, "preview") { result ->
            handler.post {
                ReceiverState.update {
                    it.copy(
                        lyricsState = if (result != null) ReceiverState.LyricsState.FOUND else ReceiverState.LyricsState.NOT_FOUND,
                        lyrics = result,
                    )
                }
            }
        }
    }

    private fun changeVolume(level: Float) {
        ReceiverState.update { it.copy(volume = level, volumeAtMs = SystemClock.elapsedRealtime()) }
    }

    private fun changeTrack(index: Int, direction: Int) {
        val track = tracks[index]
        val now = SystemClock.elapsedRealtime()
        ReceiverState.update {
            it.copy(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.seconds * 1000,
                positionMs = 0,
                positionAtMs = now,
                trackSeq = it.trackSeq + 1,
                trackDirection = direction,
            )
        }
        schedule(250) {
            val art = artwork(track.hue)
            ReceiverState.update {
                it.copy(artwork = art, artworkSeq = it.artworkSeq + 1, artColors = ArtworkColors.from(art))
            }
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val task = Runnable { block() }
        pending = task
        handler.postDelayed(task, delayMs)
    }

    private var savedPhoto: Pair<Bitmap?, Int>? = null

    /** `photo` shows one made-up photo; `photo2` swaps it for another after a few seconds. */
    fun beginPhoto(mode: String) {
        if (savedPhoto == null) savedPhoto = Pair(ReceiverState.current.photo, ReceiverState.current.photoSeq)
        ReceiverState.update { it.copy(photo = landscape(200f), photoSeq = it.photoSeq + 1) }
        if (mode == "photo2") {
            schedule(5000) { ReceiverState.update { it.copy(photo = landscape(20f), photoSeq = it.photoSeq + 1) } }
        }
    }

    fun endPhoto() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val before = savedPhoto ?: return
        savedPhoto = null
        ReceiverState.update { it.copy(photo = before.first) }
    }

    /** A made-up 3:2 picture with a horizon, a sun and a few hills. */
    private fun landscape(hue: Float): Bitmap {
        val w = 1800
        val h = 1200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.35f)),
            Color.HSVToColor(floatArrayOf((hue + 25f) % 360f, 0.35f, 0.95f)), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = Color.argb(230, 255, 240, 200)
        canvas.drawCircle(w * 0.72f, h * 0.42f, h * 0.12f, paint)
        paint.color = Color.HSVToColor(floatArrayOf((hue + 120f) % 360f, 0.5f, 0.25f))
        canvas.drawOval(-w * 0.2f, h * 0.62f, w * 0.7f, h * 1.3f, paint)
        paint.color = Color.HSVToColor(floatArrayOf((hue + 140f) % 360f, 0.5f, 0.16f))
        canvas.drawOval(w * 0.3f, h * 0.7f, w * 1.3f, h * 1.4f, paint)
        return bitmap
    }

    fun end() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val before = saved ?: return
        saved = null
        ReceiverState.update {
            it.copy(
                status = before.status,
                clientName = before.clientName,
                audioActive = before.audioActive,
                title = before.title,
                artist = before.artist,
                album = before.album,
                artwork = before.artwork,
                artColors = before.artColors,
                durationMs = before.durationMs,
                positionMs = before.positionMs,
                positionAtMs = before.positionAtMs,
                playing = before.playing,
                volume = before.volume,
                volumeAtMs = before.volumeAtMs,
                lyricsState = before.lyricsState,
                lyrics = before.lyrics,
            )
        }
    }

    /** A generated cover: a diagonal gradient of one hue with a few translucent circles. */
    private fun artwork(hue: Float): Bitmap {
        val size = 600
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f)),
            Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.85f, 0.45f)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = Color.argb(60, 255, 255, 255)
        canvas.drawCircle(size * 0.30f, size * 0.35f, size * 0.28f, paint)
        paint.color = Color.argb(45, 0, 0, 0)
        canvas.drawCircle(size * 0.70f, size * 0.70f, size * 0.34f, paint)
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(size * 0.62f, size * 0.30f, size * 0.10f, paint)
        return bitmap
    }

    /** Invented lines, timed so the highlight is visible around 1:33. */
    private fun demoLyrics(): Lyrics {
        val words = listOf(
            "Neon rivers run beneath the sleeping town",
            "Every window holds a story folded small",
            "We were chasing echoes down the stairs",
            "Counting streetlights like they were stars",
            "Hold the evening a little longer now",
            "Let the radio carry what we cannot say",
            "Paper lanterns drifting out to sea",
            "Tell me that the morning can wait for us",
            "Slow the clock and keep the night in tune",
            "Nothing here is ending, nothing here is lost",
        )
        val lines = words.mapIndexed { i, text -> Lyrics.Line(70_000L + i * 6_500L, text) }
        return Lyrics(lines, synced = true)
    }
}
