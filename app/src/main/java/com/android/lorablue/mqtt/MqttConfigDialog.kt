package com.android.lorablue.mqtt

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Popup for editing Konker MQTT connection settings: server, port, a topic
 * per device (Cistern / Tank), user, pass. Server/port/user/pass are
 * shared by both devices; only the topic differs, since Konker registers
 * Cistern and Tank as two separate devices/applications.
 *
 * Values are loaded from / saved to MqttConfigStore (SharedPreferences).
 * Opened from a settings icon on MainActivity.
 */
class MqttConfigDialog : DialogFragment() {

    var onConfigSaved: (MqttConfig) -> Unit = {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val store = MqttConfigStore(context)
        val current = store.load()

        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val sectionSpacing = (12 * context.resources.displayMetrics.density).toInt()

        val etServer = EditText(context).apply {
            hint = "Server (e.g. mqtt.konkerlabs.net)"
            setText(current.server)
        }
        val etPort = EditText(context).apply {
            hint = "Port (default 1883)"
            setText(current.port)
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
        val etCisternTopic = EditText(context).apply {
            hint = "Cistern topic"
            setText(current.cisternTopic)
        }
        val etTankTopic = EditText(context).apply {
            hint = "Tank topic"
            setText(current.tankTopic)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(etServer)
            addView(etPort)
            addView(etUser)
            addView(etPass)

            addView(TextView(context).apply {
                text = "Device topics"
                setPadding(0, sectionSpacing, 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(etCisternTopic)
            addView(etTankTopic)
        }

        return AlertDialog.Builder(context)
            .setTitle("Konker MQTT Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val config = MqttConfig(
                    server = etServer.text.toString().trim(),
                    port = etPort.text.toString().trim().ifEmpty { "1883" },
                    cisternTopic = etCisternTopic.text.toString().trim(),
                    tankTopic = etTankTopic.text.toString().trim(),
                    user = etUser.text.toString().trim(),
                    pass = etPass.text.toString()
                )

                if (config.server.isBlank()) {
                    Toast.makeText(context, "Server is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (config.cisternTopic.isBlank() && config.tankTopic.isBlank()) {
                    Toast.makeText(
                        context,
                        "Set at least one topic (Cistern or Tank)",
                        Toast.LENGTH_SHORT
                    ).show()
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