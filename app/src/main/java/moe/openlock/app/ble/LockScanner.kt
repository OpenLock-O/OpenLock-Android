package moe.openlock.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** A lock seen during a scan. */
data class ScannedLock(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: android.bluetooth.BluetoothDevice,
) {
    /** The stable id: the name suffix is what survives address rotation. */
    val id: String get() = name.removePrefix(BleLimits.NAME_PREFIX)
}

/**
 * Scans for locks advertising the OpenLock service.
 *
 * The advertised name only arrives in the scan response, so active scanning is
 * required - a passive scan sees the service UUID but no name.
 */
class LockScanner(private val context: Context) {

    private val tag = "LockScanner"

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Emits every sighting; the caller decides how to merge repeats. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedLock> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(tag, "no BLE scanner available")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: result.device.name
                    ?: return
                if (!name.startsWith(BleLimits.NAME_PREFIX)) return
                trySend(
                    ScannedLock(
                        name = name,
                        address = result.device.address,
                        rssi = result.rssi,
                        device = result.device,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(tag, "scan failed: $errorCode")
                close(IllegalStateException("scan failed ($errorCode)"))
            }
        }

        // Filter on the service UUID, which is what the lock guarantees.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }.flowOn(Dispatchers.Default)
}
