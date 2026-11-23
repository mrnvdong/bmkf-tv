package com.bmkf.tv.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.bmkf.tv.data.model.AppSettings
import com.bmkf.tv.data.model.DEFAULT_COLATV_URL
import com.bmkf.tv.data.model.DEFAULT_GAVANG_URL
import com.bmkf.tv.data.model.DEFAULT_HIGHFLY_SPORT_URL
import com.bmkf.tv.data.model.DEFAULT_KHOPHIM_API_BASE
import com.bmkf.tv.data.model.DEFAULT_KHOPHIM_WEB_BASE
import com.bmkf.tv.data.model.DEFAULT_SHOWBOX_MANIFEST_URL
import com.bmkf.tv.data.model.DEFAULT_SPORT_TV_URL
import com.bmkf.tv.data.model.DEFAULT_XOICHE_URL
import com.bmkf.tv.data.model.DEFAULT_XOILAC_URL
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.WatchHistoryEntry
import com.bmkf.tv.data.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class LocalStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Serialize & persist JSON off the Main UI thread so TV scrolling & playback never stutter
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<List<MovieItem>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<WatchHistoryEntry>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun isFavorite(movie: MovieItem): Boolean {
        return _favorites.value.any { it.uniqueKey == movie.uniqueKey }
    }

    fun toggleFavorite(movie: MovieItem): Boolean {
        val current = _favorites.value.toMutableList()
        val idx = current.indexOfFirst { it.uniqueKey == movie.uniqueKey }
        val added = if (idx >= 0) {
            current.removeAt(idx)
            false
        } else {
            current.add(0, movie)
            true
        }
        val snapshot = current.toList()
        _favorites.value = snapshot
        ioScope.launch {
            prefs.edit().putString(KEY_FAVORITES, HttpClient.json.encodeToString(snapshot)).apply()
        }
        return added
    }

    fun saveWatchProgress(entry: WatchHistoryEntry) {
        val current = _history.value.toMutableList()
        current.removeAll { it.movie.uniqueKey == entry.movie.uniqueKey }
        current.add(0, entry.copy(updatedAt = System.currentTimeMillis()))
        val trimmed = current.take(MAX_HISTORY)
        _history.value = trimmed
        ioScope.launch {
            prefs.edit().putString(KEY_HISTORY, HttpClient.json.encodeToString(trimmed)).apply()
        }
    }

    fun getWatchProgress(movieKey: String): WatchHistoryEntry? {
        return _history.value.firstOrNull { it.movie.uniqueKey == movieKey }
    }

    fun removeFromHistory(movieKey: String) {
        val updated = _history.value.filterNot { it.movie.uniqueKey == movieKey }
        _history.value = updated
        ioScope.launch {
            prefs.edit().putString(KEY_HISTORY, HttpClient.json.encodeToString(updated)).apply()
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        ioScope.launch {
            prefs.edit().remove(KEY_HISTORY).apply()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        ioScope.launch {
            prefs.edit().putString(KEY_SETTINGS, HttpClient.json.encodeToString(updated)).apply()
        }
    }

    fun resetSettings() {
        val defaults = AppSettings()
        _settings.value = defaults
        ioScope.launch {
            prefs.edit().putString(KEY_SETTINGS, HttpClient.json.encodeToString(defaults)).apply()
        }
    }

    private fun loadFavorites(): List<MovieItem> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching { HttpClient.json.decodeFromString<List<MovieItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadHistory(): List<WatchHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { HttpClient.json.decodeFromString<List<WatchHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        val decoded = runCatching { HttpClient.json.decodeFromString<AppSettings>(raw) }
            .getOrDefault(AppSettings())
        // Migrate older saved settings so newly added sources (ShowBox in v2, Sport TV in v3) enable automatically
        val savedVersion = if (raw.contains("schemaVersion")) decoded.schemaVersion else 1
        return if (savedVersion < 8) {
            var nextSources = decoded.enabledSources
            if (savedVersion < 2) nextSources = nextSources + SourceId.SHOWBOX
            if (savedVersion < 3) nextSources = nextSources + SourceId.SPORT_TV
            if (savedVersion < 8) nextSources = nextSources + SourceId.KHOPHIM
            val migrated = decoded.copy(
                schemaVersion = 8,
                enabledSources = nextSources,
                showBoxManifestUrl = decoded.showBoxManifestUrl.ifBlank { DEFAULT_SHOWBOX_MANIFEST_URL },
                khoPhimApiBaseUrl = decoded.khoPhimApiBaseUrl.ifBlank { DEFAULT_KHOPHIM_API_BASE },
                khoPhimWebBaseUrl = decoded.khoPhimWebBaseUrl.ifBlank { DEFAULT_KHOPHIM_WEB_BASE },
                sportTvUrl = decoded.sportTvUrl.ifBlank { DEFAULT_SPORT_TV_URL },
                highFlySportUrl = decoded.highFlySportUrl.ifBlank { DEFAULT_HIGHFLY_SPORT_URL },
                xoiLacUrl = decoded.xoiLacUrl.ifBlank { DEFAULT_XOILAC_URL },
                gaVangUrl = decoded.gaVangUrl.ifBlank { DEFAULT_GAVANG_URL },
                xoiCheUrl = decoded.xoiCheUrl.ifBlank { DEFAULT_XOICHE_URL },
                colaTvUrl = decoded.colaTvUrl.ifBlank { DEFAULT_COLATV_URL }
            )
            ioScope.launch {
                prefs.edit().putString(KEY_SETTINGS, HttpClient.json.encodeToString(migrated)).apply()
            }
            migrated
        } else {
            decoded
        }
    }

    companion object {
        private const val PREFS_NAME = "bmkf_tv_storage"
        private const val KEY_FAVORITES = "favorites_v1"
        private const val KEY_HISTORY = "history_v1"
        private const val KEY_SETTINGS = "settings_v1"
        private const val MAX_HISTORY = 40
    }
}
