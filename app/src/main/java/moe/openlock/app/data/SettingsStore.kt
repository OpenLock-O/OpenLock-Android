package moe.openlock.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app should look.
 *
 * Mirrors the model LSPosed's manager uses, which separates *what the palette is
 * derived from* from *which colour it is*:
 *
 * @param mode [SettingsStore.MODE_SYSTEM] / [SettingsStore.MODE_LIGHT] /
 *   [SettingsStore.MODE_DARK].
 * @param followSystemAccent derive the palette from the wallpaper (Monet). When
 *   true, [accent] is ignored and the picker is disabled - there is nothing to
 *   pick, the system already decided.
 * @param accent one of the `ACCENT_*` seed keys, used only when
 *   [followSystemAccent] is false.
 * @param amoled force pure black surfaces in dark mode. Only meaningful when the
 *   effective appearance is dark.
 */
data class ThemePrefs(
    val mode: String = SettingsStore.MODE_SYSTEM,
    val followSystemAccent: Boolean = false,
    val accent: String = SettingsStore.ACCENT_MINT,
    val amoled: Boolean = false,
)

/**
 * How the chrome is built, as opposed to what colour it is.
 *
 * Split from [ThemePrefs] because these are independent decisions: picking a blue
 * accent says nothing about whether the bottom bar floats. LSPosed's manager keeps
 * them together on one page, which is why they are on one screen here too, but
 * they are two groups and two preference sets.
 *
 * @param blur frost the content behind the floating bar. Off makes the bar a flat
 *   translucent surface - a real fallback, not a broken setting: the runtime
 *   shader it needs is not available everywhere.
 * @param floatingBar the pill bar that hovers over the content, instead of a bar
 *   docked to the bottom edge that the content stops above.
 * @param liquidGlass push the glass further: more blur and a much lower fill
 *   opacity, so the colour behind the bar shows through more strongly. Meaningless
 *   without [blur] or without [floatingBar], which is why the screen disables it.
 * @param predictiveBack page changes follow the system back gesture. Off swaps in
 *   an immediate, non-interactive transition.
 * @param scale multiplies dp and sp together - the whole interface, not just text.
 */
data class UiPrefs(
    val blur: Boolean = true,
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = false,
    val predictiveBack: Boolean = true,
    val scale: Float = SettingsStore.SCALE_DEFAULT,
)

