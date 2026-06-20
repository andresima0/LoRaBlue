package com.android.lorablue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.ble.BleManager
import com.android.lorablue.data.BleMessage
import com.android.lorablue.data.TelemetryData

/**
 * Holds all BLE-derived state so it survives configuration changes (screen
 * rotation). The Activity only observes LiveData and forwards user actions
 * (connect/ping/clear) — it never touches BluetoothGatt directly.
 *
 * AndroidViewModel (not plain ViewModel) is used because BleManager needs a
 * Context to fetch the BluetoothManager system service.
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)

    private val _connectionState =
        MutableLiveData<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: LiveData<BleConnectionState> = _connectionState

    private val _telemetry = MutableLiveData<TelemetryData>()
    val telemetry: LiveData<TelemetryData> = _telemetry

    private val _debugLog = MutableLiveData<String>()
    val debugLog: LiveData<String> = _debugLog

    init {
        bleManager.onStateChanged = { state -> _connectionState.postValue(state) }
        bleManager.onMessageReceived = { message -> handleMessage(message) }
    }

    private fun handleMessage(message: BleMessage) {
        when (message) {
            is BleMessage.Telemetry -> _telemetry.postValue(message.data)
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