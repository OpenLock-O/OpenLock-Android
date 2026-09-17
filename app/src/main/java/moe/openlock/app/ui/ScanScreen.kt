package moe.openlock.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.openlock.app.vm.LockViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Finding locks.
 *
 * Filtering happens on the service UUID and the name prefix, so anything listed
 * here is a lock running the OpenLock firmware.
 */
@Composable
fun ScanScreen(
    vm: LockViewModel,
    onBack: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()

    // Scan while this screen is on top; stop as soon as it goes away.
    LaunchedEffect(Unit) {
        vm.startScan()
        try {
            while (true) kotlinx.coroutines.delay(1_000)
        } finally {
            vm.stopScan()
        }
    }

    LockScaffold(
        title = "添加门锁",
        subtitle = if (scanning) "正在扫描…" else "扫描结束",
        onBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            MessageBanner(vm)

            if (results.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                    Muted("正在寻找处于广播状态的门锁。请确认门锁已上电，并且距离足够近。")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(results, key = { it.id }) { seen ->
                    val known = devices.any { it.id == seen.id }
                    Card(
                        onClick = { vm.connect(seen); onPicked(seen.id) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        BasicComponent(
                            title = seen.name,
                            summary = if (known) {
                                "信号 ${seen.rssi} dBm · 已添加"
                            } else {
                                "信号 ${seen.rssi} dBm · 未添加"
                            },
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
            }

            if (results.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Muted(
                        "第一次连接时系统会询问是否配对，同意即可。" +
                            "如果门锁已经有主人，它会直接拒绝新手机，需要主人先开启配对模式。"
                    )
                }
            }
        }
    }
}
