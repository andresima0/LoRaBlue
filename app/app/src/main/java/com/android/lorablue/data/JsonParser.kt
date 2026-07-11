package com.android.lorablue.data

import android.util.Log
import org.json.JSONObject

/**
 * Reassembles BLE notification fragments into complete JSON messages and
 * parses them into [BleMessage]. This class owns the reassembly buffer
 * exclusively — nothing outside it should touch raw byte chunks.
 *
 * The firmware always terminates every JSON payload with '\n'. BLE may
 * still fragment a message across multiple notifications regardless of
 * negotiated MTU, so chunks are appended here and only flushed into
 * complete messages when a newline is found.
 *
 * Two devices share the same gateway and the same TX characteristic, so a
 * single buffer/parser instance correctly interleaves messages from both —
 * routing happens per-message via the "id" field, not per-connection.
 */
class JsonParser {

    private val buffer = StringBuilder()

    companion object {
        private const val TAG = "BLE_JSON"
        private const val MAX_BUFFER_SIZE = 2048
    }

    /**
     * Feed a raw BLE notification chunk. Returns zero or more complete
     * messages found after appending this chunk (usually zero or one,
     * but can be more if multiple JSONs arrived back-to-back).
     */
    fun feed(chunk: String): List<BleMessage> {
        buffer.append(chunk)

        // Safety valve: if the buffer grows without ever finding a newline,
        // something is wrong upstream (e.g. firmware regression). Discard
        // stale data rather than leaking memory over a long session.
        if (buffer.length > MAX_BUFFER_SIZE) {
            Log.w(TAG, "Buffer overflow (${buffer.length} bytes), clearing")
            buffer.clear()
            return emptyList()
        }

        val messages = mutableListOf<BleMessage>()
        var newlineIndex = buffer.indexOf("\n")

        while (newlineIndex != -1) {
            val fullMessage = buffer.substring(0, newlineIndex).trim()
            buffer.delete(0, newlineIndex + 1)

            if (fullMessage.isNotEmpty()) {
                Log.d("BLE_RX", "Complete packet: $fullMessage")
                messages.add(parse(fullMessage))
            }

            newlineIndex = buffer.indexOf("\n")
        }

        return messages
    }

    fun clear() = buffer.clear()

    private fun parse(message: String): BleMessage {
        return try {
            val json = JSONObject(message)

            when {
                // Telemetry packets always carry "id" — route by device id
                // rather than by guessing from which fields are present.
                // This is more robust than "has(turbidity)" style checks
                // because it fails loudly (Malformed) if a device sends an
                // id it shouldn't, instead of silently misreading fields.
                json.has("id") -> parseTelemetry(json, message)

                json.has("debug") -> BleMessage.Debug(json.getString("debug"))

                else -> {
                    Log.w(TAG, "Unknown JSON keys in: $message")
                    BleMessage.Unknown(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discarding bad packet: '$message' — ${e.message}")
            BleMessage.Malformed(message, e.message)
        }
    }

    private fun parseTelemetry(json: JSONObject, raw: String): BleMessage {
        val deviceId = json.getInt("id")

        val reading: TelemetryReading = when (deviceId) {
            TelemetryReading.DEVICE_ID_CISTERN -> TelemetryReading.Cistern(
                waterLevel = json.getDouble("water_lvl"),
                pumpOn = json.getBoolean("water_pump"),
                batteryPercent = json.getDouble("batt_lvl"),
                rssiDbm = json.getDouble("rssi_lvl")
            )

            TelemetryReading.DEVICE_ID_TANK -> TelemetryReading.Tank(
                waterLevel = json.getDouble("water_lvl"),
                turbidity = json.getDouble("turbidity"),
                batteryPercent = json.getDouble("batt_lvl"),
                rssiDbm = json.getDouble("rssi_lvl")
            )

            else -> {
                Log.w(TAG, "Unknown device id=$deviceId in: $raw")
                return BleMessage.Unknown(raw)
            }
        }

        return BleMessage.Telemetry(reading)
    }
}