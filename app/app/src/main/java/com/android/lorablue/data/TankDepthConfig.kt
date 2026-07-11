package com.android.lorablue.data

import android.content.Context
import androidx.core.content.edit

/**
 * The TOF sensor sits at the top of the tank/cistern and reports the
 * distance down to the water surface (`water_lvl` in the BLE/MQTT
 * payload). That raw distance is NOT the water column height — it's the
 * *air gap* above the water. To show a meaningful water column height and
 * a fill percentage, the total depth of the tank/cistern (sensor-to-bottom
 * distance) must be known per device, and is user-configurable via the
 * gear icon next to "Water Level" on each card.
 *
 * waterColumn = totalDepth - sensorDistance
 * percent     = waterColumn / totalDepth * 100
 *
 * Stored per device id (Cistern/Tank have independent physical depths).
 * A value of 0.0 (or absent) means "not configured yet" — callers should
 * treat that as "show raw distance only, can't compute % or column".
 */
class TankDepthConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("tank_depth_config", Context.MODE_PRIVATE)

    private fun key(deviceId: Int) = "total_depth_m_$deviceId"

    /** Returns the configured total depth in meters for [deviceId], or null if not set. */
    fun getTotalDepth(deviceId: Int): Double? {
        val raw = prefs.getFloat(key(deviceId), -1f)
        return if (raw < 0f) null else raw.toDouble()
    }

    fun setTotalDepth(deviceId: Int, totalDepthMeters: Double) {
        prefs.edit { putFloat(key(deviceId), totalDepthMeters.toFloat()) }
    }
}

/**
 * Result of converting a raw TOF sensor distance into a usable water
 * column reading, once the tank/cistern total depth is known.
 *
 * [columnMeters] and [percent] are null when the total depth hasn't been
 * configured yet — there's nothing meaningful to compute.
 */
data class WaterColumnReading(
    val sensorDistanceMeters: Double,
    val totalDepthMeters: Double?,
    val columnMeters: Double?,
    val percent: Double?
)

/**
 * Converts a raw sensor distance (ceiling/top sensor down to the water
 * surface) into the actual water column height and fill percentage, given
 * the tank/cistern's configured total depth (sensor-to-bottom distance).
 *
 * Clamped to [0, totalDepth] so that minor sensor noise (e.g. a reading
 * slightly above an empty tank's total depth, or slightly below 0 when
 * full and the sensor overshoots) doesn't produce a negative column or a
 * percentage outside 0–100%.
 *
 * Results are rounded here (not just at display time) so that every
 * consumer — the UI cards and the MQTT JSON payload alike — gets a clean
 * number instead of raw Double artifacts like 28.000000000000004.
 * columnMeters keeps 2 decimal places (matches the "0.00m" UI format);
 * percent is rounded to a whole number (no decimals needed for a
 * percentage on a small tank/cistern display).
 */
object WaterLevelCalculator {

    fun compute(sensorDistanceMeters: Double, totalDepthMeters: Double?): WaterColumnReading {
        if (totalDepthMeters == null || totalDepthMeters <= 0.0) {
            return WaterColumnReading(
                sensorDistanceMeters = sensorDistanceMeters,
                totalDepthMeters = totalDepthMeters,
                columnMeters = null,
                percent = null
            )
        }

        val rawColumn = totalDepthMeters - sensorDistanceMeters
        val column = rawColumn.coerceIn(0.0, totalDepthMeters)
        val percent = (column / totalDepthMeters) * 100.0

        return WaterColumnReading(
            sensorDistanceMeters = sensorDistanceMeters,
            totalDepthMeters = totalDepthMeters,
            columnMeters = column.roundTo(2),
            percent = percent.roundTo(0)
        )
    }

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}