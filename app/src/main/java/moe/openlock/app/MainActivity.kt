package moe.openlock.app

import android.Manifest
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.openlock.app.ui.AppRoot
import moe.openlock.app.ui.InterfaceScale
import moe.openlock.app.ui.LocalUiPrefs
import moe.openlock.app.ui.theme.OpenLockTheme
import moe.openlock.app.ui.theme.isDarkTheme
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Single activity. Everything else is Compose.
 *
 * It extends FragmentActivity rather than ComponentActivity because
 * androidx.biometric's prompt needs a FragmentActivity; the biometric gate is
 * what stands between a stolen unlocked phone and the door.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The lock list runs under the system bars; miuix has its own insets
        // handling, so the window just needs to allow drawing there.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            // The theme wraps everything, including the permission and biometric
            // gates, so it has to be read here rather than inside a screen.
            val settings = (application as OpenLockApp).settings
            val themePrefs by settings.theme.collectAsStateWithLifecycle()
            val uiPrefs by settings.ui.collectAsStateWithLifecycle()

            OpenLockTheme(prefs = themePrefs) {
                // The window itself is not Compose, so its background and the
                // system bar icon colours have to be pushed across by hand.
                // Without this the window keeps whatever the XML theme had while
                // the content switches, which shows up as a stale strip under
                // the status bar.
                val background = MiuixTheme.colorScheme.background
                val dark = isDarkTheme(themePrefs)
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }

                // Interface scale and the chrome preferences are read from here
                // rather than passed down through every screen: they are global
                // display settings, and threading them through ten call sites
                // would make each screen's signature about the theme instead of
                // about the lock it shows.
                InterfaceScale(uiPrefs.scale) {
                    CompositionLocalProvider(LocalUiPrefs provides uiPrefs) {
                        val vm: LockViewModel = viewModel()
                        AppRoot(vm)
                    }
                }
            }
        }
    }

    companion object {
        /** Bluetooth permissions this app needs at runtime. */
        val BLE_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
