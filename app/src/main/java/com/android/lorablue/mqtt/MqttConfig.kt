package com.android.lorablue.mqtt

import android.content.Context
import androidx.core.content.edit

/**
 * MQTT connection parameters needed to publish to a Konker broker.
 * Server/port/user/pass are shared across both devices — Konker
 * distinguishes the two devices by the broker-side client/auth mechanism,
 * not by anything this app controls — but each device needs its own topic
 * since Cistern and Tank are registered as separate devices in Konker.
 */
data class MqttConfig(
    val server: String,
    val port: String,
    val cisternTopic: String,
    val tankTopic: String,
    val user: String,
    val pass: String
) {
    val isCisternComplete: Boolean
        get() = server.isNotBlank() && cisternTopic.isNotBlank()

    val isTankComplete: Boolean
        get() = server.isNotBlank() && tankTopic.isNotBlank()

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
        port = prefs.getString("port", "1883") ?: "1883",
        cisternTopic = prefs.getString("cistern_topic", "") ?: "",
        tankTopic = prefs.getString("tank_topic", "") ?: "",
        user = prefs.getString("user", "") ?: "",
        pass = prefs.getString("pass", "") ?: ""
    )

    fun save(config: MqttConfig) {
        prefs.edit {
            putString("server", config.server)
            putString("port", config.port)
            putString("cistern_topic", config.cisternTopic)
            putString("tank_topic", config.tankTopic)
            putString("user", config.user)
            putString("pass", config.pass)
        }
    }
}