package com.android.lorablue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.ble.BleManager
import com.android.lorablue.data.BleMessage
import com.android.lorablue.data.TelemetryReading
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
 * ViewModel forwards it to MqttPublisher, which picks the Cistern or Tank
 * topic based on the reading's type. If the relevant topic isn't
 * configured yet, MqttPublisher silently skips that publish — BLE/LoRa
 * telemetry keeps working regardless of whether Konker forwarding is set
 * up for one, both, or neither device.
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val mqttPublisher = MqttPublisher()
    private val mqttConfigStore = MqttConfigStore(application)

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

    private fun handleMessage(message: BleMessage) {
        when (message) {
            is BleMessage.Telemetry -> {
                when (val reading = message.reading) {
                    is TelemetryReading.Cistern -> _cisternData.postValue(reading)
                    is TelemetryReading.Tank -> _tankData.postValue(reading)
                }
                mqttPublisher.publish(mqttConfigStore.load(), message.reading)
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

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}