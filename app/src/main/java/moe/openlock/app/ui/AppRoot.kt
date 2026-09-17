package moe.openlock.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.MainActivity
import moe.openlock.app.ble.ConnState
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Where the app can be.
 *
 * These are [NavKey]s so [NavDisplay] can own the stack. A single sealed
 * hierarchy keeps the routes type-safe and lets the back stack be a plain list
 * of keys rather than a pile of string tags.
 *
 * [Locks] and [Settings] are the two tabs of the bottom bar; everything else is
 * pushed on top of one of them.
 */
sealed interface Screen : NavKey {
    /** The lock list - the landing tab. */
    data object Locks : Screen

    /** App settings. */
    data object Settings : Screen

    /** The theme sub-page of settings. */
    data object Theme : Screen

    /** Scanning for a lock to add. */
    data object Scan : Screen

    /** One lock. */
    data class Device(val id: String) : Screen

    /** The pairing window for one lock. */
    data class Pairing(val id: String) : Screen

    /** The phones bonded on one lock, and revoking them. */
    data class PairedPhones(val id: String) : Screen
}

private fun hasBlePermissions(context: Context): Boolean =
    MainActivity.BLE_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Top-level gate order: biometrics, then Bluetooth permissions, then the app.
 *
 * The biometric prompt comes first on purpose - it should be the first thing a
 * stranger holding the phone meets, and it should not be skippable by
 * answering a permission dialog.
 */
@Composable
fun AppRoot(vm: LockViewModel) {
    val context = LocalContext.current

    var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted = hasBlePermissions(context) }

    // Not persisted: a cold start always asks again.
    var authenticated by remember { mutableStateOf(!vm.settings.biometricLock) }

    when {
        !authenticated -> GateScreen(resume = { authenticated = true })

        !permissionsGranted -> PermissionScreen(
            onRequest = { permissionLauncher.launch(MainActivity.BLE_PERMISSIONS) }
        )

        else -> MainNavigation(vm)
    }
}

/**
 * The lock screen shown when the app lock is on.
 *
 * The system prompt fires on its own as soon as this page appears - opening the
 * app and immediately meeting the fingerprint dialog is the whole point of an
 * app lock. The manual button only shows up after the user backs out of the
 * prompt, so there is always a way back in without relaunching.
 */
@Composable
private fun GateScreen(resume: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var note by remember { mutableStateOf<String?>(null) }
    // False until the user dismisses the prompt themselves. A failed attempt
    // (wrong finger) re-prompts on its own; only an explicit cancellation arms
    // the manual button.
    var dismissed by remember { mutableStateOf(false) }

    fun launch() {
        if (activity == null) {
            note = "无法调用系统认证"
            return
        }
        moe.openlock.app.security.BiometricGate.prompt(
            activity = activity,
            title = "解锁 OpenLock",
            subtitle = "验证身份后即可开门",
            onSuccess = { note = null; resume() },
            onFailure = { dismissed = true; note = it },
        )
    }

    // Fires once per appearance: entering this page *is* the request. Keyed on
    // Unit so a recomposition (a theme flip, a rotation) does not re-fire it
    // on top of the dialog that is already showing.
    LaunchedEffect(Unit) { launch() }

    LockScaffold(title = "OpenLock") { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "已锁定", style = MiuixTheme.textStyles.title3)
            Muted("应用锁已开启。用指纹、面容或设备密码验证后即可使用。")
            if (dismissed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { dismissed = false; launch() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "验证身份")
                    }
                }
            }
            note?.let { Muted(it) }
        }
    }
}

/** Bluetooth permissions are required before anything can be scanned. */
@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    LockScaffold(title = "需要权限") { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "蓝牙权限", style = MiuixTheme.textStyles.title4)
            Muted(
                "扫描和连接门锁需要“附近的设备”权限。" +
                    "本应用不会用扫描结果推断位置。"
            )
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(text = "授予权限")
            }
        }
    }
}

/**
 * The screen stack, rendered by miuix's own navigator.
 *
 * [NavDisplay] is doing three things that a `when (screen)` cannot:
 *
 *  - it animates every push and pop with [top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault],
 *    including the parallax of the page underneath;
 *  - it drives the Android predictive-back gesture, so the incoming page follows
 *    the user's finger and can be cancelled;
 *  - it keeps per-entry saveable state, so returning to a screen restores its
 *    scroll position instead of resetting it.
 */
