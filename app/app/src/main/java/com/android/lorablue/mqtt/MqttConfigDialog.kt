package com.android.lorablue.mqtt

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Settings dialog for the IoT platforms.
 *
 * A [Spinner] at the top only controls which section is currently visible
 * for editing — it is NOT an exclusive "active platform" selector anymore.
 * Each platform (ThingsBoard / Konker) carries its own "Enable publishing"
 * checkbox, so both can be turned on at the same time and telemetry will be
 * published to both simultaneously (see BleViewModel.mqttConfigs).
 *
 * Both platforms persist their settings independently in [MqttConfigStore], so
 * switching the spinner between ThingsBoard and Konker never loses either
 * set of credentials, and saving always writes BOTH sections regardless of
 * which one is currently shown — otherwise toggling the spinner right
 * before hitting Save could silently disable/lose the other platform's
 * enabled flag.
 *
 * [onConfigSaved] is called with the list of currently ENABLED configs
 * immediately after saving, so [BleViewModel] can update its in-memory
 * cache without an app restart.
 */
class MqttConfigDialog : DialogFragment() {

    var onConfigSaved: (List<MqttConfig>) -> Unit = {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val store = MqttConfigStore(ctx)

        // Load whatever is currently saved; used to select the initially
        // visible section in the spinner (last platform the user edited).
        val current = store.load()

        val dp = ctx.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val sectionSpacing = (14 * dp).toInt()
        val itemSpacing = (6 * dp).toInt()

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

        // ── ThingsBoard fields ───────────────────────────────────────────────
        // Each platform keeps its own server/port fields so switching the
        // spinner never overwrites the other platform's broker address.

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

        // ── Platform spinner (view switcher only — not an exclusive toggle) ──

        val platforms = IotPlatform.values()
        val spinner = Spinner(ctx)
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
            addView(label("Edit platform"))
            addView(spinner)
            addView(formContainer) // swapped out by spinner selection
        }

        // Swap visible section when the spinner changes. Both sections'
        // EditTexts/CheckBoxes stay alive off-screen, so their values are
        // never lost when switching back and forth.
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

        // Select the platform that was last edited/active, purely as a
        // starting point for which section is shown first.
        val savedIndex = platforms.indexOfFirst { it == current.platform }
        spinner.setSelection(savedIndex.coerceAtLeast(0))

        // ── Dialog ───────────────────────────────────────────────────────────

        return AlertDialog.Builder(ctx)
            .setTitle("Platform MQTT Settings")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->

                // Build BOTH configs regardless of which section is currently
                // visible — both can be enabled simultaneously, so both must
                // always be validated and persisted together.
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

                // Validate only the platforms the user actually enabled —
                // a disabled platform can be left blank/incomplete.
                if (tbConfig.enabled) {
                    if (tbConfig.server.isBlank()) {
                        Toast.makeText(ctx, "ThingsBoard: server address is required", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!tbConfig.isCisternConfigured && !tbConfig.isTankConfigured) {
                        Toast.makeText(
                            ctx, "ThingsBoard: enter at least one device access token",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                }

                if (konkerConfig.enabled) {
                    if (konkerConfig.server.isBlank()) {
                        Toast.makeText(ctx, "Konker: server address is required", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!konkerConfig.isCisternConfigured && !konkerConfig.isTankConfigured) {
                        Toast.makeText(
                            ctx,
                            "Konker: enter username and at least one device topic",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                }

                store.save(tbConfig)
                store.save(konkerConfig)

                val enabledConfigs = listOf(tbConfig, konkerConfig).filter { it.enabled }
                onConfigSaved(enabledConfigs)

                val enabledNames = enabledConfigs.map { it.platform.displayName }
                Toast.makeText(
                    ctx,
                    if (enabledNames.isEmpty()) "Settings saved — no platform enabled"
                    else "Settings saved — publishing to: ${enabledNames.joinToString(" and ")}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        const val TAG = "PlatformConfigDialog"
    }
}