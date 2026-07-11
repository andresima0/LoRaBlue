package com.android.lorablue.chart

import com.android.lorablue.data.TelemetryReading

/**
 * One selectable series for the chart screen's metric spinner. label is
 * what the spinner shows; extractValue pulls the right Double out of a
 * TelemetrySample for that metric (booleans like pumpOn are mapped to
 * 0.0/1.0 so they can still be plotted on the same LineChart).
 */
enum class ChartMetric(val label: String) {
    WATER_LEVEL("Water Level (m)") {
        override fun extractValue(sample: TelemetrySample) = sample.waterLevel
    },
    BATTERY("Battery (%)") {
        override fun extractValue(sample: TelemetrySample) = sample.batteryPercent
    },
    RSSI("RSSI (dBm)") {
        override fun extractValue(sample: TelemetrySample) = sample.rssiDbm
    },
    TURBIDITY("Turbidity (NTU)") {
        override fun extractValue(sample: TelemetrySample) = sample.turbidity ?: 0.0
    },
    PUMP_STATUS("Water Pump (ON/OFF)") {
        override fun extractValue(sample: TelemetrySample) =
            if (sample.pumpOn == true) 1.0 else 0.0
    };

    abstract fun extractValue(sample: TelemetrySample): Double

    companion object {
        /**
         * Cistern has no turbidity sensor, Tank has no pump — each device
         * only offers the metrics that actually mean something for it,
         * instead of showing a flat zero line for an n/a field.
         */
        fun forDevice(deviceId: Int): List<ChartMetric> = when (deviceId) {
            TelemetryReading.DEVICE_ID_CISTERN -> listOf(WATER_LEVEL, PUMP_STATUS, BATTERY, RSSI)
            TelemetryReading.DEVICE_ID_TANK -> listOf(WATER_LEVEL, TURBIDITY, BATTERY, RSSI)
            else -> emptyList()
        }
    }
}