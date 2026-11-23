package com.bmkf.tv.data.model

import androidx.compose.runtime.Immutable
import com.bmkf.tv.BuildConfig
import kotlinx.serialization.Serializable

@Immutable
@Serializable
enum class SourceId(
    val displayName: String,
    val shortLabel: String,
    val accentHex: Long,
    val description: String
) {
    KKPHIM(
        displayName = "KKPhim",
        shortLabel = "KK",
        accentHex = 0xFFE50914,
        description = "API công khai phimapi.com — HLS trực tiếp tốc độ cao"
    ),
    VSMOV(
        displayName = "VSMOV",
        shortLabel = "VS",
        accentHex = 0xFF3B82F6,
        description = "API công khai vsmov.com — Kho phim HD & phụ đề rời VTT"
    ),
    COBEPHIM(
        displayName = "CoBePhim",
        shortLabel = "CB",
        accentHex = 0xFFF5C518,
        description = "Nguồn cobephim.wiki — Đa server, Song ngữ, Thuyết minh"
    ),
    K20(
        displayName = "K20 Addon",
        shortLabel = "K20",
        accentHex = 0xFF10B981,
        description = "Stremio Addon sc.k-20.xyz — NguonC, STP, CLBPX, 4K & Live"
    ),
    SHOWBOX(
        displayName = "ShowBox",
        shortLabel = "SB",
        accentHex = 0xFFA855F7,
        description = "Stremio ShowBox 4K/1080P (FebBox) + Cinemeta & Phụ đề Việt"
    ),
    KHOPHIM(
        displayName = "KhoPhim",
        shortLabel = "KP",
        accentHex = 0xFF84CC16,
        description = "Nguồn khophim.top — Phim Bộ/Lẻ FHD & Publisher API"
    ),
    SPORT_TV(
        displayName = "Sport TV",
        shortLabel = "SPORT",
        accentHex = 0xFFFF6B00,
        description = "Nuvio Sports — Bóng đá, MMA trực tiếp, TV thể thao 24/7 & Xem lại"
    )
}

@Immutable
@Serializable
enum class MediaType {
    MOVIE,
    SERIES,
    TV
}

@Immutable
@Serializable
data class MovieItem(
    val source: SourceId,
    val id: String,
    val title: String,
    val originTitle: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val year: String = "",
    val quality: String = "",
    val lang: String = "",
    val episodeCurrent: String = "",
    val rating: String = "",
    val mediaType: MediaType = MediaType.MOVIE,
    val description: String = ""
) {
    val uniqueKey: String get() = "${source.name}:$id"
}

@Immutable
@Serializable
data class GenreItem(
    val slug: String,
    val name: String
)

@Immutable
data class CatalogTab(
    val id: String,
    val title: String,
    val mediaType: MediaType = MediaType.MOVIE,
    val genres: List<GenreItem> = emptyList()
)

@Immutable
data class HomeSection(
    val id: String,
    val title: String,
    val source: SourceId,
    val catalogId: String? = null,
    val movies: List<MovieItem>
)

@Immutable
data class PagedMovies(
    val items: List<MovieItem>,
    val page: Int,
    val hasMore: Boolean
)

@Immutable
@Serializable
data class EpisodeItem(
    val id: String,
    val name: String,
    val slug: String = "",
    val season: Int = 1,
    val episodeNumber: Int = 1,
    val serverName: String = "Mặc định",
    val streamUrl: String = "",
    val embedUrl: String = "",
    val k20VideoId: String = "",
    val coBeEpisodeId: Long = 0L
)

@Immutable
@Serializable
data class EpisodeServer(
    val serverName: String,
    val season: Int = 1,
    val episodes: List<EpisodeItem>
)

@Immutable
@Serializable
data class MovieDetail(
    val item: MovieItem,
    val description: String = "",
    val runtime: String = "",
    val status: String = "",
    val episodeTotal: String = "",
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val servers: List<EpisodeServer> = emptyList(),
    val imdbId: String = "",
    val tmdbId: String = ""
)

@Immutable
@Serializable
data class SubtitleTrack(
    val url: String,
    val language: String = "vi",
    val label: String = "Tiếng Việt",
    val mimeType: String = ""
)

