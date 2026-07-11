package com.android.lorablue.mqtt

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Settings dialog for the active IoT platform.
 *
 * A [Spinner] at the top lets the user choose between ThingsBoard and Konker.
 * Selecting a platform swaps the visible form section:
 *
 *   ThingsBoard — Server, Port, Cistern token, Tank token.
 *   Konker      — Server, Port, Username, Password, Cistern topic, Tank topic.
 *
 * Both platforms persist their settings independently in [MqttConfigStore], so
 * switching from ThingsBoard to Konker and back does not lose either set of
 * credentials.
 *
 * [onConfigSaved] is called with the new [MqttConfig] immediately after saving
 * so [BleViewModel] can update its in-memory cache without an app restart.
 */
class MqttConfigDialog : DialogFragment() {

    var onConfigSaved: (MqttConfig) -> Unit = {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx   = requireContext()
        val store = MqttConfigStore(ctx)

        // Load whatever is currently saved; use it to pre-populate fields and
        // select the right platform tab in the spinner.
        val current = store.load()

        val dp             = ctx.resources.displayMetrics.density
        val pad            = (16 * dp).toInt()
        val sectionSpacing = (14 * dp).toInt()
        val itemSpacing    = (6  * dp).toInt()

        // ── Helpers ──────────────────────────────────────────────────────────

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            setPadding(0, sectionSpacing, 0, itemSpacing)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        fun field(hint: String, value: String = "", numeric: Boolean = false) =
            EditText(ctx).apply {
                this.hint = hint
                setText(value)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }

        fun passwordField(hint: String, value: String = "") = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // ── Shared fields (server + port) ────────────────────────────────────
        // Each platform now keeps its own server/port fields so switching
        // the spinner never overwrites the other platform's broker address.

        val tbCurrent = current as? MqttConfig.ThingsBoard
            ?: store.loadPlatform(IotPlatform.THINGSBOARD) as? MqttConfig.ThingsBoard
            ?: MqttConfig.ThingsBoard("", "1883", "", "")

        val etTbServer = field("Server", tbCurrent.server)
        val etTbPort   = field("Port (default 1883)", tbCurrent.port, numeric = true)
        val etTbCisternToken = field("Cistern access token", tbCurrent.cisternToken)
        val etTbTankToken    = field("Tank access token",    tbCurrent.tankToken)

        val sectionThingsBoard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Broker"))
            addView(etTbServer)
            addView(etTbPort)
            addView(label("Device access tokens"))
            addView(etTbCisternToken)
            addView(etTbTankToken)
        }

        // ── Konker fields ────────────────────────────────────────────────────

        val konkerCurrent = current as? MqttConfig.Konker
            ?: store.loadPlatform(IotPlatform.KONKER) as? MqttConfig.Konker
            ?: MqttConfig.Konker("", "1883", "", "", "", "")

        val etKonkerServer       = field("Server",      konkerCurrent.server)
        val etKonkerPort         = field("Port (default 1883)", konkerCurrent.port, numeric = true)
        val etKonkerUser         = field("Username",      konkerCurrent.user)
        val etKonkerPass         = passwordField("Password", konkerCurrent.pass)
        val etKonkerCisternTopic = field("Cistern topic", konkerCurrent.cisternTopic)
        val etKonkerTankTopic    = field("Tank topic",    konkerCurrent.tankTopic)

        val sectionKonker = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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

        val platforms  = IotPlatform.values()
        val spinner    = Spinner(ctx)
        spinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            platforms.map { it.displayName }
        )

        // ── Root layout ──────────────────────────────────────────────────────

        val formContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label("Platform"))
            addView(spinner)
            addView(formContainer)   // swapped out by spinner selection
        }

        // Swap visible section when the spinner changes
        fun showSection(platform: IotPlatform) {
            formContainer.removeAllViews()
            formContainer.addView(
                when (platform) {
                    IotPlatform.THINGSBOARD -> sectionThingsBoard
                    IotPlatform.KONKER      -> sectionKonker
                }
            )

        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                showSection(platforms[pos])
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        // Select the platform that matches what is currently saved
        val savedIndex = platforms.indexOfFirst { it == current.platform }
        spinner.setSelection(savedIndex.coerceAtLeast(0))

        // ── Dialog ───────────────────────────────────────────────────────────

        return AlertDialog.Builder(ctx)
            .setTitle("Platform MQTT Settings")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->

                val selectedPlatform = platforms[spinner.selectedItemPosition]
                val config: MqttConfig = when (selectedPlatform) {

                    IotPlatform.THINGSBOARD -> {
                        val server = etTbServer.text.toString().trim()
                        val port   = etTbPort.text.toString().trim().ifEmpty { "1883" }
                        if (server.isBlank()) {
                            Toast.makeText(ctx, "Server address is required", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val cfg = MqttConfig.ThingsBoard(
                            server       = server,
                            port         = port,
                            cisternToken = etTbCisternToken.text.toString().trim(),
                            tankToken    = etTbTankToken.text.toString().trim()
                        )
                        if (!cfg.isCisternConfigured && !cfg.isTankConfigured) {
                            Toast.makeText(
                                ctx, "Enter at least one device access token",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        cfg
                    }

                    IotPlatform.KONKER -> {
                        val server = etKonkerServer.text.toString().trim()
                        val port   = etKonkerPort.text.toString().trim().ifEmpty { "1883" }
                        if (server.isBlank()) {
                            Toast.makeText(ctx, "Server address is required", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val cfg = MqttConfig.Konker(
                            server       = server,
                            port         = port,
                            user         = etKonkerUser.text.toString().trim(),
                            pass         = etKonkerPass.text.toString(),
                            cisternTopic = etKonkerCisternTopic.text.toString().trim(),
                            tankTopic    = etKonkerTankTopic.text.toString().trim()
                        )
                        if (!cfg.isCisternConfigured && !cfg.isTankConfigured) {
                            Toast.makeText(
                                ctx,
                                "Enter username and at least one device topic",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        cfg
                    }
                }

                store.save(config)
                onConfigSaved(config)
                Toast.makeText(ctx, "${selectedPlatform.displayName} settings saved",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        const val TAG = "PlatformConfigDialog"
    }
}