package com.android.lorablue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.android.lorablue.data.BleMessage
import com.android.lorablue.data.JsonParser
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns every direct interaction with the Android BLE APIs: scanning, GATT
 * connection lifecycle, the write-operation queue, MTU negotiation, and
 * notification reassembly. The ViewModel observes this class through plain
 * callbacks and never touches BluetoothGatt/BluetoothAdapter directly.
 *
 * This class has no Android UI dependency (no Activity, no View) so it can
 * be unit-tested or reused from a Service later if needed.
 */
@SuppressLint("MissingPermission")
class BleManager(private val appContext: Context) {

    // -------------------------------------------------------------------
    // Public callbacks — the ViewModel wires these up
    // -------------------------------------------------------------------
    var onStateChanged: (BleConnectionState) -> Unit = {}
    var onMessageReceived: (BleMessage) -> Unit = {}

    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false

    private val bleQueue = ConcurrentLinkedQueue<Runnable>()
    private var isBleBusy = false

    private val jsonParser = JsonParser()

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    val isConnected: Boolean
        get() = bluetoothGatt != null && rxCharacteristic != null

    // =====================================================================
    // OPERATION QUEUE — serialises GATT writes to prevent stack crashes.
    // Android's BLE stack can only have one outstanding GATT operation at
    // a time; firing writeDescriptor/writeCharacteristic concurrently is a
    // common source of silent failures and stack crashes on some OEM ROMs.
    // =====================================================================
    private fun enqueueBleOperation(operation: Runnable) {
        bleQueue.add(operation)
        if (!isBleBusy) doNextBleOperation()
    }

    private fun doNextBleOperation() {
        if (bleQueue.isEmpty()) {
            isBleBusy = false
            return
        }
        isBleBusy = true
        bleQueue.poll()?.run()
    }

    // =====================================================================
    // SCAN
    // =====================================================================
    fun startScan() {
        if (isScanning) return

        isScanning = true
        onStateChanged(BleConnectionState.Scanning)

        // Filtering by Service UUID is more reliable than by device name:
        // many Android phones return device.name == null during scanning,
        // and the name only resolves after bonding or a full scan response.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            isScanning = false
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)

            onStateChanged(BleConnectionState.Connecting)
            bluetoothGatt = result.device.connectGatt(appContext, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "scan already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE unsupported"
                else -> "error $errorCode"
            }
            Log.e("BLE_SCAN", "Scan failed: $reason")
            onStateChanged(BleConnectionState.ScanFailed(reason))
        }
    }

    // =====================================================================
    // GATT CALLBACK
    // =====================================================================
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChanged(BleConnectionState.NegotiatingMtu)
                    bleQueue.clear()
                    jsonParser.clear()
                    isBleBusy = false
                    gatt.requestMtu(BleConstants.PREFERRED_MTU)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStateChanged(BleConnectionState.Disconnected)
                    isBleBusy = false
                    jsonParser.clear()
                    rxCharacteristic = null

                    // Known Android BLE stack workaround: force-clear the
                    // GATT cache via reflection so a re-pair/reconnect picks
                    // up fresh services instead of a stale cached profile.
                    try {
                        val refreshMethod = gatt.javaClass.getMethod("refresh")
                        val result = refreshMethod.invoke(gatt) as Boolean
                        Log.d("BLE_CACHE", "GATT cache cleared: $result")
                    } catch (e: Exception) {
                        Log.e("BLE_CACHE", "Failed to clear GATT cache: ${e.message}")
                    }

                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE_MTU", "MTU negotiated: $mtu bytes, status: $status")
            onStateChanged(BleConnectionState.Connected(mtu))
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
            rxCharacteristic = service.getCharacteristic(BleConstants.RX_CHAR_UUID)
            val txChar = service.getCharacteristic(BleConstants.TX_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(txChar, true)

            enqueueBleOperation(Runnable {
                val descriptor = txChar.getDescriptor(BleConstants.CCCD_UUID)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val success = gatt.writeDescriptor(descriptor)
                if (!success) doNextBleOperation()
            })
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = doNextBleOperation()

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = doNextBleOperation()

        // Legacy callback — required for Android < 13 (API < 33)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleIncomingData(characteristic.uuid, value)
        }

        // Modern callback — Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(characteristic.uuid, value)
        }
    }

    private fun handleIncomingData(uuid: java.util.UUID, value: ByteArray) {
        if (uuid != BleConstants.TX_CHAR_UUID) return

        val chunk = String(value, Charsets.UTF_8)
        jsonParser.feed(chunk).forEach { onMessageReceived(it) }
    }

    // =====================================================================
    // SEND COMMAND (App → Board)
    // =====================================================================
    fun sendCommand(command: String) {
        val gatt = bluetoothGatt
        val char = rxCharacteristic
        if (gatt == null || char == null) {
            Log.w("BLE_TX", "sendCommand('$command') ignored — not connected")
            return
        }

        enqueueBleOperation(Runnable {
            val payload = "$command\n".toByteArray(Charsets.UTF_8)
            char.value = payload
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = gatt.writeCharacteristic(char)
            if (!success) {
                Log.e("BLE_TX", "Write failed for command: $command")
                doNextBleOperation()
            } else {
                Log.d("BLE_TX", "Command sent: $command")
            }
        })
    }

    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
    }
}