package com.bmkf.tv.data.network

import com.bmkf.tv.data.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StreamProbeResult(
    val original: StreamSource,
    val reachable: Boolean,
    val latencyMs: Long = Long.MAX_VALUE,
    val throughputMbps: Double = 0.0,
    val requiredBitrateMbps: Double = 0.0,
    val statusNote: String = ""
) {
    /**
     * Higher score = smoother playback (no buffering).
     * Compares actual measured throughput from the TV's network against the stream's advertised bitrate!
     */
    val smoothnessScore: Double
        get() {
            if (!reachable) return -1_000.0
            val effectiveRequired = if (requiredBitrateMbps > 0.3) requiredBitrateMbps else 3.0
            val ratio = throughputMbps / effectiveRequired
            // Heavy bonus when measured speed comfortably exceeds video bitrate (>= 1.15x real-time)
            val stabilityBonus = when {
                ratio >= 1.35 -> 500.0
                ratio >= 1.10 -> 320.0
                ratio >= 0.85 -> 120.0
                else -> 0.0
            }
            val latencyPenalty = (latencyMs.coerceAtMost(4000L) / 25.0)
            return stabilityBonus + (ratio.coerceAtMost(4.0) * 100.0) + (throughputMbps.coerceAtMost(20.0) * 5.0) - latencyPenalty
        }

    fun toAnnotatedStream(): StreamSource {
        val badge = when {
            !reachable -> "❌ Chậm/Lỗi"
            requiredBitrateMbps > 0.3 && throughputMbps >= requiredBitrateMbps * 1.15 ->
                String.format(Locale.US, "🟢 %.1fMbps • %dms", throughputMbps, latencyMs)
            requiredBitrateMbps <= 0.3 && throughputMbps >= 3.2 ->
                String.format(Locale.US, "🟢 %.1fMbps • %dms", throughputMbps, latencyMs)
            throughputMbps >= 1.5 ->
                String.format(Locale.US, "🟡 %.1fMbps • %dms", throughputMbps, latencyMs)
            else ->
                String.format(Locale.US, "🔴 %.1fMbps (Lag)", throughputMbps)
        }
        val cleanBaseName = original.name
            .replace(Regex("^[🟢🟡🔴❌]\\s*[^—]+—\\s*"), "")
            .trim()
        return original.copy(
            name = "$badge — $cleanBaseName",
            qualityLabel = badge
        )
    }
}

/**
 * Benchmarks candidate live sports streams in parallel directly from the user's TV network:
 * measures real latency, parses HLS stream bitrate, downloads a 192KB media sample, and ranks
 * servers by real-time smoothness (measured Mbps vs stream bitrate) so lagging servers are skipped!
 */
object StreamSpeedTester {

    private val BANDWIDTH_REGEX = Regex("""(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\d+)""")
    private const val SAMPLE_BYTES = 192 * 1024
    private const val MAX_PROBED_STREAMS = 10
    private const val PER_STREAM_TIMEOUT_MS = 4_200L

    private val probeClient: OkHttpClient = HttpClient.okHttp.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Benchmarks direct streams in parallel and returns the full list ordered from smoothest to slowest,
     * with real speed/latency badges attached to every tested stream.
     */
    suspend fun rankFastestStreams(streams: List<StreamSource>): List<StreamSource> = coroutineScope {
        val direct = streams.filter { !it.isWebViewFallback }
        val webFallbacks = streams.filter { it.isWebViewFallback }

        if (direct.size <= 1) return@coroutineScope streams

        val toProbe = direct.take(MAX_PROBED_STREAMS)
        val remainder = direct.drop(MAX_PROBED_STREAMS)

        val probed = toProbe.map { source ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(PER_STREAM_TIMEOUT_MS) {
                    probeSingle(source)
                } ?: StreamProbeResult(
                    original = source,
                    reachable = false,
                    statusNote = "Timeout"
                )
            }
        }.awaitAll()

        val sortedReachable = probed
            .filter { it.reachable }
            .sortedByDescending { it.smoothnessScore }
            .map { it.toAnnotatedStream() }

        val unreachable = probed
            .filter { !it.reachable }
            .map { it.toAnnotatedStream() }

