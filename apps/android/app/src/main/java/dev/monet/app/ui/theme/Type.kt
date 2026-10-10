package dev.monet.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Type roles from docs/DESIGN_SYSTEM.md. */
@Immutable
data class MonetTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val reading: TextStyle,
    val hanzi: TextStyle,
    val ruby: TextStyle,
    val ipa: TextStyle,
)

data class MonetFonts(val sans: FontFamily, val serif: FontFamily, val hanzi: FontFamily) {
    companion object {
        val System = MonetFonts(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Serif)
    }
}

fun monetTypography(fonts: MonetFonts = MonetFonts.System) = MonetTypography(
    display = TextStyle(fontFamily = fonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    title = TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(
        fontFamily = fonts.sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    reading = TextStyle(fontFamily = fonts.serif, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 28.sp),
    hanzi = TextStyle(fontFamily = fonts.hanzi, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 40.sp),
    ruby = TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
    ipa = TextStyle(
        fontFamily = fonts.sans, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
)
