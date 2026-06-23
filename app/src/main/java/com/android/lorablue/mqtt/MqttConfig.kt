package com.android.lorablue.mqtt

import android.content.Context
import androidx.core.content.edit

/**
 * MQTT connection parameters needed to publish to a Konker broker.
 * Konker's MQTT integration authenticates with username/password exactly
 * like a generic broker (no special device-id scheme), so this mirrors the
 * publisher config fields from the wifiradar reference app: server, port,
 * topic, user, pass.
 */
data class MqttConfig(
    val server: String,
    val port: String,
    val topic: String,
    val user: String,
    val pass: String
) {
    val isComplete: Boolean
        get() = server.isNotBlank() && topic.isNotBlank()

    val brokerUrl: String
        get() = "tcp://$server:${port.ifBlank { "1883" }}"
}

/**
 * Thin SharedPreferences wrapper, mirroring the persistence pattern used in
 * wifiradar's MainActivity (prefs.edit { putString(...) } / prefs.getString).
 * Centralised here instead of inline in the dialog so MqttPublisher and the
 * config dialog both read/write through one source of truth.
 */
class MqttConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("konker_mqtt_config", Context.MODE_PRIVATE)

    fun load(): MqttConfig = MqttConfig(
        server = prefs.getString("server", "") ?: "",
        port   = prefs.getString("port", "1883") ?: "1883",
        topic  = prefs.getString("topic", "") ?: "",
        user   = prefs.getString("user", "") ?: "",
        pass   = prefs.getString("pass", "") ?: ""
    )

    fun save(config: MqttConfig) {
        prefs.edit {
            putString("server", config.server)
            putString("port", config.port)
            putString("topic", config.topic)
            putString("user", config.user)
            putString("pass", config.pass)
        }
    }
}