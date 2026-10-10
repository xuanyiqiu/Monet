package dev.monet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.monet.app.feature.detail.DetailSheet
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Temporary entry point until Home (F-04): `adb shell am start -n dev.monet.app/.MainActivity --es text "..."`.
        val initialText = intent.getStringExtra("text")
        setContent {
            MonetTheme {
                var text by rememberSaveable { mutableStateOf(initialText) }
                Box(Modifier.fillMaxSize().background(Monet.colors.paper))
                text?.let { DetailSheet(text = it, onDismiss = { text = null }) }
            }
        }
    }
}
