package app.dicto.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Une palette de Dicto : claire (canvas « Dicto », design/tokens.json) ou sombre. */
data class DictoPalette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val border: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkDisabled: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val recording: Color,
    val recordingContainer: Color,
    val onRecordingContainer: Color,
    val success: Color,
)

val LightPalette = DictoPalette(
    dark = false,
    background = Color(0xFFF6F1EA),
    surface = Color(0xFFFFFCF8),
    surfaceSunken = Color(0xFFEFE7DC),
    border = Color(0xFFE4D9CB),
    ink = Color(0xFF2B231D),
    inkSecondary = Color(0xFF4A3F37),
    inkMuted = Color(0xFF6F6258),
    inkDisabled = Color(0xFFA0948A),
    accent = Color(0xFF2F5D50),
    accentContainer = Color(0xFFE3ECE7),
    onAccent = Color(0xFFFFFCF8),
    recording = Color(0xFFD9622B),
    recordingContainer = Color(0xFFFBE5D8),
    onRecordingContainer = Color(0xFF9A3F16),
    success = Color(0xFF3E7D4F),
)

/** Le même papier chaud, de nuit : encre claire sur brun profond, vert éclairci pour garder le contraste. */
val DarkPalette = DictoPalette(
    dark = true,
    background = Color(0xFF1A1512),
    surface = Color(0xFF241E19),
    surfaceSunken = Color(0xFF2F2721),
    border = Color(0xFF3D332B),
    ink = Color(0xFFF3ECE3),
    inkSecondary = Color(0xFFD9CDBF),
    inkMuted = Color(0xFFB3A597),
    inkDisabled = Color(0xFF85786C),
    accent = Color(0xFF8ACBB5),
    accentContainer = Color(0xFF22392F),
    onAccent = Color(0xFF10231D),
    recording = Color(0xFFE2723D),
    recordingContainer = Color(0xFF4A2A1A),
    onRecordingContainer = Color(0xFFFFB48F),
    success = Color(0xFF6FBF85),
)

/** Thème choisi : « auto » (celui du téléphone), « light » ou « dark ». Observable par Compose. */
object ThemeMode {
    var mode by mutableStateOf("auto")
        private set

    fun set(value: String) {
        mode = value.takeIf { it == "light" || it == "dark" } ?: "auto"
    }
}

/** Si Dicto doit être sombre maintenant (le réglage, ou le téléphone en « auto »). */
@Composable
fun dictoIsDark(): Boolean = when (ThemeMode.mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

/** Couleurs de Dicto : celles de la palette active, lues par Compose (changer de thème redessine tout). */
object Dicto {
    var palette by mutableStateOf(LightPalette)
        internal set

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceSunken get() = palette.surfaceSunken
    val Border get() = palette.border
    val Ink get() = palette.ink
    val InkSecondary get() = palette.inkSecondary
    val InkMuted get() = palette.inkMuted
    val InkDisabled get() = palette.inkDisabled
    val Accent get() = palette.accent
    val AccentContainer get() = palette.accentContainer
    val OnAccent get() = palette.onAccent
    val Recording get() = palette.recording
    /** Sur le bouton d'enregistrement orange : toujours clair. */
    val OnRecording = Color(0xFFFFFCF8)
    val RecordingContainer get() = palette.recordingContainer
    val OnRecordingContainer get() = palette.onRecordingContainer
    val RecordingWave = Color(0xFFF08A55)
    val Success get() = palette.success
    val Brand = Color(0xFFF0B44C)
    // La bulle et son panneau restent sombres dans les deux thèmes.
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

private fun colorsOf(p: DictoPalette) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accent,
    onPrimary = p.onAccent,
    primaryContainer = p.accentContainer,
    onPrimaryContainer = p.accent,
    secondary = p.inkSecondary,
    onSecondary = p.surface,
    secondaryContainer = p.surfaceSunken,
    onSecondaryContainer = p.ink,
    tertiary = p.recording,
    onTertiary = p.surface,
    tertiaryContainer = p.recordingContainer,
    onTertiaryContainer = p.onRecordingContainer,
    background = p.background,
    onBackground = p.ink,
    surface = p.surface,
    onSurface = p.ink,
    surfaceVariant = p.surfaceSunken,
    onSurfaceVariant = p.inkMuted,
    surfaceContainerLowest = p.surface,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.surface,
    surfaceContainerHighest = p.surfaceSunken,
    outline = p.border,
    outlineVariant = p.surfaceSunken,
    error = p.onRecordingContainer,
    errorContainer = p.recordingContainer,
    inverseSurface = p.ink,
    inverseOnSurface = p.surface,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Le thème de Dicto, clair ou sombre selon [ThemeMode] ; [dark] le force (captures d'écran). */
@Composable
fun DictoTheme(dark: Boolean = dictoIsDark(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    // Écrit avant que les écrans ne lisent leurs couleurs : tout le contenu suit dans la même composition.
    if (Dicto.palette !== palette) Dicto.palette = palette
    val colors = remember(palette) { colorsOf(palette) }
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        // Les textes sans couleur explicite prennent l'encre de la palette (sinon : noir, illisible en sombre).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides palette.ink, content = content)
    }
}
