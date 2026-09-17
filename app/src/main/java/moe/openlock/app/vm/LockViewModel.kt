package moe.openlock.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.openlock.app.ble.Protocol
import moe.openlock.app.ble.ScannedLock
import moe.openlock.app.data.LockDevice
import moe.openlock.app.data.LockRepository
import moe.openlock.app.data.SettingsStore

/** Everything the UI reads and the actions it can take. */
class LockViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LockRepository(app, viewModelScope)

    val settings: SettingsStore = (app as moe.openlock.app.OpenLockApp).settings

    val devices: StateFlow<List<LockDevice>> = repo.devices
    val scanResults: StateFlow<List<ScannedLock>> = repo.scanResults
    val scanning: StateFlow<Boolean> = repo.scanning
    val connState = repo.connState
    val activeId: StateFlow<String?> = repo.activeId
    val status = repo.status

    /** The active lock's own bond list, and whether it has been read yet. */
    val bonds: StateFlow<List<Protocol.BondedPhone>> = repo.bonds
    val bondsLoaded: StateFlow<Boolean> = repo.bondsLoaded

    /** True while a bond re-read or an unpair is in flight, to disable the rows. */
    private val _bondsBusy = MutableStateFlow(false)
    val bondsBusy: StateFlow<Boolean> = _bondsBusy

    /** Transient notes, newest last. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    init {
        viewModelScope.launch {
            repo.messages.collect { note ->
                _log.value = (_log.value + note).takeLast(20)
            }
        }
    }

    fun dismissLog() {
        _log.value = emptyList()
    }

    // ---- device list ------------------------------------------------------

    fun startScan() = repo.startScan()

    fun stopScan() = repo.stopScan()

    fun connect(device: LockDevice) {
        viewModelScope.launch { repo.connect(device.address, device.id) }
    }

    /**
     * Connect to a device found by scanning. A lock this phone has not paired
     * with yet is not in [devices] at all, so it is added as an untrusted entry
     * first: the list is what the rest of the UI hangs off.
     */
    fun connect(seen: ScannedLock) {
        viewModelScope.launch {
            repo.remember(seen)
            repo.connect(seen.address, seen.id)
        }
    }

    fun disconnect() = repo.disconnect()

    fun forget(id: String) = repo.forget(id)

    fun rename(id: String, alias: String?) = repo.rename(id, alias)

    // ---- actions ----------------------------------------------------------

    fun unlock() = viewModelScope.launch { repo.unlock().also { refreshSoon() } }

    fun lock() = viewModelScope.launch { repo.lock().also { refreshSoon() } }

    fun stop() = viewModelScope.launch { repo.stop() }

    fun refresh() = viewModelScope.launch { repo.refresh() }

    /** Open the lock's pairing window so another phone can be added. */
    fun openPairingWindow() = viewModelScope.launch { repo.openPairingWindow() }

    // ---- bonded phones ----------------------------------------------------

    /** Re-read the lock's bond list. Safe to call from a screen's launch effect. */
    fun refreshBonds() = viewModelScope.launch {
        _bondsBusy.value = true
        try {
            repo.refreshBonds()
        } finally {
            _bondsBusy.value = false
        }
    }

    fun unpair(index: Int) = viewModelScope.launch {
        _bondsBusy.value = true
        try {
            repo.unpair(index)
        } finally {
            _bondsBusy.value = false
        }
    }

    /** Re-learn the bolt's travel; the result arrives as an event push. */
    fun calibrate() = viewModelScope.launch { repo.calibrate() }

    private fun refreshSoon() {
        viewModelScope.launch { repo.refresh() }
    }
}
