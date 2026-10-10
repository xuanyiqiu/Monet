package dev.monet.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

@Composable
fun HomeScreen(onExplain: (String) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val t = Monet.type
    var field by rememberSaveable { mutableStateOf("") }
    val canExplain = normalizeIncoming(field) != null

    Column(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x6),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Monet", style = t.display, color = c.ink)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = c.inkMuted)
            }
        }
        Text(
            "Paste or type text, or share it from any app.",
            style = t.body,
            color = c.inkMuted,
        )
        TextField(
            value = field,
            onValueChange = { field = it.take(MAX_TEXT_LENGTH) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            textStyle = t.reading.copy(color = c.ink),
            placeholder = { Text("Text to explain", style = t.reading, color = c.inkFaint) },
            shape = RoundedCornerShape(Radius.sm),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceSunken,
                unfocusedContainerColor = c.surfaceSunken,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = c.seal,
            ),
        )
        Button(
            onClick = {
                normalizeIncoming(field)?.let {
                    onExplain(it)
                }
            },
            enabled = canExplain,
            shape = RoundedCornerShape(Radius.sm),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.seal,
                contentColor = c.paper,
                disabledContainerColor = c.surfaceSunken,
                disabledContentColor = c.inkFaint,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Explain", style = t.label)
        }
    }
}

@Preview(name = "Home light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeLight() = MonetTheme(darkTheme = false) { HomeScreen(onExplain = {}, onOpenSettings = {}) }

@Preview(name = "Home dark", showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF151412)
@Composable
private fun HomeDark() = MonetTheme(darkTheme = true) { HomeScreen(onExplain = {}, onOpenSettings = {}) }
