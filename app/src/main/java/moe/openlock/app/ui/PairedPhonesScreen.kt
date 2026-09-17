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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.ble.ConnState
import moe.openlock.app.data.LockDevice
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The phones the lock itself remembers.
 *
 * This is a different list from the one on the home screen, and the difference
 * matters, so the screen says so: the home list is what *this app* has been told
 * about, while this one is the lock's own bond store. Removing an entry here
 * takes that phone's access away at the door; forgetting a lock on the home
 * screen only stops this app from offering it. The two lists do not have to
 * agree, and neither is a copy of the other.
 */
@Composable
fun PairedPhonesScreen(
    vm: LockViewModel,
    device: LockDevice,
    onBack: () -> Unit,
) {
    val connState by vm.connState.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val bonds by vm.bonds.collectAsStateWithLifecycle()
    val loaded by vm.bondsLoaded.collectAsStateWithLifecycle()
    val busy by vm.bondsBusy.collectAsStateWithLifecycle()

    val live = device.id == activeId && connState is ConnState.Ready

    /** Index awaiting a second tap, or -1. */
    var confirming by remember { mutableIntStateOf(-1) }

    // Read the list once the link is up. Keyed on `live` so a reconnect re-reads
    // instead of showing the previous session's answer.
    LaunchedEffect(live) {
        if (live) vm.refreshBonds()
    }

    LockScaffold(
        title = "已配对设备",
        subtitle = device.displayName,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            MessageBanner(vm)

            if (!live) {
                // Nothing here is meaningful without a link, so the page is a
                // connect prompt rather than a list with everything disabled.
                GroupTitle("连接")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = "需要先连接门锁", style = MiuixTheme.textStyles.main)
                        Muted("配对记录保存在门锁上，只有连上才能读取和修改。")
                        Muted("当前状态：${connLabel(connState)}")
                    }
                }
                ClickableCard(
                    title = "连接门锁",
                    summary = "连接后即可读取配对记录",
                    onClick = { vm.connect(device) },
                )
            } else {
                GroupTitle("门锁上的配对记录")
                when {
                    !loaded -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Muted("正在读取…")
                        }
                    }

                    bonds.isEmpty() -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(text = "门锁上没有配对记录", style = MiuixTheme.textStyles.main)
                            Muted(
                                "门锁现在没有主人，任何手机都可以配对。" +
                                    "本机也已经不在门锁的记录里，需要重新配对才能继续开关锁。"
                            )
                        }
                    }

                    else -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        bonds.forEachIndexed { index, phone ->
                            val armed = confirming == index
                            ArrowPreference(
                                title = phone.address,
                                summary = when {
                                    // The consequence differs, so the label does
                                    // too: removing your own entry locks you out
                                    // of this door until you pair again.
                                    phone.isSelf && armed -> "再次点击：本机将失去访问权限"
                                    phone.isSelf -> "本机 · 移除后需要重新配对"
                                    armed -> "再次点击确认移除"
                                    else -> "移除这台设备的访问权限"
                                },
                                enabled = !busy,
                                onClick = {
                                    if (armed) {
                                        vm.unpair(index)
                                        confirming = -1
                                    } else {
                                        confirming = index
                                    }
                                },
                            )
                        }
                    }
                }

                if (bonds.isNotEmpty() && confirming >= 0) {
                    val armed = bonds.getOrNull(confirming)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Muted(
                            if (armed?.isSelf == true) {
                                "已选中本机，再次点击就会把本机从门锁移除，连接随后断开。"
                            } else {
                                "已选中 ${armed?.address ?: ""}，再次点击即可移除。"
                            }
                        )
                    }
                }

                GroupTitle("说明")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Muted("· 地址由门锁记录，手机重置或换系统后可能不同。")
                        Muted("· 门锁最多记住 8 台设备，满了以后新的配对会被拒绝。")
                    }
                }

                ClickableCard(
                    title = "重新读取",
                    summary = if (busy) "正在读取…" else "与门锁上的记录核对一次",
                    enabled = !busy,
                    onClick = { vm.refreshBonds() },
                )
            }
        }
    }
}
