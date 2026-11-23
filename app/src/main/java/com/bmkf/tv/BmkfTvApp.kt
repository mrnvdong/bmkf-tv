package com.bmkf.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bmkf.tv.data.network.HttpClient
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.data.storage.LocalStorage

class BmkfTvApp : Application(), ImageLoaderFactory {

    lateinit var storage: LocalStorage
        private set

    lateinit var repository: MovieRepository
        private set

    override fun onCreate() {
        super.onCreate()
        storage = LocalStorage(this)
        repository = MovieRepository(storage)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(HttpClient.imageOkHttp)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.14)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Coil.imageLoader(this).memoryCache?.clear()
            repository.trimCaches()
        }
    }
}
