package com.bmkf.tv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.bmkf.tv.BmkfTvApp
import com.bmkf.tv.data.model.EpisodeItem
import com.bmkf.tv.data.model.MovieDetail
import com.bmkf.tv.data.model.PlayerLaunchPayload
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.model.VideoScaleMode
import com.bmkf.tv.data.model.WatchHistoryEntry
import com.bmkf.tv.data.network.HttpClient
import com.bmkf.tv.data.network.StreamSpeedTester
import com.bmkf.tv.ui.theme.BmkfTvTheme
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.Locale

private enum class PlayerDrawer {
    NONE,
    EPISODES,
    STREAMS,
    SUBTITLES,
    SPEED
}

private val TopHudScrim = Brush.verticalGradient(
    listOf(CinemaBackground.copy(alpha = 0.90f), androidx.compose.ui.graphics.Color.Transparent)
)
private val BottomHudScrim = Brush.verticalGradient(
    listOf(androidx.compose.ui.graphics.Color.Transparent, CinemaBackground.copy(alpha = 0.94f))
)
private val DrawerRowShape = RoundedCornerShape(10.dp)
private val ProgressBarShape = RoundedCornerShape(3.dp)

/**
 * Fullscreen TV player hosting native Media3 PlayerView (SurfaceView) directly inside a native FrameLayout
 * behind a transparent ComposeView HUD.
 *
 * Includes:
 * - Native SurfaceView layout outside Compose AndroidView (eliminates Android 9 Realtek squishing bugs)
 * - VideoAspectController auto-fit for 16:9 TV screens
 * - VideoColorGuard protection against Dolby Vision Profile 5 purple/green tint on non-DV TVs
 *   plus 1-click Hardware (HW) / Software Color-Fix (SW) decoder toggle!
 */
@OptIn(UnstableApi::class)
class TvPlayerActivity : AppCompatActivity() {

    private var keyHandler: ((KeyEvent) -> Boolean)? = null
    private lateinit var playerView: PlayerView
    private var currentPlayer: ExoPlayer? = null
    private var deviceSupportsDv: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val rawPayload = intent.getStringExtra(EXTRA_PAYLOAD)
        val payload = rawPayload?.let {
            runCatching { HttpClient.json.decodeFromString<PlayerLaunchPayload>(it) }.getOrNull()
        }
        if (payload == null) {
            finish()
            return
        }

        val app = application as BmkfTvApp
        val settings = app.storage.settings.value
        deviceSupportsDv = VideoColorGuard.deviceSupportsDolbyVision(this)

        val isLiveSport = payload.movie.source == SourceId.SPORT_TV
        val initialPlayer = createExoPlayer(
            preferSoftwareVideo = false,
            blockDolbyVision = settings.safeColorMode && !deviceSupportsDv,
            isLiveSport = isLiveSport
        )
        currentPlayer = initialPlayer

