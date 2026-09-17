package moe.openlock.app.ble

import org.json.JSONObject

/**
 * The line protocol spoken over the command/event characteristics.
 *
 * Every command is one ASCII line ending in `\n` and produces exactly one
 * `OK ...` or `ERR ...` line back. There are no request ids, so commands are
 * sent strictly one at a time.
 */
object Protocol {

    // ---- commands ---------------------------------------------------------
    //
    // Privileged commands carry the phone's Unix time and nothing else: the
    // device is proven by the BLE bond, and the lock's PIN is not in use. The
    // timestamp is what keeps a recorded command from being replayed - it is a
    // freshness bound, not a secret.

    fun ping() = "PING"

    fun time() = "TIME"

    fun status() = "STATUS"

    fun sync(unix: Long) = "SYNC $unix"

    /** Opens the lock's pairing window so another phone can join. */
    fun pair(unix: Long) = "PAIR $unix"

    fun unlock(unix: Long) = "UNLOCK $unix"

    fun lock(unix: Long) = "LOCK $unix"

    fun stop(unix: Long) = "STOP $unix"

    /**
     * Re-learn the travel between the end stops.
     *
     * The bolt is driven into both stops, so this takes seconds and answers
     * `OK calibrate queued ...` before the work is done; the outcome arrives as
     * an `EVT` push carrying `homed`.
     */
    fun calibrate(unix: Long) = "CALIBRATE $unix"

    /** The phones bonded on the lock itself. Read-only, but still privileged. */
    fun bonds(unix: Long) = "BONDS $unix"

    /**
     * Forget one bonded phone, by its position in the most recent [bonds] reply.
     *
     * The index is only valid against that same list - the lock re-reads it when
     * the command arrives - so callers must refresh before unpairing rather than
     * caching an index.
     */
    fun unpair(unix: Long, index: Int) = "UNPAIR $unix $index"

    // ---- replies ----------------------------------------------------------

    /** Result of one command line. */
    sealed interface Reply {
        /** First word of an error, empty for `OK`. Lets callers branch on the reason. */
        val code: String

        data class Ok(val body: String) : Reply {
            override val code: String get() = ""
        }

        data class Err(val reason: String) : Reply {
            override val code: String get() = reason.substringBefore(' ')
        }
    }

