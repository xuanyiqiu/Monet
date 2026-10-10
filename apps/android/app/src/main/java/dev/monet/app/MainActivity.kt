package dev.monet.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.monet.app.feature.detail.DetailSheet
import dev.monet.app.feature.home.HomeScreen
import dev.monet.app.feature.home.MainViewModel
import dev.monet.app.feature.home.incomingText
import dev.monet.app.ui.theme.MonetTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only the first launch carries the intent; after rotation the ViewModel already has the text.
        if (savedInstanceState == null) viewModel.explain(intent.incomingText())
        setContent {
            MonetTheme {
                val sheetText by viewModel.sheetText.collectAsStateWithLifecycle()
                HomeScreen(onExplain = viewModel::explain)
                sheetText?.let { DetailSheet(text = it, onDismiss = viewModel::dismiss) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.explain(intent.incomingText())
    }
}
