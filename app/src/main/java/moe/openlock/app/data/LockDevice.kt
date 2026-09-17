package moe.openlock.app.data

import moe.openlock.app.ble.Protocol

/** A lock this phone knows about. */
data class LockDevice(
    /** Stable identity: the suffix of the advertised name. */
    val id: String,
    /** Full advertised name, e.g. `OpenLock-D9FA92`. */
    val name: String,
    /** Last known address, used to reconnect. Phones rotate peer addresses, so
     *  this is a hint rather than an identity. */
    val address: String,
    /** Optional label the user chose ("Front door"). */
    val alias: String? = null,

    // --- last observed state, cached so the list is useful offline ---------
    val locked: Boolean? = null,
    val battery: Int? = null,
    val lastSeen: Long = 0L,
    /** False until a command has succeeded at least once. */
    val trusted: Boolean = false,
) {
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: name

    fun applyStatus(status: Protocol.Status, atMillis: Long): LockDevice = copy(
        locked = status.locked,
        battery = status.battery,
        lastSeen = atMillis,
        trusted = true,
    )
}
