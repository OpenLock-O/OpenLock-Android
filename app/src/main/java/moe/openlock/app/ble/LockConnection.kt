package moe.openlock.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the connection is doing right now, for the UI to render. */
sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Discovering : ConnState

    /** Waiting for the system pairing to complete; the OS shows its own prompt. */
    data object Bonding : ConnState
    data object EnablingEvents : ConnState
    data object Ready : ConnState

    /**
     * @param needsOwner true when the lock hung up because it already has an
     *   owner and its pairing window is shut. Retrying will not help until the
     *   owner opens the window.
     */
    data class Failed(val message: String, val needsOwner: Boolean = false) : ConnState
    data object Closed : ConnState
}

/** A failure the UI can explain to the user. */
class LockException(message: String, val needsOwner: Boolean = false) : Exception(message)

/**
 * One GATT session with one lock.
 *
 * Lifecycle: [connect] resolves once notifications are enabled, then [send] can
 * be called. The protocol is lockstep - one command, one reply - so [send]
 * suspends until the reply arrives. `EVT` lines are routed to [events] instead
 * of being mistaken for a reply.
 *
 * The caller is responsible for holding BLUETOOTH_CONNECT; the UI only reaches
 * this class after the permission has been granted.
 */
@SuppressLint("MissingPermission")
class LockConnection(
    private val context: Context,
    val device: BluetoothDevice,
) {
    private val tag = "LockConnection"

    /** Drives the "wait for the bond, then subscribe" step off the GATT callback. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var gatt: BluetoothGatt? = null
    private var cmd: BluetoothGattCharacteristic? = null
    private var event: BluetoothGattCharacteristic? = null

    /** Negotiated ATT MTU; a write carries at most this minus 3 bytes. */
    @Volatile
    private var mtu: Int = BleLimits.FALLBACK_MTU

    private val assembler = LineAssembler()
    private val inbound = Channel<String>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _events = MutableSharedFlow<Protocol.Status>(extraBufferCapacity = 8)

    /** Unsolicited `EVT` status pushes from the lock. */
    val events: SharedFlow<Protocol.Status> = _events

    /** Completed when the link is usable, or failed when it is not. */
    private var ready: CompletableDeferred<Unit>? = null

    /** Completed when the system reports the device bonded (or that it failed). */
    private var bonded: CompletableDeferred<Unit>? = null

    /** How many times discovery has been retried against a stale attribute cache. */
    private var discoveryAttempts = 0

    /** Whether the post-bond subscribe retry has already been used. */
    private var subscribeRetried = false

    /**
     * The lock requires encryption on the CCCD, so the phone has to be bonded
     * before it can subscribe. Relying on the failed write to trigger pairing
     * is not portable - Android usually does start bonding on
     * GATT_INSUFFICIENT_AUTHENTICATION, but other stacks just return the error -
     * so bond explicitly and wait for the system to say it finished.
     */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val target: BluetoothDevice? =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (target?.address != device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> bonded?.complete(Unit)
                BluetoothDevice.BOND_NONE -> {
                    // Only a failure if we were the ones bonding; an unpair by
                    // the user also lands here.
                    val was = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    if (was == BluetoothDevice.BOND_BONDING) {
                        bonded?.completeExceptionally(LockException("pairing was rejected"))
                    }
                }
            }
        }
    }

    private var receiverRegistered = false

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        context.registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            // A protected system broadcast: not exported is correct here.
            Context.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnState.Discovering
                    if (!g.discoverServices()) fail("could not start service discovery")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    when {
                        // The lock drops unbonded phones while pairing is shut.
                        status == HCI_REMOTE_USER_TERMINATED -> fail(
                            "the lock refused this phone: it already has an owner",
                            needsOwner = true,
                        )
                        status != BluetoothGatt.GATT_SUCCESS -> fail("disconnected (status $status)")
                        _state.value == ConnState.Ready -> _state.value = ConnState.Closed
                        else -> fail("disconnected before the link was ready")
                    }
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service discovery failed (status $status)")
                return
            }

            val service = g.getService(BleUuids.SERVICE)
            cmd = service?.getCharacteristic(BleUuids.CMD)
            event = service?.getCharacteristic(BleUuids.EVENT)

            if (cmd != null && event != null) {
                discoveryAttempts = 0
                _state.value = ConnState.EnablingEvents
                // Ask for a large MTU first: it shrinks the number of writes per
                // command and the number of notifications per status line.
                if (!g.requestMtu(BleLimits.REQUESTED_MTU)) subscribe(g)
                return
            }

            // Android caches a device's attribute table and only drops it when
            // the device says the database changed. A table read before this
            // firmware was flashed - or cached under a different bond - comes
            // back without the characteristics, which looks exactly like "this
            // is not an OpenLock". Clearing the cache and asking again is the
            // only way out; a plain re-discovery just returns the same cache.
            if (discoveryAttempts < MAX_DISCOVERY_ATTEMPTS && refreshAttributeCache(g)) {
                discoveryAttempts++
                Log.w(
                    tag,
                    "attribute table incomplete, cleared the cache and re-discovering " +
                        "(attempt $discoveryAttempts)",
                )
                return // onServicesDiscovered fires again when the re-read finishes
            }

            fail(
                when {
                    service == null -> "this device does not expose the OpenLock service"
                    else -> "the OpenLock service is missing its characteristics"
                },
            )
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newMtu > 0) {
                mtu = newMtu
                Log.i(tag, "ATT MTU is $newMtu")
            }
            // The CCCD needs an encrypted link, so make sure the phone is bonded
            // before subscribing rather than relying on the failed write to
            // start pairing.
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                subscribe(g)
            } else {
                startBonding(g)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnState.Ready
                ready?.complete(Unit)
                return
            }

            // The lock only lets a bonded phone subscribe. A refusal for lack of
            // security means the bond did not happen (or was dropped since), so
            // pair and try once more rather than reporting a security error the
            // user has no way to act on.
            val securityRefusal =
                status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                    status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            if (securityRefusal && !subscribeRetried) {
                subscribeRetried = true
                Log.w(tag, "subscribe refused (status $status); bonding and retrying once")
                startBonding(g)
                return
            }
            fail("could not subscribe to events (status $status)")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            for (line in assembler.feed(value)) {
                if (line.startsWith("EVT")) {
                    Protocol.parseEvent(line)?.let { _events.tryEmit(it) }
                } else {
                    inbound.trySend(line)
                }
            }
        }
    }

    /**
     * Pair with the lock, then subscribe.
     *
     * Bonding is asked for explicitly rather than left to the failed encrypted
     * write: Android usually does start pairing on GATT_INSUFFICIENT_
     * AUTHENTICATION, but not every stack does (Windows, at least, just returns
     * the error), so relying on it would make adding a lock unreliable.
     */
    private fun startBonding(g: BluetoothGatt) {
        _state.value = ConnState.Bonding

        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            // Already in flight - the system may have started it for us.
            awaitBondThenSubscribe(g)
            return
        }

        bonded = CompletableDeferred()
        registerBondReceiver()
        if (!device.createBond()) {
            // Some stacks refuse createBond() but will still pair on the
            // encrypted access itself; try that rather than failing outright.
            Log.w(tag, "createBond() was refused; relying on the CCCD write")
            unregisterBondReceiver()
            bonded = null
            subscribe(g)
            return
        }
        awaitBondThenSubscribe(g)
    }

    /**
     * Drop Android's cached attribute table so the next discovery re-reads the
     * device. `refresh()` is hidden API, so this is best-effort: it returns
     * false when the platform will not let us call it, and the caller then
     * reports the real problem instead of retrying forever.
     */
    private fun refreshAttributeCache(g: BluetoothGatt): Boolean = try {
        val refresh = g.javaClass.getMethod("refresh")
        val ok = refresh.invoke(g) as? Boolean ?: false
        Log.i(tag, "BluetoothGatt.refresh() -> $ok")
        ok
    } catch (e: Exception) {
        Log.w(tag, "cannot clear the GATT cache: ${e.message}")
        false
    }

    /**
     * Wait for the system to finish bonding, then subscribe. The bond is
     * negotiated on the live link, so the same [BluetoothGatt] stays valid and
     * comes out of it encrypted.
     */
    private fun awaitBondThenSubscribe(g: BluetoothGatt) {
        val deferred = bonded ?: run { subscribe(g); return }
        scope.launch {
            try {
                deferred.await()
                Log.i(tag, "bonded")
                unregisterBondReceiver()
                bonded = null
                subscribe(g)
            } catch (e: Exception) {
                unregisterBondReceiver()
                bonded = null
                fail(e.message ?: "pairing with the lock failed")
            }
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val e = event ?: return fail("no event characteristic")
        // 0x01,0x00 enables notifications. The lock requires encryption, so this
        // only succeeds once the phone is bonded.
        if (!g.setCharacteristicNotification(e, true)) return fail("could not enable notifications")
        val cccd = e.getDescriptor(BleUuids.CCCD) ?: return fail("event has no CCCD")
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) fail("could not write the CCCD (code $rc)")
    }

    private fun fail(message: String, needsOwner: Boolean = false) {
        Log.w(tag, message)
        if (_state.value != ConnState.Closed) {
            _state.value = ConnState.Failed(message, needsOwner)
        }
        ready?.completeExceptionally(LockException(message, needsOwner))
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
    }

    /** Connect and subscribe. Throws [LockException] when the link is unusable. */
    suspend fun connect(timeoutMs: Long = 20_000) {
        val deferred = CompletableDeferred<Unit>()
        ready = deferred
        _state.value = ConnState.Connecting
        // Every Context/boolean/callback overload is deprecated as of API 37 in
        // favour of the BluetoothGattConnectionSettings form. Keep this one: it
        // is universally available and still correct, and the alternative would
        // raise minSdk and have no pre-37 fallback. The only cost is a compiler
        // deprecation note.
        @Suppress("DEPRECATION")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (g == null) {
            fail("the system refused to open a GATT client")
        } else {
            gatt = g
        }
        withTimeout(timeoutMs) { deferred.await() }
    }

    /**
     * Send one command and wait for its reply.
     *
     * A long command is split across as many writes as the negotiated MTU
     * needs; the lock reassembles lines itself, so the app may split anywhere.
     *
     * On timeout the input buffer is dropped: the late reply would otherwise be
     * read as the answer to the *next* command, and a desynced lockstep session
     * is worse than a failed one.
     */
    suspend fun send(line: String, timeoutMs: Long = 12_000): Protocol.Reply {
        require(line.length <= BleLimits.MAX_LINE) { "command line is too long" }
        writeLine("$line\n")
        return try {
            withTimeout(timeoutMs) {
                var reply: Protocol.Reply? = null
                while (reply == null) {
                    val next = inbound.receive()
                    // Events are unsolicited; the first other line is the reply.
                    if (!next.startsWith("EVT")) reply = Protocol.parseReply(next)
                }
                reply
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            drain()
            throw e
        }
    }

    /**
     * Send one command and return without waiting for its reply.
     *
     * This exists for exactly one command. Forgetting this phone's own bond
     * makes the lock tear the link down *before* it answers, so the reply can
     * never arrive and [send] would sit there until it timed out - turning a
     * successful removal into a reported failure. The disconnect is the
     * confirmation here, not a reply line.
     */
    fun sendNoReply(line: String) {
        require(line.length <= BleLimits.MAX_LINE) { "command line is too long" }
        writeLine("$line\n")
    }

    private fun writeLine(payload: String) {        val g = gatt ?: throw LockException("not connected")
        val c = cmd ?: throw LockException("not connected")
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        val chunkSize = (if (mtu > 3) mtu - 3 else 20).coerceAtMost(BleLimits.MAX_LINE)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val rc = g.writeCharacteristic(
                c,
                bytes.copyOfRange(offset, end),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            if (rc != BluetoothStatusCodes.SUCCESS) throw LockException(writeErrorText(rc))
            offset = end
        }
    }

    private fun writeErrorText(rc: Int): String = when (rc) {
        BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
            "Bluetooth permission is missing"
        BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED -> "this phone is not paired with the lock"
        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "a previous write is still in flight"
        BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "the BLE stack is not ready"
        else -> "write failed (code $rc)"
    }

    /** Drop buffered input so a timed-out command cannot desync the next one. */
    fun drain() {
        assembler.reset()
        while (inbound.tryReceive().isSuccess) { /* discard */ }
    }

    fun close() {
        unregisterBondReceiver()
        ready?.cancel()
        scope.cancel()
        closeGatt()
        _state.value = ConnState.Closed
    }

    private companion object {
        /**
         * How many times to clear the attribute cache and rediscover. One retry
         * covers the real case (a table cached from older firmware); more would
         * only spin when the device genuinely is not a lock.
         */
        const val MAX_DISCOVERY_ATTEMPTS = 2
    }
}
