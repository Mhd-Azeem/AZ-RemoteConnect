package com.azeem.azremoteconnect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Local-only device history: address and full certificate pin are saved; pairing codes are NEVER stored.
// A device is marked trusted only after the user explicitly chooses "Trust this device".
internal class DeviceHistory(context: Context) {
    private val preferences = context.getSharedPreferences("az_devices_v1", Context.MODE_PRIVATE)

    data class Device(val address: String, val name: String, val lastSeen: Long,
        val visitCount: Int, val trustedPin: String)

    fun all(): List<Device> {
        val arr = try { JSONArray(preferences.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        val output = mutableListOf<Device>()
        for (i in 0 until arr.length()) {
            val json = arr.optJSONObject(i) ?: continue
            val address = json.optString("address")
            if (address.isBlank()) continue
            output += Device(address, json.optString("name", address),
                json.optLong("lastSeen"), json.optInt("count", 1), json.optString("pin"))
        }
        return output.sortedByDescending { it.lastSeen }.take(32)
    }

    fun find(address: String): Device? = all().firstOrNull { it.address == address }

    private fun persist(devices: List<Device>) {
        val arr = JSONArray()
        devices.take(32).forEach { d ->
            arr.put(JSONObject().put("address", d.address).put("name", d.name)
                .put("lastSeen", d.lastSeen).put("count", d.visitCount).put("pin", d.trustedPin))
        }
        preferences.edit().putString("items", arr.toString()).apply()
    }

    fun visited(address: String) {
        val previous = find(address)
        persist(listOf(Device(address, previous?.name ?: address, System.currentTimeMillis(),
            (previous?.visitCount ?: 0) + 1, previous?.trustedPin ?: "")) +
            all().filterNot { it.address == address })
    }

    fun rename(address: String, name: String) =
        persist(all().map { if (it.address == address) it.copy(name = name.take(48)) else it })

    fun trust(address: String, fullPin: String) {
        require(fullPin.matches(Regex("[0-9A-F]{64}")))
        persist(all().map { if (it.address == address) it.copy(trustedPin = fullPin) else it })
    }

    fun forget(address: String) = persist(all().filterNot { it.address == address })
}
