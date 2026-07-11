package com.android.lorablue.mqtt

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.android.lorablue.R

class MqttSettingsActivity : AppCompatActivity() {

    private lateinit var store: MqttConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mqtt_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val ctx = this
        store = MqttConfigStore(ctx)
        val current = store.load()

        val dp = ctx.resources.displayMetrics.density
        val sectionSpacing = (14 * dp).toInt()
        val itemSpacing = (6 * dp).toInt()

        val rootView = findViewById<View>(R.id.root)
        val scrollView = findViewById<ScrollView>(R.id.scrollContent)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val spinner = findViewById<Spinner>(R.id.spinnerPlatform)
        val formContainer = findViewById<LinearLayout>(R.id.formContainer)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val bottomBarBasePadding = bottomBar.paddingBottom
        val scrollViewBasePadding = scrollView.paddingBottom

        var focusedField: EditText? = null

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            bottomBar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight,
                bottomBarBasePadding + navBarBottom
            )

            val bottomClearance = if (imeVisible) imeBottom else navBarBottom
            scrollView.setPadding(
                scrollView.paddingLeft, scrollView.paddingTop, scrollView.paddingRight,
                scrollViewBasePadding + bottomClearance
            )

            if (imeVisible) {
                scrollView.post {
                    focusedField?.let { scrollView.smoothScrollTo(0, it.top - (24 * dp).toInt()) }
                }
            }

            insets
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            setPadding(0, sectionSpacing, 0, itemSpacing)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        fun EditText.scrollToOnFocus() {
            setOnFocusChangeListener { _, hasFocus ->
                focusedField = if (hasFocus) this else null
            }
        }

        fun field(hint: String, value: String = "", numeric: Boolean = false) =
            EditText(ctx).apply {
                this.hint = hint
                setText(value)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                scrollToOnFocus()
            }

        fun passwordField(hint: String, value: String = "") = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            scrollToOnFocus()
        }

        // ── ThingsBoard fields ───────────────────────────────────────────────

        val tbCurrent = store.loadPlatform(IotPlatform.THINGSBOARD) as MqttConfig.ThingsBoard

        val cbTbEnabled = CheckBox(ctx).apply {
            text = "Enable publishing to ThingsBoard"
            isChecked = tbCurrent.enabled
        }
        val etTbServer = field("Server", tbCurrent.server)
        val etTbPort = field("Port (default 1883)", tbCurrent.port, numeric = true)
        val etTbCisternToken = field("Cistern access token", tbCurrent.cisternToken)
        val etTbTankToken = field("Tank access token", tbCurrent.tankToken)

        val sectionThingsBoard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(cbTbEnabled)
            addView(label("Broker"))
            addView(etTbServer)
            addView(etTbPort)
            addView(label("Device access tokens"))
            addView(etTbCisternToken)
            addView(etTbTankToken)
        }

        // ── Konker fields ────────────────────────────────────────────────────

        val konkerCurrent = store.loadPlatform(IotPlatform.KONKER) as MqttConfig.Konker

        val cbKonkerEnabled = CheckBox(ctx).apply {
            text = "Enable publishing to Konker"
            isChecked = konkerCurrent.enabled
        }
        val etKonkerServer = field("Server", konkerCurrent.server)
        val etKonkerPort = field("Port (default 1883)", konkerCurrent.port, numeric = true)
        val etKonkerUser = field("Username", konkerCurrent.user)
        val etKonkerPass = passwordField("Password", konkerCurrent.pass)
        val etKonkerCisternTopic = field("Cistern topic", konkerCurrent.cisternTopic)
        val etKonkerTankTopic = field("Tank topic", konkerCurrent.tankTopic)

        val sectionKonker = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(cbKonkerEnabled)
            addView(label("Broker"))
            addView(etKonkerServer)
            addView(etKonkerPort)
            addView(label("Credentials"))
            addView(etKonkerUser)
            addView(etKonkerPass)
            addView(label("Device topics"))
            addView(etKonkerCisternTopic)
            addView(etKonkerTankTopic)
        }

        // ── Platform spinner ─────────────────────────────────────────────────

        val platforms = IotPlatform.values()
        spinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            platforms.map { it.displayName }
        )

        fun showSection(platform: IotPlatform) {
            formContainer.removeAllViews()
            formContainer.addView(
                when (platform) {
                    IotPlatform.THINGSBOARD -> sectionThingsBoard
                    IotPlatform.KONKER -> sectionKonker
                }
            )
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                showSection(platforms[pos])
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        val savedIndex = platforms.indexOfFirst { it == current.platform }.coerceAtLeast(0)
        spinner.setSelection(savedIndex)
        showSection(platforms[savedIndex])

        // ── Cancel ───────────────────────────────────────────────────────────

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        // ── Save ─────────────────────────────────────────────────────────────

        btnSave.setOnClickListener {
            val tbConfig = MqttConfig.ThingsBoard(
                server = etTbServer.text.toString().trim(),
                port = etTbPort.text.toString().trim().ifEmpty { "1883" },
                cisternToken = etTbCisternToken.text.toString().trim(),
                tankToken = etTbTankToken.text.toString().trim(),
                enabled = cbTbEnabled.isChecked
            )

            val konkerConfig = MqttConfig.Konker(
                server = etKonkerServer.text.toString().trim(),
                port = etKonkerPort.text.toString().trim().ifEmpty { "1883" },
                user = etKonkerUser.text.toString().trim(),
                pass = etKonkerPass.text.toString(),
                cisternTopic = etKonkerCisternTopic.text.toString().trim(),
                tankTopic = etKonkerTankTopic.text.toString().trim(),
                enabled = cbKonkerEnabled.isChecked
            )

            if (tbConfig.enabled) {
                if (tbConfig.server.isBlank()) {
                    Toast.makeText(ctx, "ThingsBoard: server address is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!tbConfig.isCisternConfigured && !tbConfig.isTankConfigured) {
                    Toast.makeText(
                        ctx, "ThingsBoard: enter at least one device access token",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
            }

            if (konkerConfig.enabled) {
                if (konkerConfig.server.isBlank()) {
                    Toast.makeText(ctx, "Konker: server address is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!konkerConfig.isCisternConfigured && !konkerConfig.isTankConfigured) {
                    Toast.makeText(
                        ctx,
                        "Konker: enter username and at least one device topic",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
            }

            store.save(tbConfig)
            store.save(konkerConfig)

            val enabledNames = listOf(tbConfig, konkerConfig).filter { it.enabled }.map { it.platform.displayName }
            Toast.makeText(
                ctx,
                if (enabledNames.isEmpty()) "Settings saved — no platform enabled"
                else "Settings saved — publishing to: ${enabledNames.joinToString(" and ")}",
                Toast.LENGTH_SHORT
            ).show()

            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    companion object {
        fun newIntent(activity: Activity): Intent = Intent(activity, MqttSettingsActivity::class.java)
    }
}