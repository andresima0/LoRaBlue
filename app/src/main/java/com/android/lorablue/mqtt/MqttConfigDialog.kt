package com.android.lorablue.mqtt

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Popup for editing Konker MQTT connection settings: server, port, topic,
 * user, pass — the same field set as the wifiradar publisher panel.
 * Values are loaded from / saved to MqttConfigStore (SharedPreferences).
 *
 * Opened from a settings icon on MainActivity. onConfigSaved is invoked
 * after a successful save so the caller (Activity) can refresh anything
 * that depends on the config without this dialog needing to know about
 * BleViewModel or any other app component.
 */
class MqttConfigDialog : DialogFragment() {

    var onConfigSaved: (MqttConfig) -> Unit = {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val store = MqttConfigStore(context)
        val current = store.load()

        // Built programmatically (no separate XML layout) to keep this
        // dialog self-contained as a single file, mirroring the small
        // field count from wifiradar's publisher section.
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val etServer = EditText(context).apply {
            hint = "Server (e.g. mqtt.konkerlabs.net)"
            setText(current.server)
        }
        val etPort = EditText(context).apply {
            hint = "Port (default 1883)"
            setText(current.port)
        }
        val etTopic = EditText(context).apply {
            hint = "Topic"
            setText(current.topic)
        }
        val etUser = EditText(context).apply {
            hint = "Username"
            setText(current.user)
        }
        val etPass = EditText(context).apply {
            hint = "Password"
            setText(current.pass)
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(etServer)
            addView(etPort)
            addView(etTopic)
            addView(etUser)
            addView(etPass)
        }

        return AlertDialog.Builder(context)
            .setTitle("Konker MQTT Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val config = MqttConfig(
                    server = etServer.text.toString().trim(),
                    port = etPort.text.toString().trim().ifEmpty { "1883" },
                    topic = etTopic.text.toString().trim(),
                    user = etUser.text.toString().trim(),
                    pass = etPass.text.toString()
                )

                if (!config.isComplete) {
                    Toast.makeText(context, "Server and Topic are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                store.save(config)
                onConfigSaved(config)
                Toast.makeText(context, "Konker settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        const val TAG = "MqttConfigDialog"
    }
}