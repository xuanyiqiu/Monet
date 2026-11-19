package dev.monet.app.feature.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.MonetApp
import dev.monet.app.core.network.Lang
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.Radius

/** Bottom sheet that explains [text]. State is keyed by text so a new selection restarts loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MonetApp).container
    val viewModel: DetailViewModel = viewModel(
        key = "detail:$text",
        factory = viewModelFactory {
            initializer { DetailViewModel(container.api, container.settingsRepository, text) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    LaunchedEffect(speaker) { container.settingsRepository.settings.collect { speaker.speed = it.ttsSpeed } }

    val actions = remember(text, state) { buildDetailActions(context, speaker, text, state, onRetry = viewModel::load) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Monet.colors.paper,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        tonalElevation = 0.dp,
    ) {
        DetailContent(state, actions, Modifier)
    }
}

/**
 * The sheet's actions for [text] in its current [state]. Shared by the in-app sheet and the one the
 * Lens draws over other apps; [afterShare] lets the overlay get out of the way of the share dialog.
 */
internal fun buildDetailActions(
    context: Context,
    speaker: Speaker,
    text: String,
    state: DetailUiState,
    onRetry: () -> Unit,
    afterShare: () -> Unit = {},
): DetailActions {
    val ready = state as? DetailUiState.Ready
    return DetailActions(
        onRetry = onRetry,
        onSpeak = { slow ->
            val ok = speaker.speak(text, ready?.annotation?.sourceLang ?: guessLang(text), slow)
            if (!ok) Toast.makeText(context, "No voice available for this language", Toast.LENGTH_SHORT).show()
        },
        onCopy = { copy(context, shareText(text, ready)) },
        onShare = {
            share(context, shareText(text, ready))
            afterShare()
        },
    )
}

private fun guessLang(text: String): Lang = if (text.any { it.code in 0x4E00..0x9FFF }) Lang.ZH else Lang.EN

private fun shareText(source: String, ready: DetailUiState.Ready?): String {
    val translation = ready?.annotation?.translation.orEmpty()
    return if (translation.isEmpty()) source else "$source\n$translation"
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Monet", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
