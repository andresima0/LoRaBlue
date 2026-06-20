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

/**
 * Pure UI layer: binds views, observes BleViewModel via LiveData, and wires
 * click listeners. Contains no BLE/GATT logic — see BleManager and
 * BleViewModel for that.
 *
 * btnTxTest toggle state (isTxTestActive, color) is presentation-only and
 * lives here. It only calls viewModel.sendPing()/sendClear() — the actual
 * GATT write goes through BleManager unchanged.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val viewModel: BleViewModel by viewModels()

    private lateinit var tvStatus: TextView
    private lateinit var tvWaterLevel: TextView
    private lateinit var tvTurbidity: TextView
    private lateinit var tvPump: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvRssi: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTxTest: Button

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
        tvWaterLevel = findViewById(R.id.tvWaterLevel)
        tvTurbidity = findViewById(R.id.tvTurbidity)
        tvPump = findViewById(R.id.tvPump)
        tvBattery = findViewById(R.id.tvBattery)
        tvRssi = findViewById(R.id.tvRssi)
        btnConnect = findViewById(R.id.btnConnect)
        btnTxTest = findViewById(R.id.btnTxTest)
    }

    private fun wireClickListeners() {
        btnConnect.setOnClickListener {
            if (checkBlePermissions()) ensureBluetoothEnabledThenScan()
        }

        btnTxTest.setOnClickListener {
            isTxTestActive = !isTxTestActive

            if (isTxTestActive) {
                // State is ON: green + PING
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                viewModel.sendPing()
            } else {
                // State is OFF: gray + CLEAR
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                viewModel.sendClear()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state -> renderConnectionState(state) }

        viewModel.telemetry.observe(this) { data ->
            tvWaterLevel.text = String.format("Water Level: %.2f m", data.waterLevel)
            tvTurbidity.text = String.format("Turbidity: %.2f NTU", data.turbidity)
            tvPump.text = if (data.pumpOn) "Water Pump: ON" else "Water Pump: OFF"
            tvBattery.text = String.format("TX Battery: %.1f %%", data.batteryPercent)
            tvRssi.text = String.format("Link RSSI: %.1f dBm", data.rssiDbm)
        }

        // Debug messages (PING/CLEAR acks from the firmware) are logged
        // inside BleManager; surfaced here as a lightweight Toast.
        viewModel.debugLog.observe(this) { text ->
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

        // If we lose the connection, the toggle's visual state goes stale
        // (it could be stuck green even though CLEAR was never confirmed
        // by the firmware). Reset it on disconnect so it always starts
        // from a known OFF/gray state on the next connection.
        if (state is BleConnectionState.Disconnected) {
            isTxTestActive = false
            btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
        }
    }

    // =========================================================================
    // Centralises the "is the BT radio actually on?" check.
    // =========================================================================
    private fun ensureBluetoothEnabledThenScan() {
        if (!viewModel.isBluetoothEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            viewModel.startScan()
        }
    }

    // =========================================================================
    // RUNTIME PERMISSIONS
    // =========================================================================
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