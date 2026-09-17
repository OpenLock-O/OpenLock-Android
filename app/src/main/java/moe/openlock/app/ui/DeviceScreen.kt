package moe.openlock.app.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.ble.ConnState
import moe.openlock.app.data.LockDevice
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One lock: open it, close it, read it, and manage who can reach it.
 *
 * Order is deliberate and matches what someone holding the phone wants: the
 * state drawing first (is it locked?), then the three actions, then the things
 * you only touch when something is wrong - calibration, pairing, identity.
 *
 * Two facts the page refuses to hide: the lock reports a fixed 100% because it
 * has no way to measure the pack, and the app only knows the bolt position while
 * it is connected.
 */
@Composable
fun DeviceScreen(
    vm: LockViewModel,
    device: LockDevice,
    onBack: () -> Unit,
    onPairingMode: () -> Unit,
    onPairedPhones: () -> Unit,
) {
    val connState by vm.connState.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    val isActive = device.id == activeId
    val live = isActive && connState is ConnState.Ready
    val busy = isActive && connState is ConnState.Connecting

    // Prefer the live status; fall back to what was cached when we last spoke.
    // The cached copy is labelled as such in the caption, because "locked as of
    // the last connection" and "locked now" are different claims.
    val bolt = if (live) status?.locked else device.locked
    val battery = if (live) status?.battery else device.battery
    val homed = if (live) status?.homed else null
    val last = if (live) status?.last else null

    var renaming by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmCalibrate by remember { mutableIntStateOf(0) }

    LockScaffold(
        title = device.displayName,
        subtitle = if (live) "已连接" else connLabel(connState),
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.refresh() }, enabled = live) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = "刷新",
                    tint = if (live) {
                        MiuixTheme.colorScheme.onBackground
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            MessageBanner(vm)

            // ---- state -----------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BasicComponentDefaults.InsideMargin),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LockStateIllustration(
                        locked = bolt,
                        state = status?.state ?: "unknown",
                    )
                    LockStateCaption(
                        locked = bolt,
                        battery = battery,
                        homed = homed,
                        lastAction = last,
                    )
                    if (!live) {
                        Muted(
                            if (isActive) {
                                "显示的是上次连接时读到的状态。"
                            } else {
                                "本机还没有连接这把门锁，显示的是上次读到的状态。"
                            }
                        )
                    }
                }
            }

            // ---- actions ---------------------------------------------------
            GroupTitle("操作")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.unlock() },
                    enabled = live,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (bolt == false) "已开锁（再开一次）" else "开锁")
                }
                Button(
                    onClick = { vm.lock() },
                    enabled = live,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "上锁")
                }
                Button(
                    onClick = { vm.stop() },
                    enabled = live,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "停止")
                }

                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Muted("正在连接…")
                    }
                }

                if (!live) {
                    Button(
                        onClick = { vm.connect(device) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "连接")
                    }
                } else {
                    Button(
                        onClick = { vm.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "断开")
                    }
                }
            }

            // ---- calibration -----------------------------------------------
            GroupTitle("行程校准")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                InfoRow(
                    title = "行程状态",
                    summary = when {
                        !live -> "连接后可读取"
                        homed == true -> "已标定，行程 ${status?.travel ?: 0} 计数"
                        homed == false -> "尚未标定"
                        else -> "未知"
                    },
                )
                ArrowPreference(
                    title = when (confirmCalibrate) {
                        0 -> "重新校准行程"
                        1 -> "再次点击：门锁会往返一次"
                        else -> "确认开始校准？"
                    },
                    summary = "门锁会分别顶到收回和伸出两个端点，记录总行程",
                    enabled = live,
                    onClick = {
                        if (confirmCalibrate >= 1) {
                            vm.calibrate()
                            confirmCalibrate = 0
                        } else {
                            confirmCalibrate = 1
                        }
                    },
                )
                Column(
                    modifier = Modifier.padding(BasicComponentDefaults.InsideMargin),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Muted(
                        "校准由门锁自己完成，期间门锁会真的转动，请确认门舌前方没有人或障碍物。" +
                            "结果会在门锁上报后自动更新。"
                    )
                    if (homed == false && live) {
                        Muted("第一次开关锁也会自动标定，通常不需要手动执行。")
                    }
                }
            }

            // ---- who can use it --------------------------------------------
            GroupTitle("访问权限")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "已配对设备",
                    summary = "查看并移除门锁记住的手机",
                    enabled = live,
                    onClick = onPairedPhones,
                )
                ArrowPreference(
                    title = "配对模式",
                    summary = if (live && status?.pairingOpen == true) {
                        "窗口当前是打开的"
                    } else {
                        "临时允许另一台手机配对到这把门锁"
                    },
                    enabled = live,
                    onClick = onPairingMode,
                )
            }

            // ---- identity --------------------------------------------------
            GroupTitle("标识")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "名称",
                    summary = device.displayName,
                    onClick = { renaming = !renaming },
                )
                if (renaming) {
                    RenameEditor(
                        initial = device.displayName,
                        onSave = { vm.rename(device.id, it); renaming = false },
                    )
                }
                InfoRow(title = "标识符", summary = device.id)
                InfoRow(
                    title = "地址",
                    summary = if (device.address.isBlank()) "未知" else device.address,
                )
                InfoRow(
                    title = "上次通信",
                    summary = lastSeenLabel(device.lastSeen),
                )
            }

            // ---- removal ---------------------------------------------------
            GroupTitle("从本机移除")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = if (confirmForget) "再次点击确认移除" else "忘记这把门锁",
                    summary = "只从本机移除，门锁上的配对记录不受影响",
                    onClick = {
                        if (confirmForget) {
                            vm.forget(device.id)
                            onBack()
                        } else {
                            confirmForget = true
                        }
                    },
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Muted(
                    "这里只是让本机忘掉这把门锁。要真正收回本机的开锁权限，" +
                        "请到「已配对设备」里把本机从门锁上移除。"
                )
            }
        }
    }
}

/** "...已经多久没连上", from the cached timestamp. */
private fun lastSeenLabel(atMillis: Long): String {
    if (atMillis <= 0L) return "没有记录"
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000
    val ago = when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
    return ago
}

@Composable
private fun RenameEditor(initial: String, onSave: (String?) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(value = value, onValueChange = { value = it.take(32) }, label = "显示名称")
        Button(onClick = { onSave(value.takeIf { it.isNotBlank() }) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "保存")
        }
    }
}
