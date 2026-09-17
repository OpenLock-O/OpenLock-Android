package moe.openlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import moe.openlock.app.ble.BleLimits
import moe.openlock.app.ble.ConnState
import moe.openlock.app.data.LockDevice
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Pairing mode: let another phone join this lock.
 *
 * The window state shown here comes from the lock's own status document, not
 * from a local guess, so it stays right when the window was opened by another
 * phone or from the serial console. The lock reports it as a countdown while a
 * deadline is running and as "no deadline" on an unowned lock - the two cases
 * are worded differently because only one of them is time-limited.
 *
 * The three things that are easy to get wrong are stated on the page rather than
 * assumed: this phone must already be paired, only the first phone to pair takes
 * the window, and Just Works carries no man-in-the-middle protection.
 */
@Composable
fun PairingScreen(
    vm: LockViewModel,
    device: LockDevice,
    onBack: () -> Unit,
) {
    val connState by vm.connState.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    val live = device.id == activeId && connState is ConnState.Ready
    val window = if (live) status?.pairing else null
    val open = window != null && window != 0

    // The lock only reports the countdown when it is asked, so the page polls
    // while it is on screen. One refresh per second is enough when a deadline is
    // running; the slower interval is for the unowned case, where the number
    // does not change and polling is only there to notice a phone pairing.
    LaunchedEffect(live, open) {
        if (!live) return@LaunchedEffect
        while (true) {
            vm.refresh()
            delay(if (open) 1_000 else 3_000)
        }
    }

    LockScaffold(
        title = "配对模式",
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

            GroupTitle("当前状态")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateDot(pairingDotColor(window = window, live = live))
                        Text(
                            text = pairingHeadline(window = window, live = live),
                            style = MiuixTheme.textStyles.title4,
                        )
                    }
                    Muted(pairingDetail(window = window, live = live))
                }
            }

            if (!live) {
                ClickableCard(
                    title = "连接门锁",
                    summary = "连接后才能开关配对模式",
                    onClick = { vm.connect(device) },
                )
            }

            GroupTitle("操作")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.openPairingWindow() },
                    enabled = live && !open,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            !live -> "需要先连接门锁"
                            open -> "配对窗口已经开着"
                            else -> "打开配对模式（${BleLimits.PAIR_WINDOW_SECONDS} 秒）"
                        }
                    )
                }
                if (open) {
                    Muted("窗口开着的时候不需要再点一次；它会在有人配对、超时或者关闭后结束。")
                }
            }

            GroupTitle("会发生什么")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StepRow("1", "在另一台手机上安装本应用，并打开扫描列表。")
                    StepRow("2", "在这台手机上打开配对模式，窗口开始计时。")
                    StepRow("3", "在那台手机上选择这把门锁，同意系统的配对提示。")
                    StepRow("4", "第一台成功配对的手机占用窗口，窗口随即结束。")
                }
            }

            GroupTitle("前提")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Muted("· 本机必须已经和这把门锁配对，否则命令会被拒绝。")
                    Muted("· 门锁需要在蓝牙范围内。")
                    Muted("· 门锁最多记住 8 台设备，满了以后新的配对会被拒绝。")
                }
            }

            GroupTitle("安全提醒")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Muted(
                        "门锁的配对没有中间人保护。窗口开着的时候，" +
                            "范围内任何一台手机都可能抢先完成配对，成为新的所有者之一。"
                    )
                    Muted("请在你能信任的环境里添加手机，添加完马上离开这个页面。")
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Muted(
                    "如果门锁提示“已经有主人”，说明它的配对窗口是关着的，这是正常的：" +
                        "只有已配对的手机或串口控制台能重新打开它。"
                )
            }
        }
    }
}

/** A numbered step: the marker is a fixed-size box so the text lines up. */
@Composable
private fun StepRow(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = number,
            color = MiuixTheme.colorScheme.primary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.width(14.dp),
        )
        Muted(text, modifier = Modifier.weight(1f))
    }
}

/**
 * Green while a deadline is running, grey when the window is shut.
 *
 * An unowned lock gets its own colour rather than the "open" colour: it is open
 * for a different reason, and the difference matters to whoever is reading it.
 */
@Composable
private fun pairingDotColor(window: Int?, live: Boolean): Color = when {
    !live || window == null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    window > 0 -> MiuixTheme.colorScheme.primary
    window < 0 -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    else -> MiuixTheme.colorScheme.disabledOnSurface
}

private fun pairingHeadline(window: Int?, live: Boolean): String = when {
    !live -> "未连接"
    window == null -> "状态未知"
    window > 0 -> "配对窗口已打开"
    window < 0 -> "配对窗口一直开着"
    else -> "配对窗口已关闭"
}

private fun pairingDetail(window: Int?, live: Boolean): String = when {
    !live -> "连接门锁后，这里会显示门锁上真实的配对窗口状态。"
    window == null -> "还没有读到门锁的状态。"
    window > 0 -> "还剩约 $window 秒。第一台成功配对的手机就会占用这个窗口。"
    window < 0 -> "门锁上没有任何已配对设备，因此它对任何手机开放，没有时间限制。"
    else -> "门锁已经有主人，新的手机在连接时会被直接拒绝。"
}
