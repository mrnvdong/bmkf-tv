package com.bmkf.tv.data.network

import android.net.Uri
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.model.SubtitleTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Resolves embed URLs and verifies direct HLS streams across KKPhim, VSMOV, CoBePhim, and StreamC.
 */
object StreamResolver {

    private val VSMOV_VIDEO_REGEX = Regex("""https?://([^/]*streamvsmov\.com)/video/([a-zA-Z0-9-]+)""")
    private val VSMOV_SIGNED_URL_REGEX = Regex("""signedMasterUrl\s*:\s*["']([^"']+)["']""")
    private val VSMOV_SIGNED_ENABLED_REGEX = Regex("""enableSignedUrl\s*:\s*(true|false)""")
    private val VSMOV_BASE_URL_REGEX = Regex("""const\s+baseUrl\s*=\s*["']([^"']+)["']""")
    private val VSMOV_HASH_REGEX = Regex("""const\s+videoHash\s*=\s*["']([^"']+)["']""")
    private val VSMOV_SUBTITLES_REGEX = Regex("""subtitles\s*:\s*(\[[^\n\r]*])""")
    private val STREAMC_EMBED_REGEX = Regex("""https?://([^/]*streamc\.xyz)/embed\.php\?[^"']*hash=([a-fA-F0-9]+)""")

    suspend fun resolveDirectOrEmbed(
        label: String,
        streamUrl: String,
        embedUrl: String,
        defaultHeaders: Map<String, String> = emptyMap(),
        k20BaseUrl: String = "https://sc.k-20.xyz"
    ): List<StreamSource> {
        val results = mutableListOf<StreamSource>()

        // 1. Direct m3u8 / mp4 stream URL
        if (streamUrl.isNotBlank()) {
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            val usableDirectly = if (isM3u8 && streamUrl.contains("darkbytes.xyz", ignoreCase = true)) {
                // Check for proprietary placeholder key (e.g. %%CHOPHIM_KEY%%)
                !hasUnsupportedKeyPlaceholder(streamUrl, defaultHeaders)
            } else {
                true
            }

            if (usableDirectly) {
                results += StreamSource(
                    name = "$label • Trực tiếp",
                    url = streamUrl,
                    isHls = isM3u8 || !streamUrl.contains(".mp4", ignoreCase = true),
                    isWebViewFallback = false,
                    headers = defaultHeaders
                )
            }
        }

        // 2. Embed URL resolution
        if (embedUrl.isNotBlank()) {
            val vsmovMatch = VSMOV_VIDEO_REGEX.find(embedUrl)
            val streamcMatch = STREAMC_EMBED_REGEX.find(embedUrl)

            when {
                vsmovMatch != null -> {
                    val resolved = resolveVsMovEmbed(label, embedUrl, vsmovMatch.groupValues[1], vsmovMatch.groupValues[2])
                    results.addAll(resolved)
                }

                streamcMatch != null -> {
                    val host = streamcMatch.groupValues[1]
                    val hash = streamcMatch.groupValues[2]
                    val cleanK20 = k20BaseUrl.trimEnd('/')
                    results += StreamSource(
                        name = "$label • StreamC Direct",
                        url = "$cleanK20/nguonc-hls.m3u8?hash=$hash&eh=${URLEncoder.encode(host, "UTF-8")}&mode=direct",
                        isHls = true,
                        headers = mapOf(
                            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                            "Referer" to "https://$host/",
                            "Origin" to "https://$host"
                        )
                    )
                    results += StreamSource(
                        name = "$label • StreamC Proxy",
                        url = "$cleanK20/nguonc-hls.m3u8?hash=$hash&eh=${URLEncoder.encode(host, "UTF-8")}",
                        isHls = true,
                        headers = mapOf(
                            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                            "Referer" to "https://phim.nguonc.com/",
                            "Origin" to "https://phim.nguonc.com"
                        )
                    )
                }

                embedUrl.contains("url=http", ignoreCase = true) -> {
                    val extracted = extractUrlParam(embedUrl)
                    if (!extracted.isNullOrBlank()) {
                        results += StreamSource(
                            name = "$label • HLS",
                            url = extracted,
                            isHls = extracted.contains(".m3u8", ignoreCase = true),
                            headers = defaultHeaders
                        )
                    }
                }
            }

            // Always append a WebView fallback option for embeds so playback never dead-ends
            results += StreamSource(
                name = "$label • Trình phát Web",
                url = embedUrl,
                isHls = false,
                isWebViewFallback = true,
                headers = defaultHeaders
            )
        }

        return results.distinctBy { it.url to it.isWebViewFallback }
    }