@Immutable
@Serializable
data class StreamSource(
    val name: String,
    val url: String,
    val isHls: Boolean = true,
    val isWebViewFallback: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val qualityLabel: String = ""
)

@Immutable
@Serializable
data class WatchHistoryEntry(
    val movie: MovieItem,
    val episodeId: String,
    val episodeName: String,
    val serverName: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Immutable
@Serializable
enum class VideoScaleMode(val label: String) {
    AUTO("Tự động"),
    FIT("Gốc"),
    ZOOM("Zoom đầy"),
    FILL("Đầy 16:9"),
    FORCE_16_9("Ép 16:9");

    fun next(): VideoScaleMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

val DEFAULT_SHOWBOX_MANIFEST_URL: String = BuildConfig.DEFAULT_SHOWBOX_MANIFEST_URL

const val DEFAULT_SPORT_TV_URL =
    "https://nuviosports.xyz/eyJ0aW1lem9uZSI6IkFzaWEvU2FpZ29uIiwic3BvcnRzIjoiZm9vdGJhbGwsbW1hIn0/collections.json"

val DEFAULT_HIGHFLY_SPORT_URL: String = BuildConfig.DEFAULT_HIGHFLY_SPORT_URL

const val DEFAULT_XOILAC_URL = "https://xoilacz.ca"

const val DEFAULT_GAVANG_URL = "https://gavang69.com/"

const val DEFAULT_XOICHE_URL = "https://xoiche.live/"

const val DEFAULT_COLATV_URL = "https://colatv111.live/"

const val DEFAULT_KHOPHIM_API_BASE = "https://kp.streamhub.solutions/publisher"

const val DEFAULT_KHOPHIM_WEB_BASE = "https://khophim.top"

val DEFAULT_KHOPHIM_API_KEY: String = BuildConfig.DEFAULT_KHOPHIM_API_KEY

@Immutable
@Serializable
data class AppSettings(
    val schemaVersion: Int = 8,
    val enabledSources: Set<SourceId> = setOf(
        SourceId.KKPHIM,
        SourceId.VSMOV,
        SourceId.COBEPHIM,
        SourceId.K20,
        SourceId.SHOWBOX,
        SourceId.KHOPHIM,
        SourceId.SPORT_TV
    ),
    val kkPhimBaseUrl: String = "https://phimapi.com",
    val vsMovBaseUrl: String = "https://vsmov.com/api",
    val coBeApiBaseUrl: String = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1",
    val coBeWebBaseUrl: String = "https://cobephim.wiki",
    val k20ManifestUrl: String = "https://sc.k-20.xyz/manifest.json",
    val showBoxManifestUrl: String = DEFAULT_SHOWBOX_MANIFEST_URL,
    val khoPhimApiBaseUrl: String = DEFAULT_KHOPHIM_API_BASE,
    val khoPhimApiKey: String = DEFAULT_KHOPHIM_API_KEY,
    val khoPhimWebBaseUrl: String = DEFAULT_KHOPHIM_WEB_BASE,
    val sportTvUrl: String = DEFAULT_SPORT_TV_URL,
    val highFlySportUrl: String = DEFAULT_HIGHFLY_SPORT_URL,
    val xoiLacUrl: String = DEFAULT_XOILAC_URL,
    val gaVangUrl: String = DEFAULT_GAVANG_URL,
    val xoiCheUrl: String = DEFAULT_XOICHE_URL,
    val colaTvUrl: String = DEFAULT_COLATV_URL,
    val autoPlayNext: Boolean = true,
    val subtitleEnabled: Boolean = true,
    val subtitleTextScale: Float = 1.0f,
    val defaultScaleMode: VideoScaleMode = VideoScaleMode.AUTO,
    val safeColorMode: Boolean = true,
    val autoSwitchOnLag: Boolean = true
)

@Immutable
@Serializable
data class PlayerLaunchPayload(
    val movie: MovieItem,
    val servers: List<EpisodeServer>,
    val initialServerIndex: Int = 0,
    val initialEpisodeIndex: Int = 0,
    val resumePositionMs: Long = 0L,
    val imdbId: String = ""
)
