package com.android.lorablue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.ble.BleManager
import com.android.lorablue.data.BleMessage
import com.android.lorablue.data.TelemetryData
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
 * Konker publishing: every time a Telemetry message arrives from the
 * gateway, this ViewModel automatically forwards it to MqttPublisher using
 * whatever MqttConfig is currently saved in MqttConfigStore. If the config
 * is incomplete (no server/topic saved yet), MqttPublisher silently skips
 * the publish — there's no error shown to the user in that case, since an
 * unconfigured Konker connection is a valid, expected state (BLE/LoRa
 * telemetry keeps working regardless of whether Konker forwarding is set
 * up).
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val mqttPublisher = MqttPublisher()
    private val mqttConfigStore = MqttConfigStore(application)

    private val _connectionState =
        MutableLiveData<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: LiveData<BleConnectionState> = _connectionState

    private val _telemetry = MutableLiveData<TelemetryData>()
    val telemetry: LiveData<TelemetryData> = _telemetry

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
                _telemetry.postValue(message.data)
                publishToKonker(message.data)
            }
            is BleMessage.Debug -> _debugLog.postValue(message.text)
            is BleMessage.Unknown -> { /* logged inside JsonParser already */ }
            is BleMessage.Malformed -> { /* logged inside JsonParser already */ }
        }
    }

    private fun publishToKonker(data: TelemetryData) {
        val config = mqttConfigStore.load()
        mqttPublisher.publish(config, data)
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