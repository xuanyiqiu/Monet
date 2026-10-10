package dev.monet.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Visual check of tokens: palette swatches plus every type role. */
@Composable
fun ThemePreviewScreen() {
    val c = Monet.colors
    val t = Monet.type
    Column(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x8),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Text("Monet", style = t.display, color = c.ink)
        Text("MEANING", style = t.label, color = c.inkMuted)
        Text("Ink on paper, with one seal.", style = t.reading, color = c.ink)
        Text("画蛇添足", style = t.hanzi, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x4)) {
            Text("huà", style = t.ruby, color = c.tone4)
            Text("shé", style = t.ruby, color = c.tone2)
            Text("tiān", style = t.ruby, color = c.tone1)
            Text("zú", style = t.ruby, color = c.tone2)
        }
        Text("/xwa.ˈsar/", style = t.ipa, color = c.inkMuted)
        Text("Body text in the interface sans.", style = t.body, color = c.inkMuted)
        HorizontalDivider(color = c.hairline)
        Surface(
            color = c.surface,
            shape = RoundedCornerShape(Radius.md),
            border = BorderStroke(1.dp, c.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                Text("Card on surface", style = t.title, color = c.ink)
                Text("Hairline border, no shadow.", style = t.body, color = c.inkFaint)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(c.seal, c.sealSoft, c.indigo, c.success, c.danger, c.surfaceSunken, c.inkFaint).forEach {
                Box(Modifier.size(36.dp).background(it, RoundedCornerShape(Radius.xs)))
            }
        }
    }
}


@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ThemePreviewLight() = MonetTheme(darkTheme = false) { ThemePreviewScreen() }

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF151412)
@Composable
private fun ThemePreviewDark() = MonetTheme(darkTheme = true) { ThemePreviewScreen() }
