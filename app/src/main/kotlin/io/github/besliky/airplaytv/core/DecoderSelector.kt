package io.github.besliky.airplaytv.core

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Picks the video decoders used for mirroring: hardware decoders first, those that
 * advertise low-latency decoding preferred. Software decoders are only used when a
 * device has no hardware decoder at all (e.g. an emulator).
 */
object DecoderSelector {

    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    data class Selection(
        val avcDecoder: String?,
        val hevcDecoder: String?,
        /** A hardware HEVC decoder that handles 2160p30, required for 4K mirroring. */
        val hevc4k: Boolean,
        val options: Int,
    )

    data class DecoderInfo(val name: String, val hardware: Boolean, val lowLatency: Boolean)

    private fun decodersFor(mime: String): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (Build.VERSION.SDK_INT < 29 || !info.isAlias)
        }
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val n = info.name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") ||
            (!n.startsWith("omx.") && !n.startsWith("c2."))
    }

    private fun supportsLowLatency(info: MediaCodecInfo, mime: String): Boolean =
        Build.VERSION.SDK_INT >= 30 && try {
            info.getCapabilitiesForType(mime).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
        } catch (_: RuntimeException) {
            false
        }

    /** Decoders for [mime], best first. */
    fun candidates(mime: String): List<DecoderInfo> = decodersFor(mime)
        .map { DecoderInfo(it.name, !isSoftware(it), supportsLowLatency(it, mime)) }
        .sortedWith(compareBy({ !it.hardware }, { !it.lowLatency }))

    fun select(preferredAvc: String?): Selection {
        val avc = candidates(AVC)
        val avcHardware = avc.filter { it.hardware }
        val chosenAvc = preferredAvc?.takeIf { p -> avc.any { it.name == p } }
            ?: avcHardware.firstOrNull()?.name
            ?: avc.firstOrNull()?.name

        val hevcInfo = decodersFor(HEVC).filter { !isSoftware(it) }
            .sortedBy { !supportsLowLatency(it, HEVC) }
            .firstOrNull()
        val hevc4k = hevcInfo?.let {
            try {
                it.getCapabilitiesForType(HEVC).videoCapabilities?.areSizeAndRateSupported(3840, 2160, 30.0) == true
            } catch (_: RuntimeException) {
                false
            }
        } ?: false

        val options = NativeBridge.DECODER_LOW_LATENCY or NativeBridge.DECODER_VENDOR_KEYS or
            NativeBridge.DECODER_REALTIME
        return Selection(chosenAvc, hevcInfo?.name, hevc4k, options)
    }
}
