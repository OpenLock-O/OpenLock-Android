package moe.openlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.ble.ConnState
import moe.openlock.app.data.LockDevice
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The lock list - the landing tab. */
@Composable
fun HomeScreen(
    vm: LockViewModel,
    onOpenDevice: (String) -> Unit,
    onAddDevice: () -> Unit,
    onSettings: () -> Unit,
    selectedTab: Screen,
    onSelectTab: (Screen) -> Unit,
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val connState by vm.connState.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()

    LockScaffold(
        title = "OpenLock",
        subtitle = if (devices.isEmpty()) "" else "${devices.size} 把门锁",
        actions = {
            IconButton(onClick = onAddDevice) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "添加门锁",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        bar = { style ->
            AppNavigationBar(
                selected = selectedTab,
                onSelect = onSelectTab,
                style = style,
            )
        },
    ) { padding ->
        /*
         * A single LazyColumn is the scroller for the whole page. Nesting a
         * LazyColumn inside another scrollable parent is the classic Compose
         * crash ("measured with an infinity maximum height"); keeping the
         * banner and the group title as items avoids it and lets the list
         * recycle.
         */
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            // Room for the bar when it floats over the list; nothing extra when
            // the Scaffold has already reserved the space for a docked one.
            contentPadding = barClearance(),
        ) {
            item { MessageBanner(vm) }

            if (devices.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有门锁",
                        body = "点击右上角的 + 扫描并添加一把门锁。" +
                            "第一次添加时，门锁需要在配对模式（新的门锁默认就是）。",
                    )
                }
                item {
                    Button(
                        onClick = onAddDevice,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Text(text = "扫描门锁")
                    }
                }
            } else {
                item { GroupTitle("门锁") }
                items(devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        isActive = device.id == activeId,
                        connState = if (device.id == activeId) connState else null,
                        onClick = { onOpenDevice(device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: LockDevice,
    isActive: Boolean,
    connState: ConnState?,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        BasicComponent(
            title = device.displayName,
            summary = deviceSummary(device, connState),
            // The bolt, drawn rather than described: two rows in a list are
            // easier to tell apart by a shape than by two similar sentences.
            startAction = { LockGlyph(locked = device.locked) },
            endActions = {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

/** One line that says what matters: bolt, battery, and whether we are live. */
private fun deviceSummary(device: LockDevice, connState: ConnState?): String {
    val bolt = when (device.locked) {
        true -> "已上锁"
        false -> "已开锁"
        null -> "状态未知"
    }
    val battery = device.battery?.takeIf { it >= 0 }?.let { "$it%" } ?: "电量未知"
    val link = when {
        connState == null -> null
        connState is ConnState.Ready -> "已连接"
        connState is ConnState.Failed -> "连接失败"
        connState is ConnState.Idle || connState is ConnState.Closed -> "未连接"
        else -> "连接中…"
    }
    return listOfNotNull(bolt, battery, link).joinToString(" · ")
}
