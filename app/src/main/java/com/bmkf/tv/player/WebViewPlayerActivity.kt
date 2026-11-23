package com.bmkf.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bmkf.tv.data.network.HttpClient
import java.net.URI

/**
 * Fullscreen hardware-accelerated WebView player for official signed embed streams (e.g. KhoPhim).
 * Includes TV D-pad remote controls (OK play/pause, Left/Right seek ±10s, BACK exit) and auto-start.
 */
class WebViewPlayerActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var hintBanner: TextView? = null
    private val hideHintRunnable = Runnable { hintBanner?.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val allowedHost = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val wv = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = HttpClient.DEFAULT_USER_AGENT

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    if (!(target.startsWith("http://") || target.startsWith("https://"))) return true
                    // Block pop-up ad redirects outside the embed player domain
                    if (allowedHost.isNotBlank()) {
                        val targetHost = runCatching { URI(target).host.orEmpty().lowercase() }.getOrDefault("")
                        if (targetHost.isNotBlank() && targetHost != allowedHost && !targetHost.endsWith(".$allowedHost")) {
                            return true
                        }
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    // Auto-click play overlay button on Android TV (no mouse cursor) and fit video cleanly
                    view?.postDelayed({
                        view.evaluateJavascript(
                            """
                            (function(){
                              var v = document.querySelector('video');
                              if (v) { v.style.objectFit = 'contain'; v.muted = false; }
                              var btn = document.querySelector('#play-btn, #play-overlay, .vjs-big-play-button, button[aria-label*="Play"]');
                              if (btn && btn.offsetParent !== null) { btn.click(); }
                              if (v && v.paused) { v.play().catch(function(){}); }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }, 900L)
                }
            }
        }
        webView = wv
        container.addView(wv)

        // Subtle auto-hiding banner for TV remote guidance & seek feedback
        val hintView = TextView(this).apply {
            text = "$title  •  OK: Phát/Dừng  •  ◄/►: Tua ±10s  •  BACK: Thoát"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC0B0D14.toInt())
            setPadding(36, 18, 36, 18)
            textSize = 14f
        }
        hintBanner = hintView
        container.addView(
            hintView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(32, 24, 32, 24)
            }
        )
        hintView.postDelayed(hideHintRunnable, 6000L)

        setContentView(container)

        val headers = mutableMapOf<String, String>()
        if (referer.isNotBlank()) headers["Referer"] = referer
        if (url.isNotBlank()) {
            wv.loadUrl(url, headers)
        }
    }

    private fun showBanner(message: String) {
        val banner = hintBanner ?: return
        banner.removeCallbacks(hideHintRunnable)
        banner.text = message
        banner.visibility = View.VISIBLE
        banner.postDelayed(hideHintRunnable, 2400L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    webView?.evaluateJavascript(
                        """
                        (function(){
                          var overlay = document.querySelector('#play-overlay');
                          var btn = document.querySelector('#play-btn');
                          if (overlay && !overlay.classList.contains('hidden') && btn) {
                            btn.click(); return 'Đang phát...';
                          }
                          var v = document.querySelector('video');
                          if (!v) return '';
                          if (v.paused) { v.play().catch(function(){}); return '▶ Đang phát'; }
                          else { v.pause(); return '⏸ Tạm dừng'; }
                        })();
                        """.trimIndent()
                    ) { result ->
                        val msg = result?.trim('"').orEmpty()
                        if (msg.isNotBlank() && msg != "null") showBanner(msg)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekRelative(10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekRelative(-10)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekRelative(deltaSec: Int) {
        val sign = if (deltaSec > 0) "+${deltaSec}s" else "${deltaSec}s"
        webView?.evaluateJavascript(
            """
            (function(){
              var v = document.querySelector('video');
              if (!v) return '';
              var next = Math.max(0, (v.currentTime || 0) + ($deltaSec));
              if (isFinite(v.duration) && v.duration > 0) next = Math.min(v.duration, next);
              v.currentTime = next;
              function fmt(s){
                s = Math.floor(s || 0);
                var h = Math.floor(s/3600), m = Math.floor((s%3600)/60), sec = s%60;
                return (h>0 ? h+':' : '') + (m<10?'0':'')+m + ':' + (sec<10?'0':'')+sec;
              }
              return fmt(next) + (isFinite(v.duration) && v.duration > 0 ? ' / ' + fmt(v.duration) : '');
            })();
            """.trimIndent()
        ) { result ->
            val ts = result?.trim('"').orEmpty()
            if (ts.isNotBlank() && ts != "null") showBanner("Tua $sign  •  $ts")
        }
    }

    override fun onDestroy() {
        hintBanner?.removeCallbacks(hideHintRunnable)
        hintBanner = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
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
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_REFERER = "extra_referer"
        private const val EXTRA_TITLE = "extra_title"

        fun createIntent(context: Context, url: String, referer: String, title: String): Intent {
            return Intent(context, WebViewPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
}
