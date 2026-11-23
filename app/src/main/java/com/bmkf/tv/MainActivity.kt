package com.bmkf.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bmkf.tv.player.TvPlayerActivity
import com.bmkf.tv.ui.navigation.BmkfTvRoot
import com.bmkf.tv.ui.theme.BmkfTvTheme

class MainActivity : AppCompatActivity() {

    private var searchShortcutTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val app = application as BmkfTvApp

        setContent {
            BmkfTvTheme {
                BmkfTvRoot(
                    app = app,
                    searchShortcutTick = searchShortcutTick,
                    onLaunchPlayer = { payload ->
                        startActivity(TvPlayerActivity.createIntent(this, payload))
                    }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            searchShortcutTick++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