@Composable
private fun MainNavigation(vm: LockViewModel) {
    val backStack = remember { navBackStackOf(Screen.Locks) }
    val devices by vm.devices.collectAsStateWithLifecycle()

    /** Replace the whole stack with a tab root - what a bottom-bar tap means. */
    fun selectTab(tab: Screen) {
        if (backStack.size == 1 && backStack[0] == tab) return
        backStack.clear()
        backStack.add(tab)
    }

    fun push(screen: Screen) {
        backStack.add(screen)
    }

    fun pop() {
        backStack.removeLastOrNull()
    }

    NavDisplay(
        backStack = backStack,
        // With gesture-driven transitions off, pages appear immediately. That is
        // a real trade rather than a cosmetic one: the incoming page no longer
        // follows the back gesture, so it cannot be cancelled mid-swipe.
        transition = if (LocalUiPrefs.current.predictiveBack) {
            NavTransitions.MiuixDefault
        } else {
            NavTransitions.None
        },
    ) {
        entry<Screen.Locks> {
            HomeScreen(
                vm = vm,
                onOpenDevice = { push(Screen.Device(it)) },
                onAddDevice = { push(Screen.Scan) },
                onSettings = { selectTab(Screen.Settings) },
                selectedTab = Screen.Locks,
                onSelectTab = ::selectTab,
            )
        }

        entry<Screen.Settings> {
            SettingsScreen(
                vm = vm,
                onBack = null,
                onTheme = { push(Screen.Theme) },
                selectedTab = Screen.Settings,
                onSelectTab = ::selectTab,
            )
        }

        entry<Screen.Theme>(
            // Slides in from a shallower depth than a full push: it is a
            // sub-page of settings, not a new task.
            transition = NavTransitions.Modal,
        ) {
            ThemeScreen(vm = vm, onBack = ::pop)
        }

        entry<Screen.Scan> {
            ScanScreen(
                vm = vm,
                onBack = ::pop,
                onPicked = { id -> pop(); push(Screen.Device(id)) },
            )
        }

        entry<Screen.Device> { key ->
            val device = devices.firstOrNull { it.id == key.id }
            if (device == null) {
                // Forgotten from under us; fall back rather than crash.
                pop()
            } else {
                DeviceScreen(
                    vm = vm,
                    device = device,
                    onBack = ::pop,
                    onPairingMode = { push(Screen.Pairing(device.id)) },
                    onPairedPhones = { push(Screen.PairedPhones(device.id)) },
                )
            }
        }

        entry<Screen.Pairing> { key ->
            val device = devices.firstOrNull { it.id == key.id }
            if (device == null) {
                pop()
            } else {
                PairingScreen(vm = vm, device = device, onBack = ::pop)
            }
        }

        entry<Screen.PairedPhones> { key ->
            val device = devices.firstOrNull { it.id == key.id }
            if (device == null) {
                pop()
            } else {
                PairedPhonesScreen(vm = vm, device = device, onBack = ::pop)
            }
        }
    }
}

/** Shared: the connection state as a readable line. */
fun connLabel(state: ConnState): String = when (state) {
    ConnState.Idle -> "未连接"
    ConnState.Connecting -> "连接中…"
    ConnState.Discovering -> "发现服务…"
    ConnState.Bonding -> "等待系统配对…"
    ConnState.EnablingEvents -> "订阅事件…"
    ConnState.Ready -> "已连接"
    ConnState.Closed -> "已断开"
    is ConnState.Failed -> state.message
}

/**
 * Shared: a Card carrying the newest note from the repository.
 *
 * Screens place this at the top of their content. miuix also has a Snackbar for
 * this; a card was kept because the notes here are not transient toasts - several
 * of them ("the lock wants a PIN", "reconnecting") describe a state the user
 * needs to keep reading while they act.
 */
@Composable
fun MessageBanner(vm: LockViewModel) {
    val log by vm.log.collectAsStateWithLifecycle()
    val newest = log.lastOrNull() ?: return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = newest,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
