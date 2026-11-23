package com.bmkf.tv.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object HttpClient {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val HTML_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_P_END_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val apiDispatcher = Dispatcher().apply {
        maxRequests = 12
        maxRequestsPerHost = 4
    }

    private val imageDispatcher = Dispatcher().apply {
        // Keep image decoding/download concurrency gentle for low-end TV CPUs
        maxRequests = 8
        maxRequestsPerHost = 3
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(apiDispatcher)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            if (original.header("Accept") == null) {
                builder.header("Accept", "*/*")
            }
            chain.proceed(builder.build())
        }
        .build()

    /**
     * Separate lightweight OkHttp client for poster/backdrop image loading so image bursts
     * never block API JSON requests or video segment downloads.
     */
    val imageOkHttp: OkHttpClient = okHttp.newBuilder()
        .dispatcher(imageDispatcher)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 90, TimeUnit.SECONDS))
        .build()

    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        okHttp.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            response.body?.string() ?: ""
        }
    }

    fun stripHtml(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(HTML_BR_REGEX, "\n")
            .replace(HTML_P_END_REGEX, "\n")
            .replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }
}
