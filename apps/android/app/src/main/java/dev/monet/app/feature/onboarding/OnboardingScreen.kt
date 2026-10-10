package dev.monet.app.feature.onboarding

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.Lang
import dev.monet.app.feature.lens.canDrawOverlays
import dev.monet.app.feature.lens.overlaySettingsIntent
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class OnboardingActions(
    val onTargetLang: (Lang) -> Unit,
    val onRequestOverlayPermission: () -> Unit,
    val onTry: (String) -> Unit,
    val onFinish: () -> Unit,
)

/** Wires the permission check to the lifecycle: coming back from system settings refreshes it. */
@Composable
fun OnboardingRoute(targetLang: Lang, onTargetLang: (Lang) -> Unit, onTry: (String) -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var overlayAllowed by remember { mutableStateOf(canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        overlayAllowed = canDrawOverlays(context)
        onPauseOrDispose {}
    }
    OnboardingScreen(
        targetLang = targetLang,
        overlayAllowed = overlayAllowed,
        reducedMotion = remember { animationsDisabled(context) },
        actions = OnboardingActions(
            onTargetLang = onTargetLang,
            onRequestOverlayPermission = { context.startActivity(overlaySettingsIntent(context)) },
            onTry = onTry,
            onFinish = onFinish,
        ),
    )
}

/** True when the user turned off animations in developer options or accessibility ("Remove animations"). */
private fun animationsDisabled(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun OnboardingScreen(
    targetLang: Lang,
    overlayAllowed: Boolean,
    reducedMotion: Boolean,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val c = Monet.colors
    val t = Monet.type
    var step by rememberSaveable { mutableStateOf(OnboardingStep.LANGUAGE) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x4),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${step.ordinal + 1} of ${OnboardingStep.entries.size}", style = t.label, color = c.inkMuted)
            if (!step.isLast) {
                TextButton(onClick = actions.onFinish) { Text("Skip", style = t.label, color = c.inkMuted) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            when (step) {
                OnboardingStep.LANGUAGE -> LanguageStep(targetLang, actions.onTargetLang)
                OnboardingStep.LENS -> LensStep(overlayAllowed, reducedMotion, actions.onRequestOverlayPermission)
                OnboardingStep.CAPTURE -> CaptureStep()
                OnboardingStep.TRY_IT -> TryStep(actions.onTry)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            if (!step.isFirst) {
                TextButton(onClick = { step = step.previous() }) { Text("Back", style = t.label, color = c.ink) }
            }
            Button(
                onClick = { if (step.isLast) actions.onFinish() else step = step.next() },
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = c.seal, contentColor = c.paper),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(if (step.isLast) "Start using Monet" else "Next", style = t.label)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) = Text(text, style = Monet.type.display, color = Monet.colors.ink)

@Composable
private fun StepBody(text: String) = Text(text, style = Monet.type.body, color = Monet.colors.inkMuted)

// ---- 1. Language ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageStep(selected: Lang, onSelect: (Lang) -> Unit) {
    StepTitle("What should Monet translate into?")
    StepBody("You can change this any time in Settings.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        listOf(Lang.ZH to "中文", Lang.ES to "Español", Lang.EN to "English").forEach { (lang, label) ->
            ChoiceChip(label, lang == selected) { onSelect(lang) }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Monet.colors
    val shape = RoundedCornerShape(Radius.sm)
    Text(
        label,
        style = Monet.type.body,
        color = if (selected) c.seal else c.ink,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) c.sealSoft else Color.Transparent, shape)
            .border(1.dp, if (selected) c.seal else c.hairline, shape)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    )
}

// ---- 2. Lens + overlay permission ----------------------------------------------------------

@Composable
private fun LensStep(overlayAllowed: Boolean, reducedMotion: Boolean, onRequest: () -> Unit) {
    val c = Monet.colors
    StepTitle("Read anything on your screen")
    LensDemo(reducedMotion)
    StepBody("A small seal floats over your apps. Tap it and Monet translates the lines you are reading, right where they are.")
    if (overlayAllowed) {
        Text("Display over other apps: allowed", style = Monet.type.label, color = c.success)
    } else {
        StepBody("To float over other apps, Monet needs the “Display over other apps” permission.")
        OutlinedButton(
            onClick = onRequest,
            shape = RoundedCornerShape(Radius.sm),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.seal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Allow display over other apps", style = Monet.type.label, color = c.seal)
        }
    }
}

/**
 * The 3-second loop: a line of text, the seal band fading in over it, then its translation chip.
 * With animations removed it shows the finished state.
 */
@Composable
fun LensDemo(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val frame = if (reducedMotion) {
        StaticDemoFrame
    } else {
        val phase by rememberInfiniteTransition(label = "lensDemo").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        demoFrame(phase)
    }
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .fillMaxWidth()
            .height(128.dp)
            .background(c.surface, shape)
            .border(1.dp, c.hairline, shape)
            .clearAndSetSemantics { contentDescription = "Demo: the lens highlights a line of Chinese and shows its translation over it." },
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(48.dp).alpha(frame.bandAlpha).background(c.sealSoft))
        Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(48.dp).alpha(frame.bandAlpha).background(c.seal))
        Text(
            "这首歌真的yyds！",
            style = Monet.type.hanzi,
            color = c.ink,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = Spacing.x4),
        )
        Text(
            "This song is the GOAT!",
            style = Monet.type.reading,
            color = c.ink,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.x4, bottom = Spacing.x3)
                .alpha(frame.chipAlpha)
                .background(c.paper, RoundedCornerShape(Radius.xs))
                .border(1.dp, c.hairline, RoundedCornerShape(Radius.xs))
                .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        )
    }
}

// ---- 3. Screen capture notice --------------------------------------------------------------

@Composable
private fun CaptureStep() {
    StepTitle("Screen capture, only when you ask")
    StepBody("The first time you turn the lens on, Android shows its own prompt asking to capture your screen. Tap Start now to allow it.")
    StepBody("Monet reads the screen on your device. Only the text you choose to translate is sent to the server, never the image.")
}

// ---- 4. Try it -----------------------------------------------------------------------------

@Composable
private fun TryStep(onTry: (String) -> Unit) {
    val c = Monet.colors
    StepTitle("Try it")
    StepBody("Tap any line to see how Monet explains it.")
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        TrySamples.forEach { line ->
            val shape = RoundedCornerShape(Radius.md)
            Text(
                line,
                style = Monet.type.reading,
                color = c.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface, shape)
                    .border(1.dp, c.hairline, shape)
                    .clickable { onTry(line) }
                    .padding(Spacing.x4),
            )
        }
    }
}

private val previewActions = OnboardingActions({}, {}, {}, {})

@Preview(name = "Onboarding light", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun OnboardingLight() = MonetTheme(darkTheme = false) {
    OnboardingScreen(Lang.EN, overlayAllowed = false, reducedMotion = true, actions = previewActions)
}

@Preview(name = "Onboarding dark", showBackground = true, widthDp = 360, heightDp = 700, backgroundColor = 0xFF151412)
@Composable
private fun OnboardingDark() = MonetTheme(darkTheme = true) {
    OnboardingScreen(Lang.ZH, overlayAllowed = true, reducedMotion = true, actions = previewActions)
}
