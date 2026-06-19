package com.android.lorablue

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // =========================================================================
    // UI COMPONENTS
    // =========================================================================
    private lateinit var tvStatus: TextView
    private lateinit var tvWaterLevel: TextView
    private lateinit var tvTurbidity: TextView
    private lateinit var tvPump: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvRssi: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTxTest: Button

    // =========================================================================
    // BLUETOOTH STATE
    // =========================================================================
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false

    private var isTxTestActive = false

    // =========================================================================
    // MODERN PERMISSION / ENABLE-BLUETOOTH LAUNCHERS
    // Replaces the deprecated onRequestPermissionsResult / onActivityResult
    // pair. ActivityResultContracts survives configuration changes (screen
    // rotation) correctly because the launcher is registered as a field
    // during Activity creation, before onStart(), which is required by the
    // Activity Result API contract.
    // =========================================================================
    private val requestBtPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                Log.d("BLE_PERM", "Permissions granted. Checking BT radio state.")
                ensureBluetoothEnabledThenScan()
            } else {
                Log.e("BLE_PERM", "Permissions denied by user.")
                Toast.makeText(
                    this,
                    "Permissions are required to operate LoRaBlue telemetry.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("BLE_SYS", "User successfully enabled Bluetooth. Starting scan.")
                startBleScan()
            } else {
                Toast.makeText(
                    this, "Bluetooth must be enabled to connect to the Gateway.", Toast.LENGTH_LONG
                ).show()
            }
        }

    // Nordic UART Service (NUS) UUIDs
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR_UUID =
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // App → Board
    private val TX_CHAR_UUID =
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Board → App
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // =========================================================================
    // BLE OPERATION QUEUE
    // =========================================================================
    private val bleQueue = ConcurrentLinkedQueue<Runnable>()
    private var isBleBusy = false

    // =========================================================================
    // JSON REASSEMBLY BUFFER
    // =========================================================================
    private val bleDataBuffer = StringBuilder()

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvWaterLevel = findViewById(R.id.tvWaterLevel)
        tvTurbidity = findViewById(R.id.tvTurbidity)
        tvPump = findViewById(R.id.tvPump)
        tvBattery = findViewById(R.id.tvBattery)
        tvRssi = findViewById(R.id.tvRssi)
        btnConnect = findViewById(R.id.btnConnect)
        btnTxTest = findViewById(R.id.btnTxTest)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener {
            if (checkBlePermissions()) {
                ensureBluetoothEnabledThenScan()
            }
        }

        btnTxTest.setOnClickListener {
            // Toggle the state
            isTxTestActive = !isTxTestActive

            if (isTxTestActive) {
                // State is ON: Change color to Green and send PING
                btnTxTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#4CAF50")
                )
                sendBleCommand("PING")
            } else {
                // State is OFF: Change color back to Gray and send CLEAR
                btnTxTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#9E9E9E")
                )
                sendBleCommand("CLEAR")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // =========================================================================
    // BLE OPERATION QUEUE
    // =========================================================================
    private fun enqueueBleOperation(operation: Runnable) {
        bleQueue.add(operation)
        if (!isBleBusy) doNextBleOperation()
    }

    private fun doNextBleOperation() {
        if (bleQueue.isEmpty()) {
            isBleBusy = false; return
        }
        isBleBusy = true
        runOnUiThread { bleQueue.poll()?.run() }
    }

    // =========================================================================
    // BLE SCAN
    // =========================================================================
    private fun startBleScan() {
        if (isScanning) {
            Toast.makeText(this, "Already scanning for the Gateway...", Toast.LENGTH_SHORT).show()
            return
        }

        isScanning = true
        tvStatus.text = "Status: Scanning..."
        tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))

        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            isScanning = false // Release the lock
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)

            runOnUiThread {
                tvStatus.text = "Status: Found device, connecting..."
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            }
            bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false // Release the lock

            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "scan already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE unsupported"
                else -> "error $errorCode"
            }
            Log.e("BLE_SCAN", "Scan failed: $reason")
            runOnUiThread {
                tvStatus.text = "Status: Scan failed ($reason)"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
            }
        }
    }

    // =========================================================================
    // GATT CALLBACK
    // =========================================================================
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Status: Negotiating MTU..."
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                }
                bleQueue.clear()
                bleDataBuffer.clear()
                isBleBusy = false
                gatt.requestMtu(247)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Status: Disconnected"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                }
                isBleBusy = false
                bleDataBuffer.clear()

                // [BRUTAL CACHE CLEARING]
                // Forces Android AOSP to clear the device's internal cache
                try {
                    val localMethod = gatt.javaClass.getMethod("refresh")
                    if (localMethod != null) {
                        val result = localMethod.invoke(gatt) as Boolean
                        Log.d("BLE_CACHE", "GATT cache cleared successfully: $result")
                    }
                } catch (localException: Exception) {
                    Log.e("BLE_CACHE", "Failed to clear GATT cache: ${localException.message}")
                }

                gatt.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE_MTU", "MTU negotiated: $mtu bytes, status: $status")
            runOnUiThread {
                tvStatus.text = "Status: Connected! (MTU $mtu)"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(SERVICE_UUID) ?: return
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
            val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(txChar, true)

            enqueueBleOperation(Runnable {
                val descriptor = txChar.getDescriptor(CCCD_UUID)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val success = gatt.writeDescriptor(descriptor)
                if (!success) doNextBleOperation()
            })
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            doNextBleOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            doNextBleOperation()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleIncomingBleData(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleIncomingBleData(characteristic.uuid, value)
        }
    }

    // =========================================================================
    // INCOMING DATA HANDLER
    // =========================================================================
    private fun handleIncomingBleData(uuid: UUID, value: ByteArray) {
        if (uuid != TX_CHAR_UUID) return

        val chunk = String(value, Charsets.UTF_8)
        bleDataBuffer.append(chunk)

        if (bleDataBuffer.length > 2048) {
            Log.w("BLE_RX", "Buffer overflow (${bleDataBuffer.length} bytes), clearing")
            bleDataBuffer.clear()
            return
        }

        var newlineIndex = bleDataBuffer.indexOf("\n")
        while (newlineIndex != -1) {
            val fullMessage = bleDataBuffer.substring(0, newlineIndex).trim()
            bleDataBuffer.delete(0, newlineIndex + 1)

            if (fullMessage.isNotEmpty()) {
                Log.d("BLE_RX", "Complete packet: $fullMessage")
                parseAndDispatch(fullMessage)
            }

            newlineIndex = bleDataBuffer.indexOf("\n")
        }
    }

    private fun parseAndDispatch(message: String) {
        try {
            val json = JSONObject(message)

            when {
                json.has("water") -> {
                    val waterLevel = json.getDouble("water")
                    val turbidity = json.getDouble("turbidity")
                    val pump = json.getInt("pump") == 1
                    val battery = json.getDouble("batt")
                    val rssi = json.getDouble("rssi")

                    runOnUiThread {
                        tvWaterLevel.text = String.format("Water Level: %.2f m", waterLevel)
                        tvTurbidity.text = String.format("Turbidity: %.2f NTU", turbidity)
                        tvPump.text = if (pump) "Water Pump: ON" else "Water Pump: OFF"
                        tvBattery.text = String.format("TX Battery: %.1f %%", battery)
                        tvRssi.text = String.format("Link RSSI: %.1f dBm", rssi)
                    }
                }

                json.has("debug") -> {
                    Log.d("BLE_DEBUG", "Board response: ${json.getString("debug")}")
                }

                else -> {
                    Log.w("BLE_JSON", "Unknown JSON keys in: $message")
                }
            }

        } catch (e: Exception) {
            Log.e("BLE_JSON", "Discarding bad packet: '$message' — ${e.message}")
        }
    }

    // =========================================================================
    // SEND COMMAND (App → Board)
    // =========================================================================
    private fun sendBleCommand(command: String) {
        if (bluetoothGatt == null || rxCharacteristic == null) {
            // Updated to the new LoRaBlue brand
            Toast.makeText(this, "Not connected to LoRaBlue Gateway", Toast.LENGTH_SHORT).show()
            return
        }

        enqueueBleOperation(Runnable {
            val payload = "$command\n".toByteArray(Charsets.UTF_8)
            rxCharacteristic?.let { char ->
                char.value = payload
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val success = bluetoothGatt?.writeCharacteristic(char) ?: false
                if (!success) {
                    Log.e("BLE_TX", "Write failed for command: $command")
                    doNextBleOperation()
                } else {
                    Log.d("BLE_TX", "Command sent: $command")
                }
            }
        })
    }

    // =========================================================================
    // RUNTIME PERMISSIONS
    // =========================================================================
    private fun checkBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missing = required.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestBtPermissionsLauncher.launch(missing.toTypedArray())
            return false
        }
        return true
    }

    // =========================================================================
    // Centralises the "is the BT radio actually on?" check used both right
    // after permissions are granted and on the Connect button click.
    // =========================================================================
    private fun ensureBluetoothEnabledThenScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startBleScan()
        }
    }
}