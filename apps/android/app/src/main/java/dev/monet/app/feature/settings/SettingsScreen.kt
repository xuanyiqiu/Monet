package dev.monet.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.Lang
import dev.monet.app.core.settings.PinyinStyle
import dev.monet.app.core.settings.Settings
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class SettingsActions(
    val onBack: () -> Unit,
    val onTargetLang: (Lang) -> Unit,
    val onPinyinStyle: (PinyinStyle) -> Unit,
    val onLinesPerStep: (Int) -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onUseFakeApi: (Boolean) -> Unit,
    val onApiBaseUrl: (String) -> Unit,
    val onAuthToken: (String) -> Unit,
    val onReplayIntro: () -> Unit,
)

@Composable
fun SettingsScreen(settings: Settings, actions: SettingsActions, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val t = Monet.type
    Column(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
            Text("Settings", style = t.title, color = c.ink)
        }

        Group("TRANSLATE INTO") {
            Choices(
                options = listOf(Lang.ZH to "中文", Lang.ES to "Español", Lang.EN to "English"),
                selected = settings.targetLang,
                onSelect = actions.onTargetLang,
            )
        }
        Group("PINYIN") {
            Choices(
                options = listOf(
                    PinyinStyle.TONE_COLORS to "Tone colors",
                    PinyinStyle.TONE_MARKS to "Tone marks",
                    PinyinStyle.NUMBERED to "Numbers",
                    PinyinStyle.OFF to "Off",
                ),
                selected = settings.pinyinStyle,
                onSelect = actions.onPinyinStyle,
            )
        }
        Group("LINES PER STEP") {
            Stepper(settings.linesPerStep, 1..5, actions.onLinesPerStep)
        }
        Group("THEME") {
            Choices(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = settings.themeMode,
                onSelect = actions.onThemeMode,
            )
        }

        HorizontalDivider(color = c.hairline)
        Group("DEVELOPER") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Use sample data (offline)", style = t.body, color = c.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.useFakeApi,
                    onCheckedChange = actions.onUseFakeApi,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = c.seal,
                        checkedThumbColor = c.paper,
                        uncheckedTrackColor = c.surfaceSunken,
                        uncheckedThumbColor = c.inkFaint,
                        uncheckedBorderColor = c.hairline,
                    ),
                )
            }
            if (!settings.useFakeApi) {
                CommitField("Server URL", settings.apiBaseUrl, actions.onApiBaseUrl)
                CommitField("Access token", settings.authToken, actions.onAuthToken)
            }
            TextButton(onClick = actions.onReplayIntro) {
                Text("Replay introduction", style = t.label, color = c.seal)
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text(title, style = Monet.type.label, color = Monet.colors.inkMuted)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        options.forEach { (value, label) -> Chip(label, value == selected) { onSelect(value) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Monet.colors
    Text(
        label,
        style = Monet.type.body,
        color = if (selected) c.seal else c.ink,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) c.sealSoft else Color.Transparent, RoundedCornerShape(Radius.sm))
            .border(1.dp, if (selected) c.seal else c.hairline, RoundedCornerShape(Radius.sm))
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    )
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val c = Monet.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        TextButton(
            onClick = { onChange(value - 1) },
            enabled = value > range.first,
            modifier = Modifier.semantics { contentDescription = "Fewer lines" },
        ) { Text("–", style = Monet.type.title, color = if (value > range.first) c.ink else c.inkFaint) }
        Text("$value", style = Monet.type.title, color = c.ink)
        TextButton(
            onClick = { onChange(value + 1) },
            enabled = value < range.last,
            modifier = Modifier.semantics { contentDescription = "More lines" },
        ) { Text("+", style = Monet.type.title, color = if (value < range.last) c.ink else c.inkFaint) }
    }
}

/** Text field persisted on every edit; the local copy keeps the cursor stable while DataStore catches up. */
@Composable
private fun CommitField(label: String, value: String, onCommit: (String) -> Unit) {
    val c = Monet.colors
    var text by remember(value) { mutableStateOf(value) }
    TextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label, style = Monet.type.label) },
        singleLine = true,
        textStyle = Monet.type.body.copy(color = c.ink),
        shape = RoundedCornerShape(Radius.sm),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceSunken,
            unfocusedContainerColor = c.surfaceSunken,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = c.seal,
            focusedLabelColor = c.inkMuted,
            unfocusedLabelColor = c.inkMuted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val previewActions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "Settings light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsLight() = MonetTheme(darkTheme = false) { SettingsScreen(Settings(useFakeApi = false), previewActions) }

@Preview(name = "Settings dark", showBackground = true, widthDp = 360, heightDp = 780, backgroundColor = 0xFF151412)
@Composable
private fun SettingsDark() = MonetTheme(darkTheme = true) { SettingsScreen(Settings(), previewActions) }
