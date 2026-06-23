package com.android.lorablue.data

/**
 * Two physical devices forward telemetry through the same LoRa gateway,
 * distinguished by the "id" field in their JSON payload:
 *   id=1 → Cistern (lower reservoir): water_lvl, water_pump, batt_lvl, rssi_lvl
 *   id=2 → Tank    (upper reservoir): water_lvl, turbidity, batt_lvl, rssi_lvl
 *
 * Each device's payload shares water_lvl/batt_lvl/rssi_lvl but otherwise
 * has a distinct field (water_pump vs turbidity) — a sealed class with one
 * data class per device id keeps that difference explicit instead of using
 * a single class with nullable fields for whichever sensor a given device
 * doesn't have.
 */
sealed class TelemetryReading {

    data class Cistern(
        val waterLevel: Double,
        val pumpOn: Boolean,
        val batteryPercent: Double,
        val rssiDbm: Double
    ) : TelemetryReading()

    data class Tank(
        val waterLevel: Double,
        val turbidity: Double,
        val batteryPercent: Double,
        val rssiDbm: Double
    ) : TelemetryReading()

    companion object {
        const val DEVICE_ID_CISTERN = 1
        const val DEVICE_ID_TANK = 2
    }
}

/**
 * Sealed result of parsing one complete (\n-delimited) BLE message.
 * Keeps JsonParser decoupled from the UI — it never touches a TextView.
 */
sealed class BleMessage {
    data class Telemetry(val reading: TelemetryReading) : BleMessage()
    data class Debug(val text: String) : BleMessage()
    data class Unknown(val raw: String) : BleMessage()
    data class Malformed(val raw: String, val reason: String?) : BleMessage()
}