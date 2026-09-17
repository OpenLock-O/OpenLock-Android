/*
 * Ported from the miuix sample app (Apache-2.0):
 *   example/shared/src/commonMain/kotlin/component/liquid/Vibrancy.kt
 * at upstream 6352905 ("library: fix NavigationRail selection highlight (#434)").
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0).
 *
 * Package renamed to live in this app; `ui.isInDarkTheme` (the sample's own
 * theme switch) is replaced by this app's `isDarkTheme`/luminance check.
 * Logic is otherwise unchanged so future upstream fixes can be re-applied.
 */
package moe.openlock.app.ui.liquidglass.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** Lightweight stand-in for Kyant's `vibrancy()`. */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
