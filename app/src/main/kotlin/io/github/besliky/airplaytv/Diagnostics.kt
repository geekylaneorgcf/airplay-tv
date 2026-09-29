package io.github.besliky.airplaytv

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings as SystemSettings
import android.view.Display
import io.github.besliky.airplaytv.core.DecoderSelector
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.service.ReceiverState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the plain-text report behind "Export diagnostics". It describes the device,
 * the receiver configuration, decoder capabilities, counters and recent log lines.
 * It never contains keys, PINs, paired device keys or sender names.
 */
object Diagnostics {

    fun build(context: Context): String {
        val settings = Settings(context)
        val state = ReceiverState.current
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

        sb.appendLine("AirPlay TV diagnostics")
        sb.appendLine("generated      $now")
        sb.appendLine("app            ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("device         ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        sb.appendLine("android        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("abi            ${Build.SUPPORTED_ABIS.joinToString()}")
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.mode?.let { sb.appendLine("display        ${it.physicalWidth}x${it.physicalHeight} @ ${"%.2f".format(it.refreshRate)} Hz") }
        sb.appendLine("tv features    leanback=${context.packageManager.hasSystemFeature("android.software.leanback")}")
        sb.appendLine()

        sb.appendLine("receiver       ${state.status}${state.error?.let { " ($it)" } ?: ""}")
        sb.appendLine("native library ${if (NativeBridge.loaded) "loaded" else "missing"}")
        sb.appendLine("port           ${state.port}")
        sb.appendLine("published as   ${state.publishedName.ifEmpty { "-" }}")
        sb.appendLine("network        ${state.transport.ifEmpty { "-" }} ${state.addresses.joinToString()}")
        sb.appendLine("session        ${if (state.clientName != null) "active (${state.clientModel})" else "none"}")
        sb.appendLine("settings       enabled=${settings.enabled} pin=${settings.requirePin} autostart=${settings.startAutomatically}")
        sb.appendLine("               resolution=${settings.resolution.key} fps=${settings.frameRate} decoder=${settings.preferredDecoder.ifEmpty { "auto" }}")
        val overlay = Build.VERSION.SDK_INT < 29 || SystemSettings.canDrawOverlays(context)
        sb.appendLine("auto open      ${if (overlay) "allowed" else "not allowed"}")
        sb.appendLine()

        sb.appendLine("decoders")
        for (mime in listOf(DecoderSelector.AVC, DecoderSelector.HEVC)) {
            for (d in DecoderSelector.candidates(mime)) {
                sb.appendLine("  $mime  ${d.name}  ${if (d.hardware) "hardware" else "software"}${if (d.lowLatency) " low-latency" else ""}")
            }
        }
        val sel = DecoderSelector.select(settings.preferredDecoder.ifEmpty { null })
        sb.appendLine("  selected avc=${sel.avcDecoder} hevc=${sel.hevcDecoder} hevc4k=${sel.hevc4k}")
        sb.appendLine()

        if (NativeBridge.loaded) {
            val s = LongArray(25)
            NativeBridge.nativeStats(s)
            sb.appendLine("counters")
            sb.appendLine("  video frames in=${s[0]} decoded=${s[3]} shown=${s[4]} dropped=${s[5]} keyframes=${s[2]} resets=${s[13]}")
            sb.appendLine("  video size ${s[10]}x${s[11]} codec=${s[12]}")
            sb.appendLine("  audio packets=${s[14]} lost=${s[15]} decoded=${s[16]} dropped frames=${s[18]} type=${s[19]}")
            sb.appendLine("  sessions=${s[23]} refused connections=${s[24]}")
            sb.appendLine()
            sb.appendLine("recent log")
            sb.append(NativeBridge.nativeLogHistory() ?: "")
        }
        return sb.toString()
    }

    /** Writes the report to the app's external files directory (reachable over adb or USB). */
    fun save(context: Context, report: String): File? = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val name = "diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        File(dir, name).apply { writeText(report) }.also { file ->
            // keep only the five newest reports
            dir.listFiles { f -> f.name.startsWith("diagnostics-") }
                ?.sortedByDescending { it.name }
                ?.drop(5)
                ?.forEach { it.delete() }
            Log.i(Log.Category.SERVICE, "diagnostics written to ${file.name}")
        }
    } catch (e: Exception) {
        Log.w(Log.Category.SERVICE, "cannot save diagnostics", e)
        null
    }

    fun licenses(context: Context): String = try {
        context.assets.open("licenses.txt").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        "See THIRD_PARTY_LICENSES.md in the source repository."
    }
}
