package dev.monet.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Semantic color tokens from docs/DESIGN_SYSTEM.md. */
@Immutable
data class MonetColors(
    val paper: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val hairline: Color,
    val seal: Color,
    val sealSoft: Color,
    val indigo: Color,
    val success: Color,
    val danger: Color,
    val tone1: Color,
    val tone2: Color,
    val tone3: Color,
    val tone4: Color,
    val tone5: Color,
    val isDark: Boolean,
)

val LightMonetColors = MonetColors(
    paper = Color(0xFFF7F4EE),
    surface = Color(0xFFFFFDF9),
    surfaceSunken = Color(0xFFEFEBE3),
    ink = Color(0xFF1B1A17),
    inkMuted = Color(0xFF5E5A52),
    inkFaint = Color(0xFF9A958B),
    hairline = Color(0xFF1B1A17).copy(alpha = 0.10f),
    seal = Color(0xFFC8402B),
    sealSoft = Color(0xFFC8402B).copy(alpha = 0.10f),
    indigo = Color(0xFF2F4A7A),
    success = Color(0xFF3C7A4E),
    danger = Color(0xFFB3261E),
    tone1 = Color(0xFFC8402B),
    tone2 = Color(0xFFB9822E),
    tone3 = Color(0xFF3C7A4E),
    tone4 = Color(0xFF2F4A7A),
    tone5 = Color(0xFF9A958B),
    isDark = false,
)

val DarkMonetColors = MonetColors(
    paper = Color(0xFF151412),
    surface = Color(0xFF1E1C19),
    surfaceSunken = Color(0xFF0F0E0C),
    ink = Color(0xFFECE8E1),
    inkMuted = Color(0xFFA39E94),
    inkFaint = Color(0xFF6B665D),
    hairline = Color(0xFFECE8E1).copy(alpha = 0.12f),
    seal = Color(0xFFE06A52),
    sealSoft = Color(0xFFE06A52).copy(alpha = 0.16f),
    indigo = Color(0xFF8CA6D8),
    success = Color(0xFF7DBF8E),
    danger = Color(0xFFF2B8B5),
    tone1 = Color(0xFFE06A52),
    tone2 = Color(0xFFD9A55A),
    tone3 = Color(0xFF7DBF8E),
    tone4 = Color(0xFF8CA6D8),
    tone5 = Color(0xFF6B665D),
    isDark = true,
)
