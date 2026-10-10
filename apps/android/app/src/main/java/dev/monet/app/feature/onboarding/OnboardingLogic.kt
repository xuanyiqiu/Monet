package dev.monet.app.feature.onboarding

/** The four onboarding screens from docs/PRODUCT.md section 3. */
enum class OnboardingStep {
    LANGUAGE,
    LENS,
    CAPTURE,
    TRY_IT,
    ;

    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == entries.lastIndex

    fun next(): OnboardingStep = entries[minOf(ordinal + 1, entries.lastIndex)]
    fun previous(): OnboardingStep = entries[maxOf(ordinal - 1, 0)]
}

/** One frame of the looping Lens demo: how visible the seal band and the translation chip are. */
data class DemoFrame(val bandAlpha: Float, val chipAlpha: Float)

private fun ramp(phase: Float, from: Float, to: Float): Float = ((phase - from) / (to - from)).coerceIn(0f, 1f)

/**
 * The 3-second loop as a pure function of [phase] (0..1): the band fades in, the chip follows,
 * both hold, then fade out together so the loop restarts cleanly.
 */
fun demoFrame(phase: Float): DemoFrame {
    val p = phase.coerceIn(0f, 1f)
    val fadeOut = 1f - ramp(p, 0.88f, 1f)
    return DemoFrame(
        bandAlpha = ramp(p, 0.10f, 0.25f) * fadeOut,
        chipAlpha = ramp(p, 0.35f, 0.50f) * fadeOut,
    )
}

/** What the demo shows when the user turned animations off: the finished state, no motion. */
val StaticDemoFrame = DemoFrame(bandAlpha = 1f, chipAlpha = 1f)

/** Sample lines for the "Try it" screen: memes, a chengyu and idioms in each supported language. */
val TrySamples: List<String> = listOf(
    "这首歌真的yyds！",
    "他这样做简直是画蛇添足。",
    "我真的蚌埠住了",
    "Ana está en las nubes.",
    "No cap, that was fire.",
)
