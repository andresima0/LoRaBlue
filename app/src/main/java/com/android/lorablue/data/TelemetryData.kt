package com.android.lorablue.data

/**
 * Snapshot of one telemetry reading forwarded by the LoRaBlue gateway.
 * Mirrors the JSON shape: {"water":..,"turbidity":..,"pump":0|1,"batt":..,"rssi":..}
 */
data class TelemetryData(
    val waterLevel: Double,
    val turbidity: Double,
    val pumpOn: Boolean,
    val batteryPercent: Double,
    val rssiDbm: Double
)

/**
 * Sealed result of parsing one complete (\n-delimited) BLE message.
 * Keeps JsonParser decoupled from the UI — it never touches a TextView.
 */
sealed class BleMessage {
    data class Telemetry(val data: TelemetryData) : BleMessage()
    data class Debug(val text: String) : BleMessage()
    data class Unknown(val raw: String) : BleMessage()
    data class Malformed(val raw: String, val reason: String?) : BleMessage()
}