package moe.openlock.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.data.SettingsStore
import moe.openlock.app.ui.theme.Accents
import moe.openlock.app.ui.theme.isDarkTheme
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Theme settings, on a screen of their own, pushed from Settings.
 *
 * The structure follows LSPosed's manager, which is the reference the app was
 * asked to match. Three things there are worth copying:
 *
 *  1. **The appearance and the colour are separate decisions.** Which mode to be
 *     in (light/dark/system) has nothing to do with where the palette comes
 *     from, so they are two groups rather than one matrix of six combinations.
 *  2. **Following the system disables the picker** rather than hiding it. The
 *     rows stay visible but go inert, so it is obvious the wallpaper is in
 *     charge and the choice was not lost.
 *  3. **Pure black is a dark-mode extra**, shown only while dark is in effect.
 */
@Composable
fun ThemeScreen(
    vm: LockViewModel,
    onBack: () -> Unit,
) {
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val ui by vm.settings.ui.collectAsStateWithLifecycle()
    val dark = isDarkTheme(theme)
    val wallpaperPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LockScaffold(title = "主题", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            GroupTitle("外观")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ModeRow("跟随系统", SettingsStore.MODE_SYSTEM, theme.mode, vm)
                ModeRow("浅色", SettingsStore.MODE_LIGHT, theme.mode, vm)
                ModeRow("深色", SettingsStore.MODE_DARK, theme.mode, vm)
            }

            if (dark) {
                GroupTitle("深色选项")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "纯黑模式",
                        summary = "深色下使用纯黑背景，OLED 屏幕更省电",
                        checked = theme.amoled,
                        onCheckedChange = { vm.settings.setAmoled(it) },
                    )
                }
            }

            GroupTitle("主题色")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = "跟随壁纸取色",
                    summary = if (wallpaperPossible) {
                        "由系统从壁纸生成配色，关闭后可自选主题色"
                    } else {
                        "需要 Android 12 及以上"
                    },
                    checked = wallpaperPossible && theme.followSystemAccent,
                    enabled = wallpaperPossible,
                    onCheckedChange = { vm.settings.setFollowSystemAccent(it) },
                )

                Accents.order.forEach { accent ->
                    AccentRow(
                        accent = accent,
                        current = theme.accent,
                        // Inert while the wallpaper owns the palette.
                        enabled = !theme.followSystemAccent,
                        onPick = { vm.settings.setAccent(accent) },
                    )
                }
            }

            /*
             * The chrome group. These are the switches that change how the app is
             * built rather than what colour it is, so they sit apart from the
             * palette above - the same split LSPosed's manager makes.
             */
            GroupTitle("界面")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = "界面模糊",
                    summary = "底栏背后的内容做实时模糊；设备不支持时自动变成半透明",
                    checked = ui.blur,
                    onCheckedChange = { vm.settings.setBlur(it) },
                )
                SwitchPreference(
                    title = "悬浮底栏",
                    summary = "底栏浮在内容上方，内容从它下面穿过",
                    checked = ui.floatingBar,
                    onCheckedChange = { vm.settings.setFloatingBar(it) },
                )
                SwitchPreference(
                    // Pointless without something to blur or a bar to float, so
                    // it goes inert instead of being hidden - the setting is not
                    // lost, it just cannot do anything yet.
                    title = "液态玻璃",
                    summary = if (ui.blur && ui.floatingBar) {
                        "更强的模糊和更低的填充度，背景色透出来更明显"
                    } else {
                        "需要同时打开界面模糊和悬浮底栏"
                    },
                    checked = ui.liquidGlass,
                    enabled = ui.blur && ui.floatingBar,
                    onCheckedChange = { vm.settings.setLiquidGlass(it) },
                )
                SwitchPreference(
                    title = "预测性返回手势",
                    summary = "页面切换跟随返回手势；关闭后切换没有过渡动画",
                    checked = ui.predictiveBack,
                    onCheckedChange = { vm.settings.setPredictiveBack(it) },
                )
            }

            GroupTitle("界面缩放")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SettingsStore.SCALE_CHOICES.forEach { choice ->
                    ScaleRow(choice = choice, current = ui.scale, onPick = { vm.settings.setScale(choice) })
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Muted(
                    "主题只影响这台手机上的显示。" +
                        if (theme.followSystemAccent) {
                            "当前配色由壁纸生成，因此主题色不可选。"
                        } else {
                            ""
                        }
                )
            }
        }
    }
}

/**
 * One interface scale. The label is the percentage, the summary says what it
 * does, so the choice does not need a preview to be understood.
 */
@Composable
private fun ScaleRow(choice: Float, current: Float, onPick: () -> Unit) {
    RadioButtonPreference(
        title = "${(choice * 100).toInt()}%",
        summary = when {
            choice < 1f -> "更紧凑，一屏能看到更多内容"
            choice > 1f -> "更大，更适合单手操作"
            else -> "默认大小"
        },
        selected = choice == current,
        onClick = onPick,
    )
}

/** One appearance choice. */
@Composable
private fun ModeRow(
    label: String,
    value: String,
    current: String,
    vm: LockViewModel,
) {
    RadioButtonPreference(
        title = label,
        selected = current == value,
        onClick = { vm.settings.setThemeMode(value) },
    )
}

/**
 * One accent row: a swatch, the name, and the selection mark.
 *
 * The swatch goes in `startAction` and the radio moves to the end, which is
 * miuix's own layout for a colour choice - the eye reads the colour first, then
 * scans right to confirm which one is ticked.
 *
 * While the wallpaper is in charge the swatches become hollow rings. Filling
 * them with a disabled grey still looked like a row of colour choices, just with
 * the wrong colours; a ring carries no colour at all, so it reads as "nothing to
 * pick here" while keeping the layout stable.
 */
@Composable
private fun AccentRow(
    accent: String,
    current: String,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    RadioButtonPreference(
        title = accentLabel(accent),
        selected = accent == current,
        onClick = if (enabled) onPick else null,
        enabled = enabled,
        radioButtonLocation = RadioButtonLocation.End,
        startAction = {
            if (enabled) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Accents.seedOf(accent)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(24.dp)
                        .border(
                            width = 1.5.dp,
                            color = MiuixTheme.colorScheme.disabledOnSurface,
                            shape = CircleShape,
                        ),
                )
            }
        },
    )
}

private fun accentLabel(accent: String): String = when (accent) {
    SettingsStore.ACCENT_MINT -> "薄荷"
    SettingsStore.ACCENT_BLUE -> "蓝色"
    SettingsStore.ACCENT_VIOLET -> "紫罗兰"
    SettingsStore.ACCENT_AMBER -> "琥珀"
    SettingsStore.ACCENT_ROSE -> "玫瑰"
    else -> accent
}
