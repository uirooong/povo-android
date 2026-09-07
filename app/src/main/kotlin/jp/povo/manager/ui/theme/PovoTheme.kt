package jp.povo.manager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * How the app decides its colours.
 *
 * [SYSTEM] is the default and the pre-existing behaviour — it follows the OS
 * light/dark setting and, on Android 12+, the wallpaper palette. [LIGHT] and
 * [DARK] pin that choice. [POVO] ignores both and uses the service's own
 * yellow, which is the one palette the OS can never produce.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("システムに従う"),
    LIGHT("ライト"),
    DARK("ダーク"),
    POVO("povo カラー"),
    ;

    companion object {
        /** Tolerates an unknown stored value rather than crashing on it. */
        fun from(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The palette taken from the app's own icon.
 *
 * Yellow cannot be a Material `primary` as-is: white-on-yellow fails contrast
 * at any weight, so the brand yellow serves as the *container* colour and the
 * ink navy carries the text on it. The deeper yellow is reserved for `primary`
 * itself, where it has to sit under dark text on small controls.
 */
private val PovoYellow = Color(0xFFFFE34B)
private val PovoYellowDeep = Color(0xFFF2CE00)
private val PovoInk = Color(0xFF242335)
private val PovoAccent = Color(0xFF6750A4)
private val PovoCream = Color(0xFFFFFDF3)
private val PovoCreamDim = Color(0xFFF6EFD8)
private val PovoCreamDimmer = Color(0xFFEDE4C6)

private val PovoColorScheme = lightColorScheme(
    primary = PovoYellowDeep,
    onPrimary = PovoInk,
    primaryContainer = PovoYellow,
    onPrimaryContainer = PovoInk,
    secondary = PovoAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEF8),
    onSecondaryContainer = Color(0xFF21005D),
    tertiary = PovoInk,
    onTertiary = PovoYellow,
    background = PovoCream,
    onBackground = PovoInk,
    surface = PovoCream,
    onSurface = PovoInk,
    surfaceVariant = PovoCreamDim,
    onSurfaceVariant = Color(0xFF554F3C),
    surfaceContainer = PovoCreamDim,
    surfaceContainerHigh = PovoCreamDimmer,
    surfaceContainerHighest = PovoCreamDimmer,
    outline = Color(0xFF8A8069),
    outlineVariant = Color(0xFFDCD2B4),
)

@Composable
fun PovoTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT, ThemeMode.POVO -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        mode == ThemeMode.POVO -> PovoColorScheme
        // Dynamic colour only where the platform has it, and only when the user
        // has not asked for a specific look — pinning LIGHT or DARK should mean
        // the same colours on every device.
        mode == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
