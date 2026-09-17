package moe.openlock.app.ble

import java.util.UUID

/**
 * Wire constants of the lock's BLE interface.
 *
 * These mirror `BLE-PROTOCOL.md` in the firmware repository. The lock is a
 * peripheral; this app is always the central.
 */
object BleUuids {
    /** Primary service the lock advertises. Filter scans on this. */
    val SERVICE: UUID = UUID.fromString("4f70656e-4c6f-636b-0000-000000000001")

    /** Commands go here. Write, requires an encrypted and bonded link. */
    val CMD: UUID = UUID.fromString("4f70656e-4c6f-636b-0000-000000000002")

    /** Replies and events come here as notifications. */
    val EVENT: UUID = UUID.fromString("4f70656e-4c6f-636b-0000-000000000003")

    /** Standard Client Characteristic Configuration descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Standard Device Information service, readable without pairing. */
    val DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
}

object BleLimits {
    /** The lock's advertised name starts with this; the rest is its id. */
    const val NAME_PREFIX = "OpenLock-"

    /** Longest command line the lock accepts, excluding the terminator. */
    const val MAX_LINE = 254

    /** ATT MTU we ask for; the lock prefers 256 as well. */
    const val REQUESTED_MTU = 256

    /**
     * Nominal ATT MTU if negotiation never happens. A notification cannot be
     * split below ATT, so this is the floor the firmware assumes too.
     */
    const val FALLBACK_MTU = 23

    /** Commands older than this are refused as stale by the lock. */
    const val FRESHNESS_SECONDS = 120L

    /** How long the lock keeps its pairing window open after `PAIR`. */
    const val PAIR_WINDOW_SECONDS = 120
}

/** HCI reason the lock uses to refuse an unbonded phone when pairing is shut. */
const val HCI_REMOTE_USER_TERMINATED = 0x13
