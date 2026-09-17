package moe.openlock.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the locks this phone has been paired with.
 *
 * Stored in the app's private SharedPreferences as JSON. Two things are kept
 * per lock: how to find it again (name, last address) and the last state we saw,
 * so the list is still meaningful when the lock is out of range.
 *
 * The id is the suffix of the advertised name rather than the Bluetooth
 * address, because phones resolve peers through rotating addresses and the
 * address of a reconnecting lock changes.
 */
class DeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("openlock.devices", Context.MODE_PRIVATE)

    private val tag = "DeviceStore"

    fun load(): List<LockDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> fromJson(array.getJSONObject(i)) }
        }.onFailure { Log.w(tag, "device list is unreadable, starting empty", it) }
            .getOrDefault(emptyList())
    }

    fun save(devices: List<LockDevice>) {
        val array = JSONArray()
        devices.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private fun toJson(d: LockDevice) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("address", d.address)
        put("alias", d.alias ?: JSONObject.NULL)
        put("locked", d.locked ?: JSONObject.NULL)
        put("battery", d.battery ?: JSONObject.NULL)
        put("lastSeen", d.lastSeen)
        put("trusted", d.trusted)
    }

    private fun fromJson(o: JSONObject): LockDevice? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return null
        return LockDevice(
            id = id,
            name = name,
            address = o.optString("address"),
            alias = o.optString("alias").takeIf { it.isNotEmpty() && !o.isNull("alias") },
            locked = if (o.isNull("locked")) null else o.optBoolean("locked"),
            battery = if (o.isNull("battery")) null else o.optInt("battery"),
            lastSeen = o.optLong("lastSeen"),
            trusted = o.optBoolean("trusted", false),
        )
    }

    private companion object {
        const val KEY_DEVICES = "devices"
    }
}