        // Layer 0: Native Media3 PlayerView (MATCH_PARENT, hardware SurfaceView)
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            useController = false
            keepScreenOn = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            subtitleView?.apply {
                setStyle(
                    CaptionStyleCompat(
                        Color.WHITE,
                        0x80000000.toInt(),
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        Color.BLACK,
                        null
                    )
                )
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * settings.subtitleTextScale)
                visibility = if (settings.subtitleEnabled) View.VISIBLE else View.GONE
            }
            player = initialPlayer
        }

        // Layer 1: Transparent ComposeView strictly for HUD & TV Remote drawers
        val hudComposeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BmkfTvTheme {
                    TvPlayerHudOverlay(
                        payload = payload,
                        app = app,
                        initialPlayer = initialPlayer,
                        playerView = playerView,
                        deviceSupportsDv = deviceSupportsDv,
                        onRebuildPlayer = { preferSw ->
                            val newPlayer = createExoPlayer(
                                preferSoftwareVideo = preferSw,
                                blockDolbyVision = settings.safeColorMode && !deviceSupportsDv,
                                isLiveSport = isLiveSport
                            )
                            val old = currentPlayer
                            playerView.player = newPlayer
                            currentPlayer = newPlayer
                            old?.release()
                            newPlayer
                        },
                        onApplyAspect = ::applyAspectToPlayerView,
                        onRegisterKeyHandler = { keyHandler = it },
                        onOpenWebFallback = { url, referer, title ->
                            startActivity(WebViewPlayerActivity.createIntent(this@TvPlayerActivity, url, referer, title))
                            finish()
                        },
                        onExit = { finish() }
                    )
                }
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(playerView)
            addView(hudComposeView)
        }
        setContentView(root)
    }

    private fun createExoPlayer(
        preferSoftwareVideo: Boolean,
        blockDolbyVision: Boolean,
        isLiveSport: Boolean = false
    ): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isLiveSport) 10_000 else 8_000,
                if (isLiveSport) 30_000 else 25_000,
                if (isLiveSport) 2_500 else 1_500,
                if (isLiveSport) 4_000 else 3_000
            )
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val codecSelector = VideoColorGuard.createCodecSelector(
            preferSoftwareVideo = preferSoftwareVideo,
            deviceSupportsDv = !blockDolbyVision
        )

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playWhenReady = true
            }
    }

    private fun applyAspectToPlayerView(mode: VideoScaleMode, videoSize: VideoSize): ResolvedAspectConfig {
        val metrics = resources.displayMetrics
        val screenW = playerView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenH = playerView.height.takeIf { it > 0 } ?: metrics.heightPixels

        val resolved = VideoAspectController.resolve(mode, videoSize, screenW, screenH)
        playerView.resizeMode = resolved.resizeMode

        val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        if (contentFrame != null) {
            contentFrame.resizeMode = resolved.resizeMode
            val targetAspect = resolved.aspectRatioOverride ?: computeNaturalAspectRatio(videoSize)
            if (targetAspect != null && targetAspect > 0f) {
                contentFrame.setAspectRatio(targetAspect)
            }
            contentFrame.requestLayout()
        }

        playerView.videoSurfaceView?.requestLayout()
        playerView.requestLayout()
        return resolved
    }

    private fun computeNaturalAspectRatio(videoSize: VideoSize): Float? {
        if (videoSize.width <= 0 || videoSize.height <= 0) return null
        val sar = videoSize.pixelWidthHeightRatio.takeIf { it.isFinite() && it in 0.5f..2.5f } ?: 1f
        return (videoSize.width.toFloat() * sar) / videoSize.height.toFloat()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyHandler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        playerView.player = null
        currentPlayer?.release()
        currentPlayer = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        private const val EXTRA_PAYLOAD = "extra_player_payload"

        fun createIntent(context: Context, payload: PlayerLaunchPayload): Intent {
            return Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_PAYLOAD, HttpClient.json.encodeToString(payload))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvPlayerHudOverlay(
    payload: PlayerLaunchPayload,
    app: BmkfTvApp,
    initialPlayer: ExoPlayer,
    playerView: PlayerView,
    deviceSupportsDv: Boolean,
    onRebuildPlayer: (preferSoftwareVideo: Boolean) -> ExoPlayer,
    onApplyAspect: (VideoScaleMode, VideoSize) -> ResolvedAspectConfig,
    onRegisterKeyHandler: ((KeyEvent) -> Boolean) -> Unit,
    onOpenWebFallback: (url: String, referer: String, title: String) -> Unit,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings = remember { app.storage.settings.value }
    val isLiveSport = payload.movie.source == SourceId.SPORT_TV
    var exoPlayer by remember { mutableStateOf(initialPlayer) }
    var preferSoftwareDecoder by remember { mutableStateOf(false) }

    var serverIndex by remember {
        mutableIntStateOf(payload.initialServerIndex.coerceIn(0, (payload.servers.size - 1).coerceAtLeast(0)))
    }
    var episodeIndex by remember { mutableIntStateOf(payload.initialEpisodeIndex.coerceAtLeast(0)) }

    val currentServer = payload.servers.getOrNull(serverIndex)
    val episodes = currentServer?.episodes.orEmpty()
    val currentEpisode: EpisodeItem? = episodes.getOrNull(episodeIndex) ?: episodes.firstOrNull()

    var streams by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var activeStreamIndex by remember { mutableIntStateOf(0) }
    var laggedStreamIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rebufferTimestamps by remember { mutableStateOf<List<Long>>(emptyList()) }
    var isBenchmarking by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }

    var showControls by remember { mutableStateOf(true) }
    var lastInteractionTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var activeDrawer by remember { mutableStateOf(PlayerDrawer.NONE) }
    var toastBadgeText by remember { mutableStateOf<String?>(null) }

    var subtitlesEnabled by remember { mutableStateOf(settings.subtitleEnabled) }
    var selectedSubtitleIdx by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var scaleMode by remember { mutableStateOf(settings.defaultScaleMode) }
    var lastVideoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var aspectStatusBadge by remember { mutableStateOf(settings.defaultScaleMode.label) }

    var nextEpisodeCountdown by remember { mutableIntStateOf(-1) }
    var pendingResumeMs by remember { mutableLongStateOf(payload.resumePositionMs) }

    val playPauseFocusRequester = remember { FocusRequester() }

    fun persistProgress() {
        val ep = currentEpisode ?: return
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        if (pos > 3_000L || dur > 0L) {
            app.storage.saveWatchProgress(
                WatchHistoryEntry(
                    movie = payload.movie,
                    episodeId = ep.id,
                    episodeName = ep.name,
                    serverName = currentServer?.serverName ?: ep.serverName,
                    positionMs = pos,
                    durationMs = dur
                )
            )
        }
    }

    fun applyCurrentAspect(mode: VideoScaleMode = scaleMode, size: VideoSize = lastVideoSize) {
        val resolved = onApplyAspect(mode, size)
        aspectStatusBadge = resolved.statusBadge
    }

    fun prepareStream(source: StreamSource, startPositionMs: Long, targetPlayer: ExoPlayer = exoPlayer) {
        if (source.isWebViewFallback) {
            val referer = source.headers["Referer"].orEmpty()
            val label = "${payload.movie.title} - ${currentEpisode?.name.orEmpty()}"
            onOpenWebFallback(source.url, referer, label)
            return
        }

        errorMessage = null
        isBuffering = true

        val okHttpFactory = OkHttpDataSource.Factory(HttpClient.okHttp).apply {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = source.headers["User-Agent"] ?: HttpClient.DEFAULT_USER_AGENT
            source.headers.forEach { (k, v) -> headers[k] = v }
            setDefaultRequestProperties(headers)
        }
        val strippingFactory = TsDisguiseStrippingDataSource.Factory(okHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(strippingFactory)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(source.url))

        if (source.isHls || source.url.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        if (isLiveSport) {
            // 8-second smooth live-edge cushion prevents constant micro-stutters on Vietnam -> EU/US routes
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(8_000L)
                    .setMinOffsetMs(4_000L)
                    .setMaxOffsetMs(20_000L)
                    .build()
            )
        }

        if (subtitlesEnabled && source.subtitles.isNotEmpty()) {
            val chosen = source.subtitles.getOrNull(selectedSubtitleIdx) ?: source.subtitles.first()
            val subMime = when {
                chosen.mimeType.isNotBlank() -> chosen.mimeType
                chosen.url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(chosen.url))
                .setMimeType(subMime)
                .setLanguage(chosen.language)
                .setLabel(chosen.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
        }

        playerView.subtitleView?.visibility = if (subtitlesEnabled) View.VISIBLE else View.GONE

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
        targetPlayer.setMediaSource(mediaSource)
        targetPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        if (startPositionMs > 5_000L) {
            targetPlayer.seekTo(startPositionMs)
        }
        targetPlayer.prepare()
        targetPlayer.playWhenReady = true
    }

    fun switchDecoderMode(useSoftware: Boolean) {
        val resume = exoPlayer.currentPosition.coerceAtLeast(0L)
        preferSoftwareDecoder = useSoftware
        val rebuilt = onRebuildPlayer(useSoftware)
        exoPlayer = rebuilt
        streams.getOrNull(activeStreamIndex)?.let { current ->
            prepareStream(current, resume, rebuilt)
        }
        toastBadgeText = if (useSoftware) {
            "Chế độ Fix màu (Giải mã SW)"
        } else {
            "Chế độ Phần cứng (HW — Nhẹ CPU)"
        }
    }

    fun switchToNextHealthyServer(reasonToast: String): Boolean {
        val nextMarked = laggedStreamIndices + activeStreamIndex
        laggedStreamIndices = nextMarked
        val candidateIdx = streams.indices.firstOrNull { idx ->
            idx !in nextMarked && !streams[idx].isWebViewFallback && !streams[idx].name.startsWith("❌")
        } ?: streams.indices.firstOrNull { idx ->
            idx !in nextMarked && !streams[idx].isWebViewFallback
        }
        if (candidateIdx == null) return false

        activeStreamIndex = candidateIdx
        rebufferTimestamps = emptyList()
        toastBadgeText = reasonToast
        val resume = if (isLiveSport) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        prepareStream(streams[candidateIdx], resume)
        return true
    }

    fun rebenchmarkStreamsNow() {
        if (isBenchmarking || streams.size <= 1) return
        scope.launch {
            isBenchmarking = true
            toastBadgeText = "Đang đo tốc độ ${streams.size} server..."
            val ranked = runCatching { StreamSpeedTester.rankFastestStreams(streams) }.getOrDefault(streams)
            streams = ranked
            laggedStreamIndices = emptySet()
            rebufferTimestamps = emptyList()
            isBenchmarking = false
            val bestIdx = ranked.indexOfFirst { !it.isWebViewFallback }.takeIf { it >= 0 } ?: 0
            activeStreamIndex = bestIdx
            val resume = if (isLiveSport) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
            prepareStream(ranked[bestIdx], resume)
            toastBadgeText = "Đã chọn server nhanh nhất: ${ranked[bestIdx].qualityLabel.ifBlank { ranked[bestIdx].name.take(28) }}"
        }
    }

    LaunchedEffect(scaleMode) {
        applyCurrentAspect(scaleMode, lastVideoSize)
    }

    // Anti-lag stall watchdog: auto-switches server if stuck buffering >= 7s continuously
    LaunchedEffect(isBuffering, activeStreamIndex, isResolving, isBenchmarking, errorMessage) {
        if (settings.autoSwitchOnLag && isBuffering && !isResolving && !isBenchmarking && errorMessage == null && streams.size > 1) {
            val timeoutMs = if (isLiveSport) 7_000L else 12_000L
            delay(timeoutMs)
            if (isBuffering && !isResolving && !isBenchmarking) {
                switchToNextHealthyServer("Server đứng hình (${timeoutMs / 1000}s) → Tự chuyển server mượt hơn!")
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastVideoSize = videoSize
                    playerView.post {
                        applyCurrentAspect(scaleMode, videoSize)
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                if (lastVideoSize.width > 0 && lastVideoSize.height > 0) {
                    playerView.post {
                        applyCurrentAspect(scaleMode, lastVideoSize)
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Runtime purple-tint guard: detect Dolby Vision Profile 5 on non-DV TVs and auto-switch stream!
                if (!settings.safeColorMode || deviceSupportsDv) return
                var hasPurpleRisk = false
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i) &&
                                VideoColorGuard.isPurpleRiskFormat(group.getTrackFormat(i), deviceSupportsDv)
                            ) {
                                hasPurpleRisk = true
                                break
                            }
                        }
                    }
                    if (hasPurpleRisk) break
                }

                if (hasPurpleRisk) {
                    val safeNextIdx = ((activeStreamIndex + 1) until streams.size).firstOrNull { idx ->
                        val s = streams[idx]
                        !VideoColorGuard.isPureDolbyVisionRisk(s.name) && !VideoColorGuard.isPureDolbyVisionRisk(s.url)
                    }
                    if (safeNextIdx != null) {
                        activeStreamIndex = safeNextIdx
                        val resume = exoPlayer.currentPosition.coerceAtLeast(pendingResumeMs)
                        toastBadgeText = "Phát hiện Dolby Vision (tím màu) → Đổi luồng màu chuẩn"
                        prepareStream(streams[safeNextIdx], resume)
                    } else if (!preferSoftwareDecoder) {
                        toastBadgeText = "Cảnh báo: Luồng Dolby Vision • Đang bật Fix màu"
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val nowBuffering = playbackState == Player.STATE_BUFFERING
                if (nowBuffering && !isBuffering && settings.autoSwitchOnLag && streams.size > 1 && !isResolving && !isBenchmarking) {
                    val now = System.currentTimeMillis()
                    val recent = (rebufferTimestamps + now).filter { now - it <= 25_000L }
                    rebufferTimestamps = recent
                    // 3+ rebuffer stutters within 25 seconds -> server is congested, auto-switch!
                    if (recent.size >= 3) {
                        if (switchToNextHealthyServer("Server giật liên tục → Tự chuyển server mượt hơn!")) {
                            return
                        }
                    }
                }
                isBuffering = nowBuffering
                if (playbackState == Player.STATE_ENDED) {
                    persistProgress()
                    val hasNext = episodeIndex + 1 < episodes.size
                    if (hasNext && settings.autoPlayNext) {
                        nextEpisodeCountdown = 5
                    } else {
                        showControls = true
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) persistProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                val nextIdx = activeStreamIndex + 1
                if (nextIdx < streams.size) {
                    activeStreamIndex = nextIdx
                    val resume = exoPlayer.currentPosition.coerceAtLeast(pendingResumeMs)
                    prepareStream(streams[nextIdx], resume)
                } else if (!preferSoftwareDecoder && error.errorCode in 4001..4005) {
                    // Hardware decoder rejected format (e.g. 10-bit H.264 / unusual profile): auto-fallback to SW
                    switchDecoderMode(useSoftware = true)
                } else {
                    isBuffering = false
                    errorMessage = "Không thể phát luồng video (${error.errorCodeName}). Nhấn 'Nguồn' để đổi server."
                    showControls = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            persistProgress()
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(serverIndex, episodeIndex) {
        val ep = currentEpisode ?: return@LaunchedEffect
        isResolving = true
        errorMessage = null
        nextEpisodeCountdown = -1
        selectedSubtitleIdx = 0
        laggedStreamIndices = emptySet()
        rebufferTimestamps = emptyList()
        lastVideoSize = VideoSize.UNKNOWN
        exoPlayer.stop()

        val dummyDetail = MovieDetail(
            item = payload.movie,
            servers = payload.servers,
            imdbId = payload.imdbId
        )
        val rawResolved = runCatching {
            app.repository.resolveStreams(ep, dummyDetail)
        }.getOrElse { emptyList() }

        // If NOT already tier-ordered by ShowBox, apply color-safety ordering on non-DV TVs
        val isShowBoxTiered = ep.slug == com.bmkf.tv.data.source.ShowBoxProvider.TIER_4K ||
            ep.slug == com.bmkf.tv.data.source.ShowBoxProvider.TIER_1080P
        val resolved = if (settings.safeColorMode && !isShowBoxTiered) {
            VideoColorGuard.sortStreamsForColorSafety(rawResolved, deviceSupportsDv)
        } else {
            rawResolved
        }

        streams = resolved
        isResolving = false

        if (resolved.isEmpty()) {
            errorMessage = "Không tìm thấy link phát cho ${ep.name}. Hãy thử chọn server khác."
            showControls = true
        } else {
            val firstNativeIdx = resolved.indexOfFirst { !it.isWebViewFallback }.takeIf { it >= 0 } ?: 0
            activeStreamIndex = firstNativeIdx
            val resume = pendingResumeMs
            pendingResumeMs = 0L
            prepareStream(resolved[firstNativeIdx], resume)
        }
    }

    // Quiet background progress saver every 15s (zero Compose recomposition)
    LaunchedEffect(isPlaying, serverIndex, episodeIndex, exoPlayer) {
        while (isPlaying) {
            delay(15_000L)
            persistProgress()
        }
    }

    LaunchedEffect(showControls, lastInteractionTick, activeDrawer, isPlaying) {
        if (showControls && activeDrawer == PlayerDrawer.NONE && isPlaying && errorMessage == null) {
            delay(4500L)
            showControls = false
        }
    }

    LaunchedEffect(showControls, activeDrawer) {
        if (showControls && activeDrawer == PlayerDrawer.NONE) {
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(toastBadgeText) {
        if (toastBadgeText != null) {
            delay(1800L)
            toastBadgeText = null
        }
    }

    LaunchedEffect(nextEpisodeCountdown) {
        if (nextEpisodeCountdown > 0) {
            delay(1000L)
            nextEpisodeCountdown -= 1
        } else if (nextEpisodeCountdown == 0) {
            nextEpisodeCountdown = -1
            if (episodeIndex + 1 < episodes.size) {
                episodeIndex += 1
            }
        }
    }

    fun bumpInteraction() {
        lastInteractionTick = System.currentTimeMillis()
    }

    fun seekBy(deltaMs: Long) {
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        val maxTarget = if (dur > 0L) dur else Long.MAX_VALUE / 2
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, maxTarget)
        exoPlayer.seekTo(target)
        val sec = deltaMs / 1000L
        toastBadgeText = if (sec > 0) "+${sec}s" else "${sec}s"
        bumpInteraction()
    }

    DisposableEffect(showControls, activeDrawer, isPlaying, episodes.size, episodeIndex, nextEpisodeCountdown, exoPlayer) {
        onRegisterKeyHandler { event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@onRegisterKeyHandler false
            bumpInteraction()

            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    when {
                        nextEpisodeCountdown >= 0 -> {
                            nextEpisodeCountdown = -1
                            true
                        }
                        activeDrawer != PlayerDrawer.NONE -> {
                            activeDrawer = PlayerDrawer.NONE
                            true
                        }
                        showControls && errorMessage == null -> {
                            showControls = false
                            true
                        }
                        else -> {
                            persistProgress()
                            onExit()
                            true
                        }
                    }
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    showControls = true
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exoPlayer.play()
                    showControls = true
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exoPlayer.pause()
                    showControls = true
                    true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(15_000L)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-15_000L)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (episodeIndex + 1 < episodes.size) {
                        persistProgress()
                        episodeIndex += 1
                    }
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (episodeIndex > 0) {
                        persistProgress()
                        episodeIndex -= 1
                    }
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (!showControls && activeDrawer == PlayerDrawer.NONE) {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        showControls = true
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!showControls && activeDrawer == PlayerDrawer.NONE) {
                        seekBy(-10_000L)
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!showControls && activeDrawer == PlayerDrawer.NONE) {
                        seekBy(10_000L)
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!showControls && activeDrawer == PlayerDrawer.NONE) {
                        showControls = true
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
        onDispose { }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isResolving || isBuffering || isBenchmarking) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = CinemaAccent,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            isBenchmarking -> "Đang đo tốc độ & chọn server mượt nhất..."
                            isResolving && isLiveSport -> "Đang đo tốc độ các server Sport TV..."
                            isResolving -> "Đang tìm nguồn phát tối ưu..."
                            else -> "Đang tải video..."
                        },
                        color = CinemaTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        toastBadgeText?.let { badge ->
            Surface(
                color = CinemaBackground.copy(alpha = 0.88f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CinemaGold.copy(alpha = 0.8f)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = badge,
                    color = CinemaTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }

        errorMessage?.let { err ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CinemaSurface.copy(alpha = 0.94f))
                    .border(1.dp, CinemaAccent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Lỗi phát video", color = CinemaAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = CinemaTextPrimary, fontSize = 14.sp)
                }
            }
        }

        if (nextEpisodeCountdown >= 0 && episodeIndex + 1 < episodes.size) {
            val nextEp = episodes.getOrNull(episodeIndex + 1)
            Surface(
                color = CinemaSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, CinemaAccent),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(36.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Tập tiếp theo sau ${nextEpisodeCountdown}s",
                        color = CinemaGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = nextEp?.name.orEmpty(),
                        color = CinemaTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlayerPillButton(
                            label = "Phát ngay",
                            accent = true,
                            onClick = {
                                nextEpisodeCountdown = -1
                                episodeIndex += 1
                            }
                        )
                        PlayerPillButton(
                            label = "Hủy",
                            onClick = { nextEpisodeCountdown = -1 }
                        )
                    }
                }
            }
        }

        if (showControls && activeDrawer == PlayerDrawer.NONE) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(TopHudScrim)
                        .padding(horizontal = 36.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        onClick = {
                            persistProgress()
                            onExit()
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val headerTitle = if (payload.movie.source == SourceId.SPORT_TV && !currentEpisode?.name.isNullOrBlank()) {
                            currentEpisode!!.name
                        } else {
                            payload.movie.title
                        }
                        Text(
                            text = headerTitle,
                            color = CinemaTextPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val activeStreamName = streams.getOrNull(activeStreamIndex)?.name.orEmpty()
                        val resTag = if (lastVideoSize.width > 0 && lastVideoSize.height > 0) {
                            "${lastVideoSize.width}×${lastVideoSize.height}"
                        } else null
                        val decTag = if (preferSoftwareDecoder) "SW Fix Màu" else "HW"
                        Text(
                            text = listOfNotNull(
                                currentEpisode?.name.takeIf { it != headerTitle },
                                currentServer?.serverName,
                                resTag,
                                aspectStatusBadge,
                                decTag,
                                activeStreamName.takeIf { it.isNotBlank() }
                            ).joinToString("  •  "),
                            color = CinemaTextMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = androidx.compose.ui.graphics.Color(payload.movie.source.accentHex).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(payload.movie.source.accentHex))
                    ) {
                        Text(
                            text = payload.movie.source.displayName,
                            color = CinemaTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(BottomHudScrim)
                        .padding(horizontal = 32.dp, vertical = 20.dp)
                ) {
                    PlayerProgressBar(exoPlayer = exoPlayer)

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (episodeIndex > 0) {
                                PlayerIconButton(
                                    icon = Icons.Default.SkipPrevious,
                                    contentDescription = "Tập trước",
                                    onClick = {
                                        persistProgress()
                                        episodeIndex -= 1
                                    }
                                )
                            }
                            PlayerIconButton(
                                icon = Icons.Default.FastRewind,
                                contentDescription = "Tua lùi 10s",
                                onClick = { seekBy(-10_000L) }
                            )
                            PlayerIconButton(
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Tạm dừng" else "Phát",
                                primary = true,
                                modifier = Modifier.focusRequester(playPauseFocusRequester),
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                            )
                            PlayerIconButton(
                                icon = Icons.Default.FastForward,
                                contentDescription = "Tua tới 10s",
                                onClick = { seekBy(10_000L) }
                            )
                            if (episodeIndex + 1 < episodes.size) {
                                PlayerIconButton(
                                    icon = Icons.Default.SkipNext,
                                    contentDescription = "Tập sau",
                                    onClick = {
                                        persistProgress()
                                        episodeIndex += 1
                                    }
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerChipAction(
                                icon = Icons.AutoMirrored.Filled.List,
                                label = "Tập (${episodes.size})",
                                onClick = { activeDrawer = PlayerDrawer.EPISODES }
                            )
                            PlayerChipAction(
                                icon = Icons.Default.Tune,
                                label = "Nguồn (${streams.size})",
                                onClick = { activeDrawer = PlayerDrawer.STREAMS }
                            )
                            PlayerChipAction(
                                icon = Icons.Default.ClosedCaption,
                                label = if (subtitlesEnabled) "Sub" else "Tắt Sub",
                                onClick = { activeDrawer = PlayerDrawer.SUBTITLES }
                            )
                            PlayerChipAction(
                                icon = Icons.Default.ColorLens,
                                label = if (preferSoftwareDecoder) "Fix màu: SW" else "Màu: HW",
                                onClick = { switchDecoderMode(!preferSoftwareDecoder) }
                            )
                            PlayerChipAction(
                                icon = Icons.Default.Speed,
                                label = "${playbackSpeed}x",
                                onClick = { activeDrawer = PlayerDrawer.SPEED }
                            )
                            PlayerChipAction(
                                icon = Icons.Default.AspectRatio,
                                label = scaleMode.label,
                                onClick = {
                                    val next = scaleMode.next()
                                    scaleMode = next
                                    val resolved = onApplyAspect(next, lastVideoSize)
                                    aspectStatusBadge = resolved.statusBadge
                                    toastBadgeText = "Màn hình: ${resolved.statusBadge}"
                                }
                            )
                        }
                    }
                }
            }
        }

        if (activeDrawer != PlayerDrawer.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(390.dp)
            ) {
                PlayerSideDrawer(
                    drawer = activeDrawer,
                    payload = payload,
                    serverIndex = serverIndex,
                    episodeIndex = episodeIndex,
                    streams = streams,
                    activeStreamIndex = activeStreamIndex,
                    subtitlesEnabled = subtitlesEnabled,
                    selectedSubtitleIdx = selectedSubtitleIdx,
                    playbackSpeed = playbackSpeed,
                    onSelectServer = { idx ->
                        persistProgress()
                        serverIndex = idx
                        episodeIndex = episodeIndex
                            .coerceAtMost((payload.servers.getOrNull(idx)?.episodes?.size ?: 1) - 1)
                            .coerceAtLeast(0)
                    },
                    onSelectEpisode = { idx ->
                        persistProgress()
                        episodeIndex = idx
                        activeDrawer = PlayerDrawer.NONE
                    },
                    onSelectStream = { idx ->
                        activeStreamIndex = idx
                        rebufferTimestamps = emptyList()
                        val resume = if (isLiveSport) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
                        streams.getOrNull(idx)?.let { prepareStream(it, resume) }
                        activeDrawer = PlayerDrawer.NONE
                    },
                    onBenchmarkStreams = {
                        activeDrawer = PlayerDrawer.NONE
                        rebenchmarkStreamsNow()
                    },
                    onToggleSubtitles = { enabled, subIdx ->
                        subtitlesEnabled = enabled
                        selectedSubtitleIdx = subIdx
                        playerView.subtitleView?.visibility = if (enabled) View.VISIBLE else View.GONE
                        val resume = exoPlayer.currentPosition.coerceAtLeast(0L)
                        streams.getOrNull(activeStreamIndex)?.let { prepareStream(it, resume) }
                        activeDrawer = PlayerDrawer.NONE
                    },
                    onSelectSpeed = { spd ->
                        playbackSpeed = spd
                        exoPlayer.playbackParameters = PlaybackParameters(spd)
                        activeDrawer = PlayerDrawer.NONE
                    },
                    onClose = { activeDrawer = PlayerDrawer.NONE }
                )
            }
        }
    }
}

@Composable
private fun PlayerProgressBar(exoPlayer: ExoPlayer) {
    var posMs by remember(exoPlayer) { mutableLongStateOf(exoPlayer.currentPosition.coerceAtLeast(0L)) }
    var durMs by remember(exoPlayer) { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }
    var bufMs by remember(exoPlayer) { mutableLongStateOf(exoPlayer.bufferedPosition.coerceAtLeast(0L)) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            posMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durMs = exoPlayer.duration.coerceAtLeast(0L)
            bufMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            delay(1000L)
        }
    }

    val progress = if (durMs > 0L) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f
    val buffered = if (durMs > 0L) (bufMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(ProgressBarShape)
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(buffered)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(CinemaAccent)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(posMs), color = CinemaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (durMs > 0L) formatTime(durMs) else "LIVE",
                color = if (durMs > 0L) CinemaTextMuted else CinemaAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PlayerSideDrawer(
    drawer: PlayerDrawer,
    payload: PlayerLaunchPayload,
    serverIndex: Int,
    episodeIndex: Int,
    streams: List<StreamSource>,
    activeStreamIndex: Int,
    subtitlesEnabled: Boolean,
    selectedSubtitleIdx: Int,
    playbackSpeed: Float,
    onSelectServer: (Int) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSelectStream: (Int) -> Unit,
    onBenchmarkStreams: () -> Unit,
    onToggleSubtitles: (Boolean, Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onClose: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(drawer) {
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaBackground.copy(alpha = 0.96f))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f))
            .padding(22.dp)
    ) {
        val heading = when (drawer) {
            PlayerDrawer.EPISODES -> "Chọn tập & Server"
            PlayerDrawer.STREAMS -> "Nguồn phát video"
            PlayerDrawer.SUBTITLES -> "Phụ đề"
            PlayerDrawer.SPEED -> "Tốc độ phát"
            PlayerDrawer.NONE -> ""
        }
        Text(heading, color = CinemaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        when (drawer) {
            PlayerDrawer.EPISODES -> {
                if (payload.servers.size > 1) {
                    Text("Server", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(160.dp)
                    ) {
                        itemsIndexed(payload.servers) { idx, srv ->
                            DrawerOptionRow(
                                title = srv.serverName,
                                subtitle = "${srv.episodes.size} tập",
                                selected = idx == serverIndex,
                                modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                onClick = { onSelectServer(idx) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                val eps = payload.servers.getOrNull(serverIndex)?.episodes.orEmpty()
                Text("Danh sách tập (${eps.size})", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(eps, key = { _, e -> e.id }) { idx, ep ->
                        DrawerOptionRow(
                            title = ep.name,
                            subtitle = if (idx == episodeIndex) "Đang phát" else null,
                            selected = idx == episodeIndex,
                            modifier = if (payload.servers.size <= 1 && idx == episodeIndex) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onSelectEpisode(idx) }
                        )
                    }
                }
            }

            PlayerDrawer.STREAMS -> {
                if (streams.size > 1) {
                    DrawerOptionRow(
                        title = "⚡ Đo tốc độ & Chọn server mượt nhất",
                        subtitle = "Tự kiểm tra ping + băng thông từ mạng TV của bạn",
                        selected = false,
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = onBenchmarkStreams
                    )
                    Spacer(Modifier.height(10.dp))
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(streams) { idx, stream ->
                        DrawerOptionRow(
                            title = stream.name,
                            subtitle = when {
                                stream.isWebViewFallback -> "Mở bằng trình phát Web"
                                stream.isHls -> "Media3 HLS Native"
                                else -> "Direct HTTP MKV / MP4 / TS"
                            },
                            selected = idx == activeStreamIndex,
                            modifier = if (streams.size <= 1 && idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onSelectStream(idx) }
                        )
                    }
                }
            }

            PlayerDrawer.SUBTITLES -> {
                val activeSubs = streams.getOrNull(activeStreamIndex)?.subtitles.orEmpty()
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        DrawerOptionRow(
                            title = "Tắt phụ đề",
                            selected = !subtitlesEnabled,
                            modifier = Modifier.focusRequester(firstFocus),
                            onClick = { onToggleSubtitles(false, 0) }
                        )
                    }
                    if (activeSubs.isEmpty()) {
                        item {
                            DrawerOptionRow(
                                title = "Phụ đề tích hợp trong luồng",
                                subtitle = "Tự động hiển thị nếu video có phụ đề",
                                selected = subtitlesEnabled,
                                onClick = { onToggleSubtitles(true, 0) }
                            )
                        }
                    } else {
                        itemsIndexed(activeSubs) { idx, sub ->
                            DrawerOptionRow(
                                title = sub.label,
                                subtitle = sub.language.uppercase(),
                                selected = subtitlesEnabled && idx == selectedSubtitleIdx,
                                onClick = { onToggleSubtitles(true, idx) }
                            )
                        }
                    }
                }
            }

            PlayerDrawer.SPEED -> {
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(speeds) { idx, spd ->
                        DrawerOptionRow(
                            title = if (spd == 1.0f) "1.0x (Chuẩn)" else "${spd}x",
                            selected = spd == playbackSpeed,
                            modifier = if (idx == 2) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onSelectSpeed(spd) }
                        )
                    }
                }
            }

            PlayerDrawer.NONE -> {}
        }

        Spacer(Modifier.height(10.dp))
        PlayerPillButton(label = "Đóng (BACK)", onClick = onClose)
    }
}

@Composable
private fun DrawerOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> CinemaAccent
        selected -> CinemaSurface
        else -> CinemaSurface.copy(alpha = 0.6f)
    }
    val borderColor = when {
        focused -> androidx.compose.ui.graphics.Color.White
        selected -> CinemaGold
        else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DrawerRowShape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, borderColor, DrawerRowShape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = CinemaTextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = if (focused) CinemaTextPrimary.copy(alpha = 0.85f) else CinemaTextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (primary) 48.dp else 40.dp
    val bg = when {
        focused -> CinemaAccent
        primary -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)
        else -> CinemaSurface.copy(alpha = 0.7f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                shape = CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = CinemaTextPrimary,
            modifier = Modifier.size(if (primary) 25.dp else 20.dp)
        )
    }
}

@Composable
private fun PlayerChipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(DrawerRowShape)
            .background(if (focused) CinemaAccent else CinemaSurface.copy(alpha = 0.8f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f),
                shape = DrawerRowShape
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = CinemaTextPrimary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = CinemaTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlayerPillButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(DrawerRowShape)
            .background(
                when {
                    focused -> CinemaGold
                    accent -> CinemaAccent
                    else -> CinemaSurface
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                shape = DrawerRowShape
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (focused) CinemaBackground else CinemaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
