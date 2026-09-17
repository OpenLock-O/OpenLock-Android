package moe.openlock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import moe.openlock.app.data.SettingsStore
import moe.openlock.app.data.ThemePrefs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * The accent seeds the picker offers.
 *
 * miuix derives a whole tonal palette from one seed, so the app only chooses the
 * hue; every surface, accent and dark variant follows from it.
 */
object Accents {

    val seeds: Map<String, Color> = linkedMapOf(
        SettingsStore.ACCENT_MINT to Color(0xFF7FD4C1),
        SettingsStore.ACCENT_BLUE to Color(0xFF5B9BF3),
        SettingsStore.ACCENT_VIOLET to Color(0xFF9B7BEF),
        SettingsStore.ACCENT_AMBER to Color(0xFFE8A33D),
        SettingsStore.ACCENT_ROSE to Color(0xFFE0728F),
    )

    val order: List<String> = seeds.keys.toList()

    fun seedOf(accent: String): Color =
        seeds[accent] ?: seeds.getValue(SettingsStore.ACCENT_MINT)
}

/**
 * Pure black for OLED panels.
 *
 * Only the surfaces go to black - the `on*` roles stay as the palette computed
 * them, so contrast is unchanged and text remains readable. The containers are
 * blacked out too, otherwise miuix's cards would still glow dark grey against a
 * black background and the mode would look half-applied.
 */
private fun Colors.toAmoled(): Colors = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
)

/**
 * The app's look.
 *
 * miuix supplies the shapes, spacing and motion; this only decides which palette
 * to hand it. [ThemePrefs] is persisted, so the choice survives a restart.
 *
 * Both appearance choices go through miuix's Monet modes, which is what lets one
 * code path cover "follow the wallpaper" and "derive from a chosen colour":
 *
 *  - **follow the wallpaper** leaves `keyColor` null, so miuix asks the platform
 *    for the real wallpaper-derived palette;
 *  - **a chosen accent** passes its seed as `keyColor`, and miuix generates the
 *    full tonal palette from it.
 *
 * The `Monet*` modes are the only ones that honour `keyColor`, which is why a
 * fixed accent uses them rather than `Light`/`Dark`.
 */
@Composable
fun OpenLockTheme(
    prefs: ThemePrefs = ThemePrefs(),
    content: @Composable () -> Unit,
) {
    val controller = remember(prefs.mode, prefs.accent, prefs.followSystemAccent) {
        ThemeController(
            colorSchemeMode = when (prefs.mode) {
                SettingsStore.MODE_LIGHT -> ColorSchemeMode.MonetLight
                SettingsStore.MODE_DARK -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetSystem
            },
            keyColor = if (prefs.followSystemAccent) null else Accents.seedOf(prefs.accent),
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = ThemePaletteStyle.TonalSpot,
        )
    }

    // currentColors() is what resolves mode + wallpaper into a concrete palette;
    // the result is then optionally overridden for AMOLED.
    //
    // The AMOLED override is gated on the *effective* appearance, not just on
    // the preference: with light mode selected the palette's `on*` roles are
    // dark, so forcing the surfaces to black would paint dark text on a black
    // background. The preference is kept so the choice is still there when the
    // user goes back to dark.
    val base = controller.currentColors()
    val applyAmoled = prefs.amoled && isDarkTheme(prefs)
    val colors = if (applyAmoled) remember(base) { base.toAmoled() } else base

    MiuixTheme(colors = colors, content = content)
}

/**
 * Whether [prefs] currently resolve to a dark palette.
 *
 * Used to decide whether the AMOLED switch is worth showing: pure black only
 * means anything when the appearance is dark.
 */
@Composable
fun isDarkTheme(prefs: ThemePrefs): Boolean = when (prefs.mode) {
    SettingsStore.MODE_LIGHT -> false
    SettingsStore.MODE_DARK -> true
    else -> isSystemInDarkTheme()
}
