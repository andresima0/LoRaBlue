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
 * Konker publishing: every time a Telemetry message arrives, this
 * ViewModel forwards it to MqttPublisher using the in-memory [mqttConfig]
 * cache. Calling [onMqttConfigUpdated] from MainActivity's dialog callback
 * refreshes that cache immediately — publishes right after saving the
 * dialog will use the new credentials/topics without re-reading
 * SharedPreferences on the next background thread hop.
 *
 * Water level conversion: every Telemetry message also carries the raw
 * TOF sensor distance (`reading.waterLevel`). Before publishing, that
 * distance is converted into a water column height + fill percentage via
 * [WaterLevelCalculator], using the per-device total depth the user set
 * through the gear icon (see [TankDepthConfigStore]). The MQTT payload
 * therefore reports the already-converted values ("water_dpt"/"water_pct"),
 * not the raw sensor distance.
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val mqttPublisher = MqttPublisher()
    private val mqttConfigStore = MqttConfigStore(application)
    private val telemetryStore = TelemetryStore(application)
    private val tankDepthConfigStore = TankDepthConfigStore(application)

    // In-memory cache of the MQTT config. Loaded once from SharedPreferences
    // in init{} and refreshed via onMqttConfigUpdated() when the user saves
    // the settings dialog — avoids a disk read on every telemetry message.
    @Volatile
    private var mqttConfig: MqttConfig = mqttConfigStore.load()

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
    // "Konker publish succeeded/failed" if it wants to.
    private val _mqttStatus = MutableLiveData<String>()
    val mqttStatus: LiveData<String> = _mqttStatus

    init {
        bleManager.onStateChanged = { state -> _connectionState.postValue(state) }
        bleManager.onMessageReceived = { message -> handleMessage(message) }
        mqttPublisher.onPublishResult = { _, message -> _mqttStatus.postValue(message) }
    }

    /**
     * Called by MainActivity immediately after MqttConfigDialog saves new
     * settings. Updates the in-memory cache so that the very next publish
     * (which may arrive within seconds on a live BLE connection) already
     * uses the new config — no app restart or SharedPreferences re-read
     * required.
     */
    fun onMqttConfigUpdated(newConfig: MqttConfig) {
        mqttConfig = newConfig
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
                // distance. Use the in-memory mqttConfig cache too, to
                // avoid a SharedPreferences disk read on every packet.
                val waterColumn = toWaterColumn(message.reading)
                mqttPublisher.publish(mqttConfig, message.reading, waterColumn)
                // Persists every reading via SharedPreferences (see
                // TelemetryStore) so ChartActivity has a 10-minute history
                // to plot. This runs independently of MQTT/BLE forwarding
                // — chart history keeps growing even if Konker isn't
                // configured or the app is momentarily disconnected from
                // the gateway between readings.
                telemetryStore.record(toSample(message.reading))
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
     * (MqttPublisher) send those as JSON null in that case.
     */
    private fun toWaterColumn(reading: TelemetryReading): WaterColumnReading {
        val (deviceId, sensorDistance) = when (reading) {
            is TelemetryReading.Cistern -> TelemetryReading.DEVICE_ID_CISTERN to reading.waterLevel
            is TelemetryReading.Tank -> TelemetryReading.DEVICE_ID_TANK to reading.waterLevel
        }
        val totalDepth = tankDepthConfigStore.getTotalDepth(deviceId)
        return WaterLevelCalculator.compute(sensorDistance, totalDepth)
    }

    private fun toSample(reading: TelemetryReading): TelemetrySample {
        val now = System.currentTimeMillis()
        return when (reading) {
            is TelemetryReading.Cistern -> TelemetrySample(
                deviceId = TelemetryReading.DEVICE_ID_CISTERN,
                timestamp = now,
                waterLevel = reading.waterLevel,
                batteryPercent = reading.batteryPercent,
                rssiDbm = reading.rssiDbm,
                pumpOn = reading.pumpOn
            )
            is TelemetryReading.Tank -> TelemetrySample(
                deviceId = TelemetryReading.DEVICE_ID_TANK,
                timestamp = now,
                waterLevel = reading.waterLevel,
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