    private suspend fun resolveVsMovEmbed(
        label: String,
        embedUrl: String,
        host: String,
        hashFromUrl: String
    ): List<StreamSource> {
        val origin = "https://$host"
        val headers = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "$origin/",
            "Origin" to origin
        )
        val subtitles = mutableListOf<SubtitleTrack>()
        var masterUrl = "$origin/stream/$hashFromUrl/master.m3u8"

        try {
            val html = HttpClient.getString(
                embedUrl,
                headers = mapOf(
                    "Referer" to "https://vsmov.com/",
                    "User-Agent" to HttpClient.DEFAULT_USER_AGENT
                )
            )

            val parsedBase = VSMOV_BASE_URL_REGEX.find(html)?.groupValues?.getOrNull(1)?.trimEnd('/') ?: origin
            val parsedHash = VSMOV_HASH_REGEX.find(html)?.groupValues?.getOrNull(1) ?: hashFromUrl
            val signedEnabled = VSMOV_SIGNED_ENABLED_REGEX.find(html)?.groupValues?.getOrNull(1) == "true"
            val signedMaster = VSMOV_SIGNED_URL_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()

            masterUrl = if (signedEnabled && signedMaster.isNotBlank()) {
                if (signedMaster.startsWith("http")) signedMaster else "$parsedBase$signedMaster"
            } else {
                "$parsedBase/stream/$parsedHash/master.m3u8"
            }

            // Parse subtitles array from playerOptions
            val subsRaw = VSMOV_SUBTITLES_REGEX.find(html)?.groupValues?.getOrNull(1)
            if (!subsRaw.isNullOrBlank()) {
                val arr = HttpClient.json.parseToJsonElement(subsRaw) as? JsonArray
                arr?.forEach { elem ->
                    val obj = elem.jsonObject
                    val rawPath = obj["url"]?.jsonPrimitive?.content.orEmpty()
                    if (rawPath.isNotBlank()) {
                        val absUrl = if (rawPath.startsWith("http")) rawPath else "$parsedBase$rawPath"
                        val code = obj["code"]?.jsonPrimitive?.content.orEmpty().lowercase()
                        val display = when (code) {
                            "vie", "vi" -> "Tiếng Việt"
                            "eng", "en" -> "English"
                            else -> obj["name"]?.jsonPrimitive?.content ?: code.uppercase().ifBlank { "Phụ đề" }
                        }
                        subtitles += SubtitleTrack(
                            url = absUrl,
                            language = if (code == "vie") "vi" else if (code == "eng") "en" else code.ifBlank { "vi" },
                            label = display
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to deterministic /stream/{hash}/master.m3u8 URL constructed above
        }

        // Put Vietnamese subtitle first
        val sortedSubs = subtitles.sortedBy { if (it.language == "vi") 0 else 1 }

        return listOf(
            StreamSource(
                name = "$label • VSMOV HLS",
                url = masterUrl,
                isHls = true,
                isWebViewFallback = false,
                headers = headers,
                subtitles = sortedSubs
            )
        )
    }

    private suspend fun hasUnsupportedKeyPlaceholder(
        m3u8Url: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            val playlist = HttpClient.getString(m3u8Url, headers)
            playlist.contains("%%") && playlist.contains("EXT-X-KEY")
        } catch (_: Exception) {
            false
        }
    }

    private fun extractUrlParam(embedUrl: String): String? {
        return try {
            Uri.parse(embedUrl).getQueryParameter("url")
        } catch (_: Exception) {
            null
        }
    }
}
