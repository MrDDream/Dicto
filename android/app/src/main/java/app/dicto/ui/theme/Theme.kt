package app.dicto.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dicto.R

/** Couleurs du canvas « Dicto » (design/tokens.json). */
object Dicto {
    val Background = Color(0xFFF6F1EA)
    val Surface = Color(0xFFFFFCF8)
    val SurfaceSunken = Color(0xFFEFE7DC)
    val Border = Color(0xFFE4D9CB)
    val Ink = Color(0xFF2B231D)
    val InkSecondary = Color(0xFF4A3F37)
    val InkMuted = Color(0xFF6F6258)
    val InkDisabled = Color(0xFFA0948A)
    val Accent = Color(0xFF2F5D50)
    val AccentContainer = Color(0xFFE3ECE7)
    val OnAccent = Color(0xFFFFFCF8)
    val Recording = Color(0xFFD9622B)
    val RecordingContainer = Color(0xFFFBE5D8)
    val OnRecordingContainer = Color(0xFF9A3F16)
    val RecordingWave = Color(0xFFF08A55)
    val Success = Color(0xFF3E7D4F)
    val Brand = Color(0xFFF0B44C)
    val OverlayBackground = Color(0xFF2B231D)
    val OverlayInk = Color(0xFFF6F1EA)
    val OverlayMuted = Color(0xFFBFB3A7)
    val OverlayLine = Color(0xFF4A3F37)
    val OverlayRecordingContainer = Color(0xFF4A2A1A)
    val OverlayRecordingInk = Color(0xFFFFC7A8)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int): List<Font> = weights.map { w ->
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
}

val Display = FontFamily(variable(R.font.bricolage_grotesque, 500, 600, 700, 800))
val Body = FontFamily(variable(R.font.figtree, 400, 500, 600, 700))
val Mono = FontFamily(variable(R.font.jetbrains_mono, 400, 500))

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.03).em),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.03).em),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

private val colors = lightColorScheme(
    primary = Dicto.Accent,
    onPrimary = Dicto.OnAccent,
    primaryContainer = Dicto.AccentContainer,
    onPrimaryContainer = Dicto.Accent,
    secondary = Dicto.InkSecondary,
    onSecondary = Dicto.Surface,
    secondaryContainer = Dicto.SurfaceSunken,
    onSecondaryContainer = Dicto.Ink,
    tertiary = Dicto.Recording,
    onTertiary = Dicto.Surface,
    tertiaryContainer = Dicto.RecordingContainer,
    onTertiaryContainer = Dicto.OnRecordingContainer,
    background = Dicto.Background,
    onBackground = Dicto.Ink,
    surface = Dicto.Surface,
    onSurface = Dicto.Ink,
    surfaceVariant = Dicto.SurfaceSunken,
    onSurfaceVariant = Dicto.InkMuted,
    surfaceContainerLowest = Dicto.Surface,
    surfaceContainerLow = Dicto.Surface,
    surfaceContainer = Dicto.Surface,
    surfaceContainerHigh = Dicto.Surface,
    surfaceContainerHighest = Dicto.SurfaceSunken,
    outline = Dicto.Border,
    outlineVariant = Dicto.SurfaceSunken,
    error = Dicto.OnRecordingContainer,
    errorContainer = Dicto.RecordingContainer,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Le canvas ne définit pas encore de thème sombre : Dicto reste en clair pour l'instant. */
@Composable
fun DictoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
