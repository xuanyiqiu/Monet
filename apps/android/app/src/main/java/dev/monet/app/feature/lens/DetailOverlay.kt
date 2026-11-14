package dev.monet.app.feature.lens

import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.monet.app.feature.detail.DetailActions
import dev.monet.app.feature.detail.DetailContent
import dev.monet.app.feature.detail.DetailController
import dev.monet.app.feature.detail.DetailUiState
import dev.monet.app.feature.detail.Speaker
import dev.monet.app.feature.detail.buildDetailActions
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

/**
 * The Detail sheet drawn over another app, so tapping a translation does not pull the user out of
 * what they are reading. A Material bottom sheet needs an Activity window; this is a plain overlay
 * window with a scrim and a bottom-aligned surface.
 */
internal class DetailOverlay(
    private val host: LensHost,
    private val onOpenChanged: (open: Boolean) -> Unit,
) {
    private var view: View? = null
    private var controller: DetailController? = null
    private var speaker: Speaker? = null

    val isOpen: Boolean get() = view != null

    fun open(text: String) {
        val wasOpen = isOpen
        dismiss(notify = false)
        val detail = DetailController(host.scope, host.container.api, host.container.settingsRepository, text)
        val voice = Speaker(host.service)
        controller = detail
        speaker = voice

        val density = host.service.resources.displayMetrics.density
        val bottomInset = (host.navigationBarPx() / density).dp
        val topInset = (host.statusBarPx() / density).dp
        val compose = host.composeView {
            val state by detail.state.collectAsState()
            val actions = remember(state) {
                buildDetailActions(host.service, voice, text, state, onRetry = detail::load, afterShare = { close() })
            }
            DetailOverlaySheet(text, state, actions, topInset, bottomInset, onDismiss = { close() })
        }
        // Focusable, so the system Back button reaches us and closes the sheet.
        val root = object : FrameLayout(host.service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        host.attachOwners(root)
        root.addView(compose)
        view = root
        host.add(
            root,
            host.params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = true),
        )
        if (!wasOpen) onOpenChanged(true)
    }

    fun close() = dismiss(notify = true)

    private fun dismiss(notify: Boolean) {
        val wasOpen = isOpen
        host.remove(view)
        view = null
        controller?.cancel()
        controller = null
        speaker?.shutdown()
        speaker = null
        if (notify && wasOpen) onOpenChanged(false)
    }
}

@Composable
private fun DetailOverlaySheet(
    text: String,
    state: DetailUiState,
    actions: DetailActions,
    topInset: Dp,
    bottomInset: Dp,
    onDismiss: () -> Unit,
) {
    val c = Monet.colors
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(280)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = c.paper,
                shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
                border = BorderStroke(1.dp, c.hairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (maxHeight - topInset) * 0.88f)
                    // Taps on the sheet must not fall through to the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(Modifier.padding(bottom = bottomInset)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.x2, top = Spacing.x2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                    ) {
                        Text(
                            text,
                            style = Monet.type.body,
                            color = c.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close details" }) {
                            Text("Close", style = Monet.type.label, color = c.seal)
                        }
                    }
                    DetailContent(state, actions)
                }
            }
        }
    }
}
