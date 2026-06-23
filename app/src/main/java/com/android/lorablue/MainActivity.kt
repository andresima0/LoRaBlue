package com.android.lorablue

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.mqtt.MqttConfigDialog

/**
 * Pure UI layer: binds views, observes BleViewModel via LiveData, and wires
 * click listeners. Contains no BLE/GATT logic — see BleManager and
 * BleViewModel for that. Contains no MQTT logic either — see MqttPublisher
 * and MqttConfigDialog.
 *
 * Two telemetry cards are shown: Cistern (id=1) and Tank (id=2). Each is
 * updated independently as its corresponding LiveData emits — receiving a
 * Cistern reading does not touch the Tank card's displayed values, and
 * vice versa.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val viewModel: BleViewModel by viewModels()

    private lateinit var tvStatus: TextView

    // Cistern card
    private lateinit var tvCisternWaterLevel: TextView
    private lateinit var tvCisternPump: TextView
    private lateinit var tvCisternBattery: TextView
    private lateinit var tvCisternRssi: TextView

    // Tank card
    private lateinit var tvTankWaterLevel: TextView
    private lateinit var tvTankTurbidity: TextView
    private lateinit var tvTankBattery: TextView
    private lateinit var tvTankRssi: TextView

    private lateinit var btnConnect: Button
    private lateinit var btnTxTest: Button
    private lateinit var btnSettings: Button

    // Presentation-only toggle state for btnTxTest. Not BLE connection
    // state — this just tracks whether the last command sent was PING
    // (ON / green) or CLEAR (OFF / gray).
    private var isTxTestActive = false

    // =========================================================================
    // MODERN PERMISSION / ENABLE-BLUETOOTH LAUNCHERS
    // Registered as fields (before onStart) so they survive configuration
    // changes correctly, per the Activity Result API contract.
    // =========================================================================
    private val requestBtPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                ensureBluetoothEnabledThenScan()
            } else {
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
                viewModel.startScan()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth must be enabled to connect to the Gateway.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireClickListeners()
        observeViewModel()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)

        tvCisternWaterLevel = findViewById(R.id.tvCisternWaterLevel)
        tvCisternPump = findViewById(R.id.tvCisternPump)
        tvCisternBattery = findViewById(R.id.tvCisternBattery)
        tvCisternRssi = findViewById(R.id.tvCisternRssi)

        tvTankWaterLevel = findViewById(R.id.tvTankWaterLevel)
        tvTankTurbidity = findViewById(R.id.tvTankTurbidity)
        tvTankBattery = findViewById(R.id.tvTankBattery)
        tvTankRssi = findViewById(R.id.tvTankRssi)

        btnConnect = findViewById(R.id.btnConnect)
        btnTxTest = findViewById(R.id.btnTxTest)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun wireClickListeners() {
        btnConnect.setOnClickListener {
            if (checkBlePermissions()) ensureBluetoothEnabledThenScan()
        }

        btnTxTest.setOnClickListener {
            isTxTestActive = !isTxTestActive

            if (isTxTestActive) {
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                viewModel.sendPing()
            } else {
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                viewModel.sendClear()
            }
        }

        btnSettings.setOnClickListener {
            MqttConfigDialog().show(supportFragmentManager, MqttConfigDialog.TAG)
        }
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state -> renderConnectionState(state) }

        viewModel.cisternData.observe(this) { data ->
            tvCisternWaterLevel.text = String.format("Water Level: %.2f m", data.waterLevel)
            tvCisternPump.text = if (data.pumpOn) "Water Pump: ON" else "Water Pump: OFF"
            tvCisternBattery.text = String.format("Battery: %.0f %%", data.batteryPercent)
            tvCisternRssi.text = String.format("RSSI: %.1f dBm", data.rssiDbm)
        }

        viewModel.tankData.observe(this) { data ->
            tvTankWaterLevel.text = String.format("Water Level: %.2f m", data.waterLevel)
            tvTankTurbidity.text = String.format("Turbidity: %.0f NTU", data.turbidity)
            tvTankBattery.text = String.format("Battery: %.0f %%", data.batteryPercent)
            tvTankRssi.text = String.format("RSSI: %.1f dBm", data.rssiDbm)
        }

        // Debug messages (PING/CLEAR acks from the firmware) are logged
        // inside BleManager; surfaced here as a lightweight Toast.
        viewModel.debugLog.observe(this) { text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        // Konker MQTT publish outcomes (success/failure), surfaced the
        // same lightweight way as BLE debug messages.
        viewModel.mqttStatus.observe(this) { text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderConnectionState(state: BleConnectionState) {
        val (text, color) = when (state) {
            is BleConnectionState.Disconnected ->
                "Status: Disconnected" to "#F44336"
            is BleConnectionState.Scanning ->
                "Status: Scanning..." to "#FF9800"
            is BleConnectionState.Connecting ->
                "Status: Found device, connecting..." to "#FF9800"
            is BleConnectionState.NegotiatingMtu ->
                "Status: Negotiating MTU..." to "#FF9800"
            is BleConnectionState.Connected ->
                "Status: Connected! (MTU ${state.mtu})" to "#4CAF50"
            is BleConnectionState.ScanFailed ->
                "Status: Scan failed (${state.reason})" to "#F44336"
        }
        tvStatus.text = text
        tvStatus.setTextColor(Color.parseColor(color))

        if (state is BleConnectionState.Disconnected) {
            isTxTestActive = false
            btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
        }
    }

    private fun ensureBluetoothEnabledThenScan() {
        if (!viewModel.isBluetoothEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            viewModel.startScan()
        }
    }

    private fun checkBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
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
}