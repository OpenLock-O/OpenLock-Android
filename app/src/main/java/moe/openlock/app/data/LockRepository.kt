package moe.openlock.app.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.openlock.app.ble.BleLimits
import moe.openlock.app.ble.ConnState
import moe.openlock.app.ble.LockConnection
import moe.openlock.app.ble.LockException
import moe.openlock.app.ble.LockScanner
import moe.openlock.app.ble.Protocol
import moe.openlock.app.ble.ScannedLock

/**
 * Owns every lock this phone knows about and the one live connection.
 *
 * Multi-device by design: the list is persisted, and connecting to a lock
 * closes the previous session. One connection at a time keeps the state
 * machine honest - the protocol is lockstep and a lock is a single peer.
 *
 * Commands are timestamped with the phone's clock, because the lock refuses
 * anything older than [BleLimits.FRESHNESS_SECONDS] and refuses to move its own
 * clock backwards. See [execute] for how the two clock errors are recovered.
 */
class LockRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val tag = "LockRepository"

    private val store = DeviceStore(appContext)
    private val settings = SettingsStore(appContext)
    private val scanner = LockScanner(appContext)

    private val adapter
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _devices = MutableStateFlow(store.load())
    val devices: StateFlow<List<LockDevice>> = _devices

    private val _scanResults = MutableStateFlow<List<ScannedLock>>(emptyList())
    val scanResults: StateFlow<List<ScannedLock>> = _scanResults

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _connState = MutableStateFlow<ConnState>(ConnState.Idle)
    val connState: StateFlow<ConnState> = _connState

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _status = MutableStateFlow<Protocol.Status?>(null)

    /** Last status seen for the active lock. */
    val status: StateFlow<Protocol.Status?> = _status

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** One-line notes and errors worth showing to the user. */
    val messages: SharedFlow<String> = _messages

    private val _bonds = MutableStateFlow<List<Protocol.BondedPhone>>(emptyList())

    /** The phones bonded on the active lock, as of the last [refreshBonds]. */
    val bonds: StateFlow<List<Protocol.BondedPhone>> = _bonds

    private val _bondsLoaded = MutableStateFlow(false)

    /**
     * False until a [BONDS][Protocol.bonds] reply has been parsed for the
     * current connection.
     *
     * An empty [bonds] list has two very different meanings - "no phones left"
     * and "not asked yet" - and the management screen has to tell them apart,
     * because only the first one means the lock is unowned.
     */
    val bondsLoaded: StateFlow<Boolean> = _bondsLoaded

    private var connection: LockConnection? = null
    private var scanJob: Job? = null
    private val connJobs = mutableListOf<Job>()

    /** The lock's idea of "now", learned from TIME and from clock errors. */
    private var lockClock: Long = 0

    private fun nowUnix(): Long = System.currentTimeMillis() / 1000

    // ---- device list ------------------------------------------------------

    private fun persist(next: List<LockDevice>) {
        _devices.value = next
        store.save(next)
    }

    private fun upsert(next: LockDevice) {
        val list = _devices.value.toMutableList()
        val at = list.indexOfFirst { it.id == next.id }
        if (at >= 0) list[at] = next else list.add(next)
        persist(list.sortedBy { it.displayName.lowercase() })
    }

    fun forget(id: String) {
        if (_activeId.value == id) disconnect()
        persist(_devices.value.filterNot { it.id == id })
    }

    /**
     * Add a lock seen while scanning, before it has been paired.
     *
     * A lock the phone has never paired with is not in the list yet, and the
     * list is what the UI navigates. Marking it untrusted keeps it honest:
     * nothing claims to be reachable until a command actually succeeds.
     */
    fun remember(seen: ScannedLock) {
        val existing = _devices.value.firstOrNull { it.id == seen.id }
        if (existing == null) {
            upsert(
                LockDevice(
                    id = seen.id,
                    name = seen.name,
                    address = seen.address,
                    trusted = false,
                )
            )
        } else if (existing.address != seen.address) {
            upsert(existing.copy(address = seen.address))
        }
    }

    fun rename(id: String, alias: String?) {
        _devices.value.firstOrNull { it.id == id }?.let { upsert(it.copy(alias = alias)) }
    }

    // ---- scanning ---------------------------------------------------------

    fun startScan() {
        if (_scanning.value) return
        if (!scanner.isBluetoothOn) {
            _messages.tryEmit("Bluetooth is off")
            return
        }
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = scope.launch {
            runCatching {
                scanner.scan().collect { seen ->
                    // Keep the strongest sighting per lock.
                    val list = _scanResults.value.toMutableList()
                    val at = list.indexOfFirst { it.id == seen.id }
                    if (at < 0) list.add(seen)
                    else if (seen.rssi > list[at].rssi) list[at] = seen
                    _scanResults.value = list.sortedByDescending { it.rssi }
                }
            }.onFailure { Log.w(tag, "scan stopped: ${it.message}") }
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    // ---- connection -------------------------------------------------------

    /**
     * Connect to a lock by its advertised identity.
     *
     * `address` is a hint; a lock this phone has not paired with may be at a
     * different address than the last time it was seen. The name suffix is the
     * identity, so a mismatch is corrected by the caller passing a fresh
     * [ScannedLock] rather than by guessing here.
     */
    suspend fun connect(address: String, deviceId: String) {
        disconnect()
        lockClock = 0
        _status.value = null
        // The bond list belongs to the lock, not to this session; a stale one
        // would let the UI offer an unpair index from a different connection.
        _bonds.value = emptyList()
        _bondsLoaded.value = false

        val remote = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (remote == null) {
            fail("unknown Bluetooth address $address")
            return
        }

        val conn = LockConnection(appContext, remote)
        connection = conn
        _activeId.value = deviceId

        // Mirror connection state and status pushes into our own flows. The
        // jobs are cancelled in disconnect(), otherwise every reconnect would
        // leave two more collectors feeding stale state into the UI.
        connJobs += scope.launch { conn.state.collect { _connState.value = it } }
        connJobs += scope.launch {
            conn.events.collect { pushed ->
                _status.value = pushed
                touchActive(pushed)
            }
        }

        try {
            conn.connect()
            refreshClock(conn)
            refreshStatus(conn)
        } catch (e: LockException) {
            _messages.tryEmit(e.message ?: "connection failed")
        } catch (e: Exception) {
            _messages.tryEmit(e.message ?: "connection failed")
        }
    }

    private fun fail(message: String) {
        _connState.value = ConnState.Failed(message)
        _messages.tryEmit(message)
    }

    fun disconnect() {
        connection?.close()
        connection = null
        connJobs.forEach { it.cancel() }
        connJobs.clear()
        _activeId.value = null
        _connState.value = ConnState.Idle
        _bonds.value = emptyList()
        _bondsLoaded.value = false
    }

    private fun touchActive(status: Protocol.Status) {
        val id = _activeId.value ?: return
        _devices.value.firstOrNull { it.id == id }?.let { upsert(it.applyStatus(status, nowUnix())) }
    }

    private suspend fun refreshClock(conn: LockConnection) {
        val reply = conn.send(Protocol.time())
        if (reply is Protocol.Reply.Ok) {
            Protocol.parseTime(reply)?.let { lockClock = it.unix }
        }
    }

    private suspend fun refreshStatus(conn: LockConnection) {
        val reply = conn.send(Protocol.status())
        if (reply is Protocol.Reply.Ok) {
            Protocol.Status.parse(reply.body)?.let {
                _status.value = it
                touchActive(it)
            }
        }
    }

    /**
     * Run a command, keeping the timestamp valid.
     *
     * The lock rejects a command older than two minutes (`ERR stale`) and any
     * timestamp behind its own clock (`ERR time-behind`); both errors carry the
     * lock's current time. One retry with that value is enough, and retrying
     * more would risk the eight-rejects-per-connection disconnect.
     *
     * The lambda is the last parameter so callers can use trailing-lambda
     * syntax; a trailing lambda always binds to the final parameter.
     */
    private suspend fun execute(
        timeoutMs: Long = 12_000,
        line: (unix: Long) -> String,
    ): Protocol.Reply {
        val conn = connection ?: throw LockException("not connected")
        var unix = maxOf(nowUnix(), lockClock)
        var reply = conn.send(line(unix), timeoutMs)

        if (reply is Protocol.Reply.Err) {
            val code = reply.code
            val suggested = Protocol.lockNowFrom(reply)
            if ((code == "stale" || code == "time-behind") && suggested != null) {
                Log.i(tag, "$code from the lock, retrying with its clock $suggested")
                lockClock = suggested
                unix = maxOf(nowUnix(), suggested)
                reply = conn.send(line(unix), timeoutMs)
            }
        }
        return reply
    }

    // ---- actions ----------------------------------------------------------

    /** Returns true when the lock accepted the command. */
    suspend fun unlock(): Boolean = action("unlock", Protocol::unlock)

    suspend fun lock(): Boolean = action("lock", Protocol::lock)

    suspend fun stop(): Boolean = action("stop", Protocol::stop)

    private suspend fun action(
        what: String,
        build: (Long) -> String,
    ): Boolean = try {
        val reply = execute { unix -> build(unix) }
        when (reply) {
            is Protocol.Reply.Ok -> {
                _messages.tryEmit("$what: accepted")
                true
            }
            is Protocol.Reply.Err -> {
                _messages.tryEmit("$what failed: ${explain(reply)}")
                false
            }
        }
    } catch (e: Exception) {
        _messages.tryEmit("$what failed: ${e.message ?: "unknown error"}")
        false
    }

    /** Open the lock's pairing window so another phone can join. */
    suspend fun openPairingWindow(): Boolean = try {
        val reply = execute { unix -> Protocol.pair(unix) }
        when (reply) {
            is Protocol.Reply.Ok -> {
                _messages.tryEmit("pairing window open for ${BleLimits.PAIR_WINDOW_SECONDS} s")
                true
            }
            is Protocol.Reply.Err -> {
                _messages.tryEmit("could not open pairing: ${explain(reply)}")
                false
            }
        }
    } catch (e: Exception) {
        _messages.tryEmit("could not open pairing: ${e.message ?: "unknown error"}")
        false
    }

    suspend fun refresh() {
        val conn = connection ?: return
        runCatching { refreshStatus(conn) }
            .onFailure { Log.w(tag, "status refresh failed: ${it.message}") }
    }

    // ---- bonded phones ----------------------------------------------------

    /**
     * Re-read the lock's own list of bonded phones.
     *
     * Returns false when the list could not be read, which is not the same as an
     * empty list; [bondsLoaded] distinguishes them for the UI.
     */
    suspend fun refreshBonds(): Boolean = try {
        val reply = execute { unix -> Protocol.bonds(unix) }
        when (reply) {
            is Protocol.Reply.Ok -> {
                val parsed = Protocol.parseBonds(reply.body)
                if (parsed == null) {
                    _messages.tryEmit("门锁返回的设备列表无法识别")
                    false
                } else {
                    _bonds.value = parsed
                    _bondsLoaded.value = true
                    true
                }
            }
            is Protocol.Reply.Err -> {
                _messages.tryEmit("读取已配对设备失败：${explain(reply)}")
                false
            }
        }
    } catch (e: Exception) {
        _messages.tryEmit("读取已配对设备失败：${e.message ?: "未知错误"}")
        false
    }

    /**
     * Forget one bonded phone.
     *
     * [index] is a position in [bonds], so the list is re-read first and the
     * index range-checked against the fresh copy. Unpairing is the one command
     * where acting on a stale list is destructive: the phone at that position
     * may have changed since the user tapped.
     */
    suspend fun unpair(index: Int): Boolean = try {
        if (!refreshBonds()) {
            false
        } else {
            val current = _bonds.value
            if (index !in current.indices) {
                _messages.tryEmit("这台设备已经不在列表里了，请刷新后重试")
                false
            } else {
                val target = current[index]
                if (target.isSelf) unpairSelf(index) else unpairOther(index, target)
            }
        }
    } catch (e: Exception) {
        _messages.tryEmit("移除失败：${e.message ?: "未知错误"}")
        false
    }

    /**
     * Remove another phone. This one answers normally, so the reply is awaited
     * and the list re-read to confirm the entry is gone.
     */
    private suspend fun unpairOther(index: Int, target: Protocol.BondedPhone): Boolean {
        val reply = execute { unix -> Protocol.unpair(unix, index) }
        return when (reply) {
            is Protocol.Reply.Ok -> {
                _messages.tryEmit("已移除 ${target.address}")
                refreshBonds()
                true
            }
            is Protocol.Reply.Err -> {
                _messages.tryEmit("移除失败：${explain(reply)}")
                refreshBonds()
                false
            }
        }
    }

    /**
     * Remove this phone's own bond.
     *
     * No reply is awaited, because the lock closes the link before it answers -
     * see [LockConnection.sendNoReply]. The command is built straight from the
     * known clock instead of going through [execute], and that is safe here
     * because [refreshBonds] succeeded immediately before this: a `BONDS` that
     * came back means the timestamp was already accepted, so there is nothing
     * for the stale/time-behind retry to recover from.
     */
    private fun unpairSelf(index: Int): Boolean {
        val conn = connection ?: run {
            _messages.tryEmit("未连接门锁")
            return false
        }
        val unix = maxOf(nowUnix(), lockClock)
        conn.sendNoReply(Protocol.unpair(unix, index))
        _bonds.value = _bonds.value.filterIndexed { i, _ -> i != index }
        _messages.tryEmit("已从门锁移除本机，连接即将断开")
        return true
    }

    /**
     * Re-learn the bolt's travel.
     *
     * The lock answers as soon as the job is queued and reports the outcome as
     * an `EVT` push, so the status is refreshed afterwards rather than trusted
     * from the reply.
     */
    suspend fun calibrate(): Boolean = try {
        val reply = execute(timeoutMs = 20_000) { unix -> Protocol.calibrate(unix) }
        when (reply) {
            is Protocol.Reply.Ok -> {
                _messages.tryEmit("已开始行程校准，门锁会往返一次两端")
                true
            }
            is Protocol.Reply.Err -> {
                _messages.tryEmit("校准失败：${explain(reply)}")
                false
            }
        }
    } catch (e: Exception) {
        _messages.tryEmit("校准失败：${e.message ?: "未知错误"}")
        false
    }

    private fun explain(reply: Protocol.Reply.Err): String = when (reply.code) {
        "stale" -> "the phone clock is behind the lock"
        "time-behind" -> "the phone clock is behind the lock"
        "busy" -> "the mechanism is busy"
        "unknown" -> "the lock did not recognise that command"
        // A firmware with a PIN configured answers these. The app has no way to
        // supply one and the lock's PIN is not in use; say so plainly rather
        // than reporting a wrong PIN that was never sent.
        "pin-required", "pin", "locked-out" ->
            "this lock requires a PIN the app does not support"
        else -> reply.reason
    }
}
