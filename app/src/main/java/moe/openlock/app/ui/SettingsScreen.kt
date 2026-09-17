package moe.openlock.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.R
import moe.openlock.app.data.SettingsStore
import moe.openlock.app.security.BiometricGate
import moe.openlock.app.ui.theme.isDarkTheme
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * App settings.
 *
 * Built from miuix's preference components rather than hand-assembled
 * `Card` + `BasicComponent` rows: `ArrowPreference` draws its own chevron,
 * `SwitchPreference` its own switch, and both carry the right inside margins,
 * press states and summary colouring. The hand-rolled versions were subtly
 * wrong - most visibly the row that put raw content straight into a `Card`,
 * where the labels ended up flush against the card edge.
 */
@Composable
fun SettingsScreen(
    vm: LockViewModel,
    onBack: (() -> Unit)? = null,
    onTheme: () -> Unit,
    selectedTab: Screen,
    onSelectTab: (Screen) -> Unit,
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var biometric by remember { mutableStateOf(vm.settings.biometricLock) }
    var note by remember { mutableStateOf<String?>(null) }

    LockScaffold(
        title = "设置",
        onBack = onBack,
        bar = { style ->
            AppNavigationBar(
                selected = selectedTab,
                onSelect = onSelectTab,
                style = style,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Room for the floating bar, which overlaps the scroll area.
                // A docked bar already reserves its own height, so this is only
                // applied when the bar is actually floating.
                .padding(barClearance()),
        ) {
            MessageBanner(vm)

            GroupTitle("显示")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "主题",
                    summary = themeSummary(
                        mode = theme.mode,
                        followSystem = theme.followSystemAccent,
                        amoled = theme.amoled,
                        dark = isDarkTheme(theme),
                    ),
                    onClick = onTheme,
                )
            }

            GroupTitle("应用安全")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = "应用锁",
                    summary = "打开应用时需要指纹 / 面容 / 设备密码",
                    checked = biometric,
                    onCheckedChange = { wanted ->
                        toggleBiometric(context, wanted, vm) { ok, message ->
                            biometric = ok || !wanted
                            note = message
                        }
                    },
                )
                BiometricStatusLine(context)
            }

            note?.let {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Muted(it)
                }
            }

            GroupTitle("已配对门锁")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (devices.isEmpty()) {
                    Muted("还没有添加门锁。")
                } else {
                    devices.forEach { device ->
                        // InfoRow (BasicComponent) and not ArrowPreference: a
                        // chevron promises navigation, and ArrowPreference draws
                        // one even when onClick is null.
                        InfoRow(title = device.displayName, summary = "标识 ${device.id}")
                    }
                }
            }

            GroupTitle("关于")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                AboutHeader()
                InfoRow(title = "版本", summary = appVersion(context))
                ArrowPreference(
                    title = "GitHub",
                    summary = "OpenLock-O/OpenLock-Android",
                    onClick = { openRepo(context) },
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Muted(
                    "应用锁保护的是“手机被别人拿到”这种情况，它不改变门锁自身的访问控制。" +
                        "即使关掉应用锁，开锁命令依然必须来自已配对的手机。"
                )
            }
        }
    }
}

/** One line describing the current appearance, for the row that opens the screen. */
private fun themeSummary(
    mode: String,
    followSystem: Boolean,
    amoled: Boolean,
    dark: Boolean,
): String {
    val modeLabel = when (mode) {
        SettingsStore.MODE_LIGHT -> "浅色"
        SettingsStore.MODE_DARK -> "深色"
        else -> "跟随系统"
    }
    val parts = mutableListOf(modeLabel, if (followSystem) "跟随壁纸" else "自选主题色")
    if (amoled && dark) parts.add("纯黑")
    return parts.joinToString(" · ")
}

/** The organisation's mark, tinted to sit on whatever theme is active. */
@Composable
private fun AboutHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_openlock_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
            modifier = Modifier.size(44.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "OpenLock", style = MiuixTheme.textStyles.title4)
            Muted("手机端伴侣应用")
        }
    }
}

/** The project's source page, opened in whatever browser the user has. */
private fun openRepo(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, REPO_URL.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // A device with no browser at all would throw; ignore rather than crash.
    runCatching { context.startActivity(intent) }
}

private const val REPO_URL = "https://github.com/OpenLock-O/OpenLock-Android"

private fun appVersion(context: Context): String = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        .versionName
}.getOrNull() ?: "未知"

/** Turn the app lock on or off, refusing to enable it when the device cannot. */
private fun toggleBiometric(
    context: Context,
    wanted: Boolean,
    vm: LockViewModel,
    done: (Boolean, String?) -> Unit,
) {
    if (!wanted) {
        vm.settings.biometricLock = false
        done(true, null)
        return
    }

    when (BiometricGate.availability(context)) {
        BiometricGate.Availability.Available -> {
            val activity = context as? FragmentActivity
            if (activity == null) {
                done(false, "无法调用系统认证")
                return
            }
            // Confirm once before switching it on, so the user knows the prompt
            // they are about to see on every launch is expected.
            BiometricGate.prompt(
                activity = activity,
                title = "开启应用锁",
                subtitle = "确认一次即可开始使用",
                onSuccess = {
                    vm.settings.biometricLock = true
                    done(true, null)
                },
                onFailure = { done(false, it) },
            )
        }
        BiometricGate.Availability.NoHardware ->
            done(false, "这台设备没有可用的生物识别硬件")
        BiometricGate.Availability.NotEnrolled ->
            done(false, "还没有录入指纹或面容，请先在系统设置里录入")
        BiometricGate.Availability.Unavailable ->
            done(false, "系统认证当前不可用")
    }
}

/** Say what the device supports, so a disabled switch is explained. */
@Composable
private fun BiometricStatusLine(context: Context) {
    val text = when (BiometricGate.availability(context)) {
        BiometricGate.Availability.Available -> "可用：生物识别或设备密码"
        BiometricGate.Availability.NoHardware -> "这台设备没有生物识别硬件"
        BiometricGate.Availability.NotEnrolled -> "尚未录入指纹或面容"
        BiometricGate.Availability.Unavailable -> "系统认证不可用"
    }
    Row(
        // Aligned with the title above it, not with the card edge.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Muted(text)
    }
}
