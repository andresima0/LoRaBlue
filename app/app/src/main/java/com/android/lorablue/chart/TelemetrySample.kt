package com.android.lorablue.chart

/**
 * One stored sample for a single device (Cistern or Tank) at a point in
 * time. deviceId matches TelemetryReading.DEVICE_ID_CISTERN/_TANK so chart
 * queries can filter by the same id used everywhere else in the app.
 *
 * Cistern-only and Tank-only fields are both nullable here because a
 * single list holds samples for both device types — a Cistern sample will
 * have pumpOn set and turbidity null, a Tank sample the reverse.
 */
data class TelemetrySample(
    val deviceId: Int,
    val timestamp: Long,
    val waterLevel: Double,
    val batteryPercent: Double,
    val rssiDbm: Double,
    val pumpOn: Boolean? = null,   // Cistern-only
    val turbidity: Double? = null  // Tank-only
)