    /**
     * Parse one reply line. Anything that is neither `OK` nor `ERR` is treated
     * as a protocol violation rather than silently ignored.
     */
    fun parseReply(line: String): Reply {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("OK ") -> Reply.Ok(trimmed.removePrefix("OK "))
            trimmed == "OK" -> Reply.Ok("")
            trimmed.startsWith("ERR ") -> Reply.Err(trimmed.removePrefix("ERR "))
            trimmed == "ERR" -> Reply.Err("")
            else -> Reply.Err("malformed: $trimmed")
        }
    }

    // ---- clock helpers ----------------------------------------------------

    /** `TIME` answers `OK <unix> set=<0|1>`. */
    data class LockTime(val unix: Long, val isSet: Boolean)

    fun parseTime(reply: Reply.Ok): LockTime? {
        val parts = reply.body.split(' ')
        if (parts.isEmpty()) return null
        val unix = parts[0].toLongOrNull() ?: return null
        val set = parts.getOrNull(1)?.substringAfter('=') == "1"
        return LockTime(unix, set)
    }

    /** `ERR stale <lock_now>` and `ERR time-behind <lock_now>` carry the lock's clock. */
    fun lockNowFrom(reply: Reply.Err): Long? =
        reply.reason.substringAfter(' ', "").trim().toLongOrNull()

    // ---- bonded phones ----------------------------------------------------

    /**
     * One phone the lock remembers.
     *
     * @param address the lock's own view of the peer, formatted like Android's
     *   `BluetoothDevice.address`. Phones rotate these, so it is not a stable
     *   identity - the index in the list is what [unpair] takes.
     * @param isSelf true for the phone that asked, as far as the lock can tell.
     *   The lock can only answer this while that phone is connected.
     */
    data class BondedPhone(val address: String, val isSelf: Boolean)

    /**
     * `BONDS` answers `OK <count> <addr>[+] <addr> ...`, where the trailing `+`
     * marks the phone that asked.
     *
     * `count` is parsed and checked against the number of addresses because the
     * two disagreeing means the body was truncated - a firmware with more
     * bonds than the reply buffer holds would otherwise look like a shorter
     * list, and the caller would then unpair the wrong index.
     */
    fun parseBonds(body: String): List<BondedPhone>? {
        val parts = body.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val count = parts[0].toIntOrNull() ?: return null
        val entries = parts.drop(1)
        if (count != entries.size) return null
        return entries.map { token ->
            val self = token.endsWith("+")
            BondedPhone(address = formatAddress(token.removeSuffix("+")), isSelf = self)
        }
    }

    /**
     * `646306136449` to `64:63:06:13:64:49`.
     *
     * The firmware prints the six octets most-significant first, which is the
     * same order Android uses, so this is grouping rather than reversing.
     * Anything that is not 12 hex digits is returned untouched: showing the raw
     * token is more useful than showing nothing.
     */
    private fun formatAddress(hex: String): String {
        if (hex.length != 12 || !hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
            return hex
        }
        return hex.chunked(2).joinToString(":").uppercase()
    }

    // ---- status -----------------------------------------------------------

    /**
     * The status JSON. Unknown fields are ignored and missing ones fall back to
     * defaults, so a firmware that grows fields does not break this app.
     */
    data class Status(
        val role: String,
        val locked: Boolean,
        val homed: Boolean,
        val battery: Int?,
        val travel: Int,
        val position: Int,
        val state: String,
        val last: String,
        val bleConnected: Boolean,
        /**
         * The lock's pairing window: 0 closed, a positive countdown, or -1 open
         * with no deadline (which only happens on an unowned lock).
         */
        val pairing: Int,
    ) {
        val pairingOpen: Boolean get() = pairing != 0

        companion object {
            fun parse(json: String): Status? = runCatching {
                val o = JSONObject(json)
                Status(
                    role = o.optString("role", "indoor"),
                    locked = o.optBoolean("locked", false),
                    homed = o.optBoolean("homed", false),
                    // Absent when the firmware predates the battery field.
                    battery = if (o.has("battery")) o.optInt("battery", -1) else null,
                    travel = o.optInt("travel", 0),
                    position = o.optInt("position", 0),
                    state = o.optString("state", "unknown"),
                    last = o.optString("last", "unknown"),
                    bleConnected = o.optBoolean("ble", false),
                    // Absent when the firmware predates the field. 0 ("closed")
                    // is the honest default: it is what a lock with an owner
                    // reports, and it makes the app offer the button rather
                    // than claim a window that may not be there.
                    pairing = o.optInt("pairing", 0),
                )
            }.getOrNull()
        }
    }

    /** An `EVT {json}` line carries the same object as `STATUS`. */
    fun parseEvent(line: String): Status? {
        val body = line.trim().removePrefix("EVT").trim()
        return if (body.startsWith("{")) Status.parse(body) else null
    }
}

/**
 * Reassembles newline-terminated lines from notification payloads.
 *
 * One notification carries at most `ATT MTU - 3` bytes and the firmware splits
 * a long status line across several of them, so bytes are accumulated until a
 * `\n` shows up. Several lines in one notification are handled as well.
 */
class LineAssembler(private val maxLine: Int = 512) {
    private val buffer = StringBuilder()

    /** Feed raw bytes; returns every complete line they completed, without `\n`. */
    fun feed(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (b in data) {
            when (val c = b.toInt().toChar()) {
                '\n' -> {
                    var line = buffer.toString()
                    if (line.endsWith('\r')) line = line.dropLast(1)
                    out += line
                    buffer.setLength(0)
                }
                else -> if (buffer.length >= maxLine) buffer.setLength(0) else buffer.append(c)
            }
        }
        return out
    }

    fun reset() = buffer.setLength(0)
}
