package com.bmkf.tv.player

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.bmkf.tv.data.model.StreamSource

/**
 * Prevents purple/green color corruption on Android TV hardware (such as Realtek RTD285x / Amlogic SDR/HDR10 TVs)
 * caused by Dolby Vision Profile 5 (IPTPQc2 color space) or buggy hardware YUV decoders.
 */
@OptIn(UnstableApi::class)
object VideoColorGuard {

    private val DV_TITLE_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[\(])(dv|dovi|dolby[\s._-]?vision|dvhe|dvh1)(?:$|[\s._\-\]\)])"""
    )
    private val HDR10_FALLBACK_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[\(])(hdr10\+?|hdr|sdr|hlg)(?:$|[\s._\-\]\)])"""
    )
    private val HI10P_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[\(])(hi10p?|10[\s._-]?bit)(?:$|[\s._\-\]\)])"""
    )

    fun deviceSupportsDolbyVision(context: Context): Boolean {
        val displaySupportsDv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes
            hdrTypes?.any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION } == true
        } else {
            false
        }
        if (!displaySupportsDv) return false

        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    fun isDolbyVisionTitle(title: String): Boolean = DV_TITLE_REGEX.containsMatchIn(title)

    /**
     * Releases tagged with DV/DoVi WITHOUT HDR/HDR10 fallback are typically Dolby Vision Profile 5
     * (IPTPQc2 color space), which renders bright purple & green on non-DV TVs.
     */
    fun isPureDolbyVisionRisk(title: String): Boolean {
        return DV_TITLE_REGEX.containsMatchIn(title) && !HDR10_FALLBACK_REGEX.containsMatchIn(title)
    }

    fun isHi10pRisk(title: String): Boolean {
        return HI10P_REGEX.containsMatchIn(title) &&
            (title.contains("x264", ignoreCase = true) || title.contains("h264", ignoreCase = true) || title.contains("avc", ignoreCase = true))
    }

    /**
     * Inspects an actual decoded Media3 Format to detect Dolby Vision Profile 5 (dvhe.05 / dvh1.05)
     * or incompatible Dolby Vision tracks on a non-DV TV.
     */
    fun isPurpleRiskFormat(format: Format, deviceSupportsDv: Boolean): Boolean {
        if (deviceSupportsDv) return false
        val codecs = format.codecs.orEmpty().lowercase()
        val mime = format.sampleMimeType.orEmpty().lowercase()

        // Dolby Vision Profile 5 uses proprietary IPTPQc2 color space -> ALWAYS purple/green without DV hardware
        if (codecs.startsWith("dvhe.05") || codecs.startsWith("dvh1.05") || codecs.contains("dvhe.05") || codecs.contains("dvh1.05")) {
            return true
        }
        // Generic Dolby Vision without standard HDR10/HLG transfer fallback
        if (mime == MimeTypes.VIDEO_DOLBY_VISION) {
            val transfer = format.colorInfo?.colorTransfer ?: Format.NO_VALUE
            val hasStandardHdrLayer = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
            if (!hasStandardHdrLayer) return true
        }
        return false
    }

    /**
     * Reorders candidate streams so color-safe (SDR / HDR10) streams are always tried before
     * Dolby Vision streams on TVs that do not support Dolby Vision.
     */
    fun sortStreamsForColorSafety(
        streams: List<StreamSource>,
        deviceSupportsDv: Boolean
    ): List<StreamSource> {
        if (deviceSupportsDv || streams.size <= 1) return streams
        return streams.sortedBy { stream ->
            when {
                stream.isWebViewFallback -> 3
                isPureDolbyVisionRisk(stream.name) || isPureDolbyVisionRisk(stream.url) -> 2
                isDolbyVisionTitle(stream.name) || isDolbyVisionTitle(stream.url) -> 1
                else -> 0
            }
        }
    }

    /**
     * Creates a MediaCodecSelector that:
     * 1. Avoids Dolby Vision Profile 5 fallback corruption on unsupported TVs.
     * 2. Optionally prefers software video decoders (OMX.google.* / c2.android.*) when user enables
     *    "Fix màu (SW)" for streams whose hardware OMX decoder produces wrong colors (e.g. Hi10P / bad BT.2020).
     */
    fun createCodecSelector(
        preferSoftwareVideo: Boolean,
        deviceSupportsDv: Boolean
    ): MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val isVideo = mimeType.startsWith("video/", ignoreCase = true)

        // Prevent Media3 from opening a broken/non-existent Dolby Vision decoder on SDR/HDR10 TVs
        if (!deviceSupportsDv && mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)) {
            return@MediaCodecSelector emptyList()
        }

        val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )

        if (!isVideo || !preferSoftwareVideo) {
            defaultList
        } else {
            // Put software decoders first for color accuracy on tricky 10-bit/HDR/Hi10P streams
            val (software, hardware) = defaultList.partition { isSoftwareDecoder(it) }
            software + hardware
        }
    }

    private fun isSoftwareDecoder(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase()
        return info.softwareOnly ||
            name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains(".sw.")
    }
}
