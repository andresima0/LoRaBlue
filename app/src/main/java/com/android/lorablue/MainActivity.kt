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
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.android.lorablue.ble.BleConnectionState
import com.android.lorablue.chart.ChartActivity
import com.android.lorablue.data.TankDepthConfigDialog
import com.android.lorablue.data.TankDepthConfigStore
import com.android.lorablue.data.TelemetryReading
import com.android.lorablue.data.WaterLevelCalculator
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
 *
 * Tapping a card opens ChartActivity for that device. The MQTT settings
 * dialog calls viewModel.onMqttConfigUpdated() on save so that subsequent
 * publishes immediately use the new config without waiting for a cold
 * re-read from SharedPreferences.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val viewModel: BleViewModel by viewModels()

    private lateinit var tvStatus: TextView

    // Cistern card
    private lateinit var cardCistern: View
    private lateinit var tvCisternWaterLevel: TextView
    private lateinit var tvCisternWaterPercent: TextView
    private lateinit var btnCisternDepthConfig: ImageButton
    private lateinit var tvCisternPump: TextView
    private lateinit var tvCisternBattery: TextView
    private lateinit var tvCisternRssi: TextView

    // Tank card
    private lateinit var cardTank: View
    private lateinit var tvTankWaterLevel: TextView
    private lateinit var tvTankWaterPercent: TextView
    private lateinit var btnTankDepthConfig: ImageButton
    private lateinit var tvTankTurbidity: TextView
    private lateinit var tvTankBattery: TextView
    private lateinit var tvTankRssi: TextView

    private lateinit var btnConnect: Button
    private lateinit var btnTxTest: Button
    private lateinit var btnSettings: Button

    // Persists the user-entered total depth (sensor-to-bottom distance)
    // per device, used to convert the raw TOF sensor distance into a
    // water column height + fill percentage.
    private val tankDepthConfigStore by lazy { TankDepthConfigStore(this) }

    // Cache of the most recent raw sensor distance (water_lvl) per device,
    // so that saving a new total depth via the gear icon immediately
    // re-renders the card without waiting for the next BLE telemetry
    // packet to arrive.
    private var lastCisternSensorDistance: Double? = null
    private var lastTankSensorDistance: Double? = null

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

        cardCistern = findViewById(R.id.cardCistern)
        tvCisternWaterLevel = findViewById(R.id.tvCisternWaterLevel)
        tvCisternWaterPercent = findViewById(R.id.tvCisternWaterPercent)
        btnCisternDepthConfig = findViewById(R.id.btnCisternDepthConfig)
        tvCisternPump = findViewById(R.id.tvCisternPump)
        tvCisternBattery = findViewById(R.id.tvCisternBattery)
        tvCisternRssi = findViewById(R.id.tvCisternRssi)

        cardTank = findViewById(R.id.cardTank)
        tvTankWaterLevel = findViewById(R.id.tvTankWaterLevel)
        tvTankWaterPercent = findViewById(R.id.tvTankWaterPercent)
        btnTankDepthConfig = findViewById(R.id.btnTankDepthConfig)
        tvTankTurbidity = findViewById(R.id.tvTankTurbidity)
        tvTankBattery = findViewById(R.id.tvTankBattery)
        tvTankRssi = findViewById(R.id.tvTankRssi)

        btnConnect = findViewById(R.id.btnConnect)
        btnTxTest = findViewById(R.id.btnTxTest)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun wireClickListeners() {
        // --- Device cards → ChartActivity -----------------------------------
        cardCistern.setOnClickListener {
            openChart(TelemetryReading.DEVICE_ID_CISTERN, "Cistern")
        }
        cardTank.setOnClickListener {
            openChart(TelemetryReading.DEVICE_ID_TANK, "Tank")
        }

        // --- Water Level depth configuration (gear icons) --------------------
        // These are nested clickable Views inside the (also clickable) card,
        // so Android dispatches the tap to them rather than bubbling it up
        // to the card's own click listener — tapping the gear never opens
        // ChartActivity.
        btnCisternDepthConfig.setOnClickListener {
            TankDepthConfigDialog.show(
                context = this,
                deviceId = TelemetryReading.DEVICE_ID_CISTERN,
                deviceLabel = "Cistern"
            ) { newDepth ->
                renderCisternWaterLevel(lastCisternSensorDistance, newDepth)
            }
        }
        btnTankDepthConfig.setOnClickListener {
            TankDepthConfigDialog.show(
                context = this,
                deviceId = TelemetryReading.DEVICE_ID_TANK,
                deviceLabel = "Tank"
            ) { newDepth ->
                renderTankWaterLevel(lastTankSensorDistance, newDepth)
            }
        }

        // --- BLE actions ----------------------------------------------------
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

        // --- MQTT settings --------------------------------------------------
        btnSettings.setOnClickListener {
            val dialog = MqttConfigDialog()
            // Wire the saved callback so the ViewModel's in-memory config is
            // refreshed immediately — publishes right after saving will use
            // the new credentials/topics without requiring an app restart.
            dialog.onConfigSaved = { newConfig ->
                viewModel.onMqttConfigUpdated(newConfig)
            }
            dialog.show(supportFragmentManager, MqttConfigDialog.TAG)
        }
    }

    private fun openChart(deviceId: Int, deviceName: String) {
        val intent = Intent(this, ChartActivity::class.java).apply {
            putExtra(ChartActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(ChartActivity.EXTRA_DEVICE_NAME, deviceName)
        }
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state -> renderConnectionState(state) }

        viewModel.cisternData.observe(this) { data ->
            lastCisternSensorDistance = data.waterLevel
            val depth = tankDepthConfigStore.getTotalDepth(TelemetryReading.DEVICE_ID_CISTERN)
            renderCisternWaterLevel(data.waterLevel, depth)

            tvCisternPump.text = if (data.pumpOn) "Water Pump: ON" else "Water Pump: OFF"
            tvCisternBattery.text = String.format("Battery: %.0f %%", data.batteryPercent)
            tvCisternRssi.text = String.format("RSSI: %.1f dBm", data.rssiDbm)
        }

        viewModel.tankData.observe(this) { data ->
            lastTankSensorDistance = data.waterLevel
            val depth = tankDepthConfigStore.getTotalDepth(TelemetryReading.DEVICE_ID_TANK)
            renderTankWaterLevel(data.waterLevel, depth)

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

    /**
     * Renders the Cistern's "Water Level" line and the percent badge next
     * to it. [sensorDistance] is the raw TOF distance from the top sensor
     * down to the water surface (as received from the firmware);
     * [totalDepth] is the user-configured sensor-to-bottom distance for
     * this device (null until configured via the gear icon).
     *
     * Water column height = totalDepth - sensorDistance, so it's computed
     * here rather than displaying the raw sensor distance directly.
     */
    private fun renderCisternWaterLevel(sensorDistance: Double?, totalDepth: Double?) {
        if (sensorDistance == null) return
        val reading = WaterLevelCalculator.compute(sensorDistance, totalDepth)

        tvCisternWaterLevel.text = if (reading.columnMeters != null) {
            String.format("Water Level: %.2fm", reading.columnMeters)
        } else {
            "Water Level: -- m (set depth)"
        }
        tvCisternWaterPercent.text = if (reading.percent != null) {
            String.format("(%.0f%%)", reading.percent)
        } else {
            "(-- %)"
        }
    }

    /** Tank equivalent of [renderCisternWaterLevel]. */
    private fun renderTankWaterLevel(sensorDistance: Double?, totalDepth: Double?) {
        if (sensorDistance == null) return
        val reading = WaterLevelCalculator.compute(sensorDistance, totalDepth)

        tvTankWaterLevel.text = if (reading.columnMeters != null) {
            String.format("Water Level: %.2fm", reading.columnMeters)
        } else {
            "Water Level: -- m (set depth)"
        }
        tvTankWaterPercent.text = if (reading.percent != null) {
            String.format("(%.0f%%)", reading.percent)
        } else {
            "(-- %)"
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