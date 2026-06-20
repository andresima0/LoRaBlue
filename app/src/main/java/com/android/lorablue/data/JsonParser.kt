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
                json.has("water") -> {
                    val telemetry = TelemetryData(
                        waterLevel = json.getDouble("water"),
                        turbidity = json.getDouble("turbidity"),
                        pumpOn = json.getInt("pump") == 1,
                        batteryPercent = json.getDouble("batt"),
                        rssiDbm = json.getDouble("rssi")
                    )
                    BleMessage.Telemetry(telemetry)
                }

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
}