        // Reachable fastest first -> untested remainder -> Web fallbacks -> dead/timed-out last
        sortedReachable + remainder + webFallbacks + unreachable
    }

    private fun probeSingle(source: StreamSource): StreamProbeResult {
        return try {
            val t0 = System.nanoTime()
            val req = buildRequest(source.url, source.headers)
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return StreamProbeResult(source, reachable = false, statusNote = "HTTP ${resp.code}")
                }
                val firstLatencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val body = resp.body ?: return StreamProbeResult(source, reachable = false)

                val isPlaylist = source.isHls ||
                    source.url.contains(".m3u8", ignoreCase = true) ||
                    (resp.header("Content-Type")?.contains("mpegurl", ignoreCase = true) == true)

                if (!isPlaylist) {
                    // Raw progressive / MPEG-TS live stream (e.g. media.ts): measure direct byte throughput
                    val readStart = System.nanoTime()
                    val bytes = readSampleBytes(body.byteStream(), SAMPLE_BYTES)
                    val readMs = ((System.nanoTime() - readStart) / 1_000_000L).coerceAtLeast(1L)
                    if (bytes < 8 * 1024) {
                        return StreamProbeResult(source, reachable = false, statusNote = "Empty stream")
                    }
                    val mbps = (bytes * 8.0) / (readMs * 1000.0)
                    return StreamProbeResult(
                        original = source,
                        reachable = true,
                        latencyMs = firstLatencyMs,
                        throughputMbps = mbps,
                        requiredBitrateMbps = 3.0
                    )
                }

                // HLS playlist: parse bitrate & follow to media segment to measure real CDN segment speed
                val playlistText = body.string()
                if (!playlistText.contains("#EXTM3U")) {
                    return StreamProbeResult(source, reachable = false, statusNote = "Invalid M3U8")
                }

                var requiredMbps = parseLowestBandwidthMbps(playlistText)
                var currentBaseUrl = resp.request.url.toString()
                var mediaText = playlistText

                // Follow Master -> Variant media playlist if needed
                if (playlistText.contains("#EXT-X-STREAM-INF")) {
                    val variantLine = pickVariantLine(playlistText)
                        ?: return StreamProbeResult(source, reachable = false, statusNote = "No variant")
                    val variantUrl = resolveUrl(currentBaseUrl, variantLine)
                    probeClient.newCall(buildRequest(variantUrl, source.headers)).execute().use { vResp ->
                        if (!vResp.isSuccessful) {
                            return StreamProbeResult(source, reachable = false, statusNote = "Variant ${vResp.code}")
                        }
                        currentBaseUrl = vResp.request.url.toString()
                        mediaText = vResp.body?.string().orEmpty()
                    }
                }

                val segmentLine = mediaText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .lastOrNull()
                    ?: return StreamProbeResult(source, reachable = false, statusNote = "No segments")

                val segmentUrl = resolveUrl(currentBaseUrl, segmentLine)
                val segStart = System.nanoTime()
                probeClient.newCall(buildRequest(segmentUrl, source.headers)).execute().use { segResp ->
                    if (!segResp.isSuccessful) {
                        return StreamProbeResult(source, reachable = false, statusNote = "Seg ${segResp.code}")
                    }
                    val segBody = segResp.body ?: return StreamProbeResult(source, reachable = false)
                    val bytes = readSampleBytes(segBody.byteStream(), SAMPLE_BYTES)
                    val segMs = ((System.nanoTime() - segStart) / 1_000_000L).coerceAtLeast(1L)
                    if (bytes < 4 * 1024) {
                        return StreamProbeResult(source, reachable = false, statusNote = "Tiny segment")
                    }
                    val mbps = (bytes * 8.0) / (segMs * 1000.0)
                    if (requiredMbps <= 0.0) requiredMbps = 3.0
                    StreamProbeResult(
                        original = source,
                        reachable = true,
                        latencyMs = firstLatencyMs,
                        throughputMbps = mbps,
                        requiredBitrateMbps = requiredMbps
                    )
                }
            }
        } catch (_: Exception) {
            StreamProbeResult(source, reachable = false, statusNote = "Error")
        }
    }

    internal fun parseLowestBandwidthMbps(m3u8: String): Double {
        val values = BANDWIDTH_REGEX.findAll(m3u8)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .filter { it > 100_000L }
            .toList()
        if (values.isEmpty()) return 0.0
        return values.min() / 1_000_000.0
    }

    private fun pickVariantLine(masterM3u8: String): String? {
        return masterM3u8.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun readSampleBytes(stream: java.io.InputStream, maxBytes: Int): Int {
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (read <= 0) break
            total += read
        }
        return total
    }
}
