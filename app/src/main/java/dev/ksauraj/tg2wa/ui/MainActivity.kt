package dev.ksauraj.tg2wa.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF5CC3F5), secondary = Color(0xFF4BE08A))
            } else {
                lightColorScheme(primary = Color(0xFF1C93E3), secondary = Color(0xFF1DA851))
            }
            MaterialTheme(colorScheme = scheme) {
                AppRoot(vm, onSecureChanged = ::setSecure)
            }
        }
    }

    /** Block screenshots / recents thumbnails while login codes and passwords are on screen. */
    private fun setSecure(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
