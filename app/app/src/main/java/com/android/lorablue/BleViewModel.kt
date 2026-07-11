package com.android.lorablue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.ble.BleManager
import com.android.lorablue.chart.TelemetrySample
import com.android.lorablue.chart.TelemetryStore
import com.android.lorablue.data.BleMessage
import com.android.lorablue.data.TankDepthConfigStore
import com.android.lorablue.data.TelemetryReading
import com.android.lorablue.data.WaterColumnReading
import com.android.lorablue.data.WaterLevelCalculator
import com.android.lorablue.mqtt.MqttConfig
import com.android.lorablue.mqtt.MqttConfigStore
import com.android.lorablue.mqtt.MqttPublisher

/**
 * Holds all BLE-derived state so it survives configuration changes (screen
 * rotation). The Activity only observes LiveData and forwards user actions
 * (connect/ping/clear) — it never touches BluetoothGatt directly.
 *
 * AndroidViewModel (not plain ViewModel) is used because BleManager needs a
 * Context to fetch the BluetoothManager system service.
 *
 * Two devices (Cistern id=1, Tank id=2) share the same gateway connection,
 * so they're exposed as two separate LiveData streams rather than one —
 * the UI updates whichever card corresponds to the device that just sent
 * a reading, without touching the other card's last-known values.
 *
 * Konker/ThingsBoard publishing: every time a Telemetry message arrives,
 * this ViewModel forwards it to MqttPublisher for EVERY currently enabled
 * platform config in [mqttConfigs] — not just a single "active" platform.
 * Both ThingsBoard and Konker can be enabled at the same time (see
 * MqttSettingsActivity), so the same reading gets published to both
 * brokers in that case.
 *
 * MqttSettingsActivity (a full-screen Activity — see its class comment for
 * why this isn't a Dialog anymore) persists settings directly to
 * MqttConfigStore rather than passing them back through an Intent. So
 * instead of receiving the new configs as a callback argument, MainActivity
 * simply calls [reloadMqttConfigs] after the settings screen returns with
 * RESULT_OK, and this ViewModel re-reads the enabled configs itself.
 *
 * Water level conversion: every Telemetry message also carries the raw
 * TOF sensor distance (`reading.waterLevel`). Before publishing, that
 * distance is converted into a water column height + fill percentage via
 * [WaterLevelCalculator], using the per-device total depth the user set
 * through the gear icon (see [TankDepthConfigStore]). The MQTT payload
 * therefore reports the already-converted values ("water_dpt"/"water_pct"),
 * not the raw sensor distance. This conversion is shared across all
 * enabled platforms — it only needs to happen once per incoming reading.
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val mqttPublisher = MqttPublisher()
    private val mqttConfigStore = MqttConfigStore(application)
    private val telemetryStore = TelemetryStore(application)
    private val tankDepthConfigStore = TankDepthConfigStore(application)

    // In-memory cache of every ENABLED platform config (ThingsBoard and/or
    // Konker). Loaded once from SharedPreferences in init{} and refreshed
    // via reloadMqttConfigs() when MainActivity reports that
    // MqttSettingsActivity saved new settings — avoids a disk read on every
    // telemetry message. A platform absent from this list (disabled, or not
    // configured) is simply never published to.
    @Volatile
    private var mqttConfigs: List<MqttConfig> = mqttConfigStore.loadAllEnabled()

    private val _connectionState =
        MutableLiveData<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: LiveData<BleConnectionState> = _connectionState

    private val _cisternData = MutableLiveData<TelemetryReading.Cistern>()
    val cisternData: LiveData<TelemetryReading.Cistern> = _cisternData

    private val _tankData = MutableLiveData<TelemetryReading.Tank>()
    val tankData: LiveData<TelemetryReading.Tank> = _tankData

    private val _debugLog = MutableLiveData<String>()
    val debugLog: LiveData<String> = _debugLog

    // Surfaces MQTT publish outcomes (success/failure) separately from BLE
    // debug messages so the UI can distinguish "firmware said X" from
    // "platform publish succeeded/failed" if it wants to. Each enabled
    // platform reports independently, so a ThingsBoard failure doesn't
    // hide a simultaneous Konker success (or vice versa).
    private val _mqttStatus = MutableLiveData<String>()
    val mqttStatus: LiveData<String> = _mqttStatus

    init {
        bleManager.onStateChanged = { state -> _connectionState.postValue(state) }
        bleManager.onMessageReceived = { message -> handleMessage(message) }
        mqttPublisher.onPublishResult = { _, message -> _mqttStatus.postValue(message) }
    }

    /**
     * Called by MainActivity's ActivityResultLauncher callback after
     * MqttSettingsActivity finishes with RESULT_OK. Re-reads whichever
     * platform configs are currently enabled straight from
     * MqttConfigStore (SharedPreferences) — the settings screen persists
     * directly to the store itself, so this is simpler than threading the
     * saved MqttConfig objects back through an Intent. Updates the
     * in-memory cache so that the very next reading (which may arrive
     * within seconds on a live BLE connection) already publishes to
     * exactly this set of platforms — no app restart required.
     */
    fun reloadMqttConfigs() {
        mqttConfigs = mqttConfigStore.loadAllEnabled()
    }

    private fun handleMessage(message: BleMessage) {
        when (message) {
            is BleMessage.Telemetry -> {
                when (val reading = message.reading) {
                    is TelemetryReading.Cistern -> _cisternData.postValue(reading)
                    is TelemetryReading.Tank -> _tankData.postValue(reading)
                }
                // Convert the raw TOF sensor distance into a water column
                // height + fill percentage before publishing — the MQTT
                // payload should report the converted reading, not the raw
                // distance. Computed once per reading and reused for every
                // enabled platform below.
                val waterColumn = toWaterColumn(message.reading)

                // Publish to every currently enabled platform. MqttPublisher
                // already no-ops per-device when that device isn't configured
                // for a given platform (see isCisternConfigured/isTankConfigured),
                // and each publish runs on its own background thread, so
                // publishing to two platforms here never blocks on the other.
                mqttConfigs.forEach { config ->
                    mqttPublisher.publish(config, message.reading, waterColumn)
                }

                // Persists every reading via SharedPreferences (see
                // TelemetryStore) so ChartActivity has a 10-minute history
                // to plot. waterColumn is passed in so the stored waterLevel
                // field holds the converted column height (water_dpt, meters
                // of water above the bottom) — the same value shown on the
                // cards and sent to MQTT. Falls back to the raw sensor
                // distance when depth hasn't been configured yet
                // (columnMeters == null).
                telemetryStore.record(toSample(message.reading, waterColumn))
            }
            is BleMessage.Debug -> _debugLog.postValue(message.text)
            is BleMessage.Unknown -> { /* logged inside JsonParser already */ }
            is BleMessage.Malformed -> { /* logged inside JsonParser already */ }
        }
    }

    val isBluetoothEnabled: Boolean
        get() = bleManager.isBluetoothEnabled

    fun startScan() = bleManager.startScan()

    fun sendPing() = bleManager.sendCommand("PING")

    fun sendClear() = bleManager.sendCommand("CLEAR")

    /**
     * Converts a [TelemetryReading]'s raw TOF sensor distance
     * (`waterLevel`) into a [WaterColumnReading] using this device's
     * user-configured total depth. Returns column/percent = null when the
     * depth hasn't been configured yet for this device id — callers
     * (MqttPublisher, for every enabled platform) send those as JSON null
     * in that case.
     */
    private fun toWaterColumn(reading: TelemetryReading): WaterColumnReading {
        val (deviceId, sensorDistance) = when (reading) {
            is TelemetryReading.Cistern -> TelemetryReading.DEVICE_ID_CISTERN to reading.waterLevel
            is TelemetryReading.Tank -> TelemetryReading.DEVICE_ID_TANK to reading.waterLevel
        }
        val totalDepth = tankDepthConfigStore.getTotalDepth(deviceId)
        return WaterLevelCalculator.compute(sensorDistance, totalDepth)
    }

    private fun toSample(reading: TelemetryReading, waterColumn: WaterColumnReading): TelemetrySample {
        val now = System.currentTimeMillis()
        // Store the converted column height (water_dpt) when available so the
        // chart shows the same value as the card and the MQTT payload. When
        // the depth hasn't been configured yet columnMeters is null and we
        // fall back to the raw sensor distance so the chart still has data.
        return when (reading) {
            is TelemetryReading.Cistern -> TelemetrySample(
                deviceId = TelemetryReading.DEVICE_ID_CISTERN,
                timestamp = now,
                waterLevel = waterColumn.columnMeters ?: reading.waterLevel,
                batteryPercent = reading.batteryPercent,
                rssiDbm = reading.rssiDbm,
                pumpOn = reading.pumpOn
            )
            is TelemetryReading.Tank -> TelemetrySample(
                deviceId = TelemetryReading.DEVICE_ID_TANK,
                timestamp = now,
                waterLevel = waterColumn.columnMeters ?: reading.waterLevel,
                batteryPercent = reading.batteryPercent,
                rssiDbm = reading.rssiDbm,
                turbidity = reading.turbidity
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}