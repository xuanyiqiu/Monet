package dev.monet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.ThemePreviewScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonetTheme { ThemePreviewScreen() }
        }
    }
}