/**
 * App preferences.
 *
 * `biometricLock` puts a system authentication prompt (fingerprint, face or the
 * device credential) in front of everything the app can do. It protects against
 * someone picking up an unlocked phone; it is not a substitute for the lock's
 * own access control, which is what protects the door.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("openlock.settings", Context.MODE_PRIVATE)

    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC, value) }

    /*
     * Appearance is exposed as a flow rather than a plain getter so the theme
     * recomposes the moment a switch is flipped - the theme wraps the whole
     * activity, above where the theme screen lives.
     */
    private val _theme = MutableStateFlow(readTheme())

    val theme: StateFlow<ThemePrefs> = _theme.asStateFlow()

    private fun readTheme(): ThemePrefs {
        val accent = prefs.getString(KEY_ACCENT, ACCENT_MINT) ?: ACCENT_MINT
        /*
         * The earlier version stored "wallpaper" as if it were an accent. Read
         * it as what it always meant - follow the system - so an install that
         * predates the split keeps its setting.
         */
        val legacyWallpaper = accent == LEGACY_ACCENT_WALLPAPER
        return ThemePrefs(
            mode = prefs.getString(KEY_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM,
            followSystemAccent = prefs.getBoolean(KEY_FOLLOW_SYSTEM, legacyWallpaper),
            accent = if (legacyWallpaper) ACCENT_MINT else accent,
            amoled = prefs.getBoolean(KEY_AMOLED, false),
        )
    }

    fun setThemeMode(mode: String) {
        prefs.edit { putString(KEY_THEME_MODE, mode) }
        _theme.value = _theme.value.copy(mode = mode)
    }

    fun setFollowSystemAccent(follow: Boolean) {
        prefs.edit { putBoolean(KEY_FOLLOW_SYSTEM, follow) }
        _theme.value = _theme.value.copy(followSystemAccent = follow)
    }

    fun setAccent(accent: String) {
        prefs.edit { putString(KEY_ACCENT, accent) }
        _theme.value = _theme.value.copy(accent = accent, followSystemAccent = false)
    }

    fun setAmoled(amoled: Boolean) {
        prefs.edit { putBoolean(KEY_AMOLED, amoled) }
        _theme.value = _theme.value.copy(amoled = amoled)
    }

    // ---- interface ---------------------------------------------------------

    private val _ui = MutableStateFlow(readUi())

    val ui: StateFlow<UiPrefs> = _ui.asStateFlow()

    private fun readUi(): UiPrefs {
        val stored = prefs.getFloat(KEY_SCALE, SCALE_DEFAULT)
        return UiPrefs(
            blur = prefs.getBoolean(KEY_BLUR, true),
            floatingBar = prefs.getBoolean(KEY_FLOATING_BAR, true),
            liquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
            predictiveBack = prefs.getBoolean(KEY_PREDICTIVE_BACK, true),
            // A preference written by a future build - or corrupted - must not
            // be able to scale the interface to something unusable.
            scale = stored.takeIf { it in SCALE_CHOICES } ?: SCALE_DEFAULT,
        )
    }

    fun setBlur(on: Boolean) = updateUi { it.copy(blur = on) }

    fun setFloatingBar(on: Boolean) = updateUi { it.copy(floatingBar = on) }

    /**
     * Turning the glass off also turns the plain blur off: the two switches
     * describe one effect, and leaving blur on would make the glass switch look
     * like it did nothing.
     */
    fun setLiquidGlass(on: Boolean) = updateUi {
        it.copy(liquidGlass = on, blur = if (on) true else it.blur)
    }

    fun setPredictiveBack(on: Boolean) = updateUi { it.copy(predictiveBack = on) }

    fun setScale(scale: Float) = updateUi { it.copy(scale = scale) }

    private fun updateUi(edit: (UiPrefs) -> UiPrefs) {
        val next = edit(_ui.value)
        prefs.edit {
            putBoolean(KEY_BLUR, next.blur)
            putBoolean(KEY_FLOATING_BAR, next.floatingBar)
            putBoolean(KEY_LIQUID_GLASS, next.liquidGlass)
            putBoolean(KEY_PREDICTIVE_BACK, next.predictiveBack)
            putFloat(KEY_SCALE, next.scale)
        }
        _ui.value = next
    }

    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        const val ACCENT_MINT = "mint"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_VIOLET = "violet"
        const val ACCENT_AMBER = "amber"
        const val ACCENT_ROSE = "rose"

        private const val LEGACY_ACCENT_WALLPAPER = "wallpaper"

        /**
         * Interface scales the picker offers.
         *
         * A fixed set rather than a free slider: each of these has been looked at
         * on a real screen, and a stored value outside the set is treated as
         * corrupt on read.
         */
        val SCALE_CHOICES: List<Float> = listOf(0.85f, 0.92f, 1.0f, 1.08f, 1.15f)

        const val SCALE_DEFAULT = 1.0f

        private const val KEY_BIOMETRIC = "biometricLock"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_FOLLOW_SYSTEM = "followSystemAccent"
        private const val KEY_ACCENT = "accent"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_BLUR = "uiBlur"
        private const val KEY_FLOATING_BAR = "uiFloatingBar"
        private const val KEY_LIQUID_GLASS = "uiLiquidGlass"
        private const val KEY_PREDICTIVE_BACK = "uiPredictiveBack"
        private const val KEY_SCALE = "uiScale"
    }
}
