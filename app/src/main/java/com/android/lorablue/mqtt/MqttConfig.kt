package com.android.lorablue.mqtt

import android.content.Context
import androidx.core.content.edit

// ─────────────────────────────────────────────────────────────────────────────
// Platform selector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Supported IoT platforms. Each has its own authentication model:
 *
 * [THINGSBOARD]
 *   - Fixed topic: "v1/devices/me/telemetry"
 *   - Auth: per-device access token sent as MQTT Username (no password).
 *   - Two separate connections are opened per telemetry cycle so each device
 *     is identified by its own token.
 *
 * [KONKER]
 *   - Configurable topic per device (Cistern / Tank).
 *   - Auth: shared Username + Password for the Konker application.
 *   - One connection is opened per publish; topic distinguishes the device.
 */
enum class IotPlatform(val displayName: String) {
    THINGSBOARD("ThingsBoard"),
    KONKER("Konker")
}

// ─────────────────────────────────────────────────────────────────────────────
// Config model — sealed to make platform-specific fields explicit and safe
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sealed class: each subclass carries exactly the fields that make sense for
 * its platform — no nullable placeholders or "unused" fields.
 *
 * Both share [server], [port], and [brokerUrl] via the base class.
 */
sealed class MqttConfig {

    abstract val server: String
    abstract val port: String

    val brokerUrl: String
        get() = "tcp://$server:${port.ifBlank { "1883" }}"

    val platform: IotPlatform
        get() = when (this) {
            is ThingsBoard -> IotPlatform.THINGSBOARD
            is Konker      -> IotPlatform.KONKER
        }

    // ── ThingsBoard ──────────────────────────────────────────────────────────

    /**
     * @param cisternToken Access token for the Cistern device (MQTT Username).
     * @param tankToken    Access token for the Tank device (MQTT Username).
     */
    data class ThingsBoard(
        override val server: String,
        override val port: String,
        val cisternToken: String,
        val tankToken: String
    ) : MqttConfig() {

        /** Fixed telemetry topic — the same for every ThingsBoard device. */
        val telemetryTopic: String get() = "v1/devices/me/telemetry"

        val isCisternConfigured: Boolean
            get() = server.isNotBlank() && cisternToken.isNotBlank()

        val isTankConfigured: Boolean
            get() = server.isNotBlank() && tankToken.isNotBlank()
    }

    // ── Konker ───────────────────────────────────────────────────────────────

    /**
     * @param user         Konker application username (shared by both devices).
     * @param pass         Konker application password.
     * @param cisternTopic MQTT topic for the Cistern device on Konker.
     * @param tankTopic    MQTT topic for the Tank device on Konker.
     */
    data class Konker(
        override val server: String,
        override val port: String,
        val user: String,
        val pass: String,
        val cisternTopic: String,
        val tankTopic: String
    ) : MqttConfig() {

        val isCisternConfigured: Boolean
            get() = server.isNotBlank() && user.isNotBlank() && cisternTopic.isNotBlank()

        val isTankConfigured: Boolean
            get() = server.isNotBlank() && user.isNotBlank() && tankTopic.isNotBlank()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistence — SharedPreferences, one flat namespace for both platforms
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Persists whichever [MqttConfig] subclass is currently active.
 * All keys are stored in a single prefs file; a "platform" key selects the
 * active subclass on load. Platform-specific keys that are absent simply
 * default to "".
 */
class MqttConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("iot_platform_config", Context.MODE_PRIVATE)

    /**
     * Loads the config for a **specific** platform regardless of which one is
     * currently active. Used by [MqttConfigDialog] to pre-fill both form
     * sections so previously entered credentials are not lost when the user
     * switches between platforms in the spinner.
     */
    fun loadPlatform(platform: IotPlatform): MqttConfig {
        return when (platform) {
            IotPlatform.THINGSBOARD -> MqttConfig.ThingsBoard(
                server       = prefs.getString("tb_server", "") ?: "",
                port         = prefs.getString("tb_port", "1883") ?: "1883",
                cisternToken = prefs.getString("tb_cistern_token", "") ?: "",
                tankToken    = prefs.getString("tb_tank_token",    "") ?: ""
            )
            IotPlatform.KONKER -> MqttConfig.Konker(
                server       = prefs.getString("konker_server", "") ?: "",
                port         = prefs.getString("konker_port", "1883") ?: "1883",
                user         = prefs.getString("konker_user",          "") ?: "",
                pass         = prefs.getString("konker_pass",          "") ?: "",
                cisternTopic = prefs.getString("konker_cistern_topic", "") ?: "",
                tankTopic    = prefs.getString("konker_tank_topic",    "") ?: ""
            )
        }
    }

    fun load(): MqttConfig {
        val platformName = prefs.getString("platform", IotPlatform.THINGSBOARD.name)
            ?: IotPlatform.THINGSBOARD.name

        return when (platformName) {
            IotPlatform.KONKER.name -> MqttConfig.Konker(
                server       = prefs.getString("konker_server", "") ?: "",
                port         = prefs.getString("konker_port", "1883") ?: "1883",
                user         = prefs.getString("konker_user", "") ?: "",
                pass         = prefs.getString("konker_pass", "") ?: "",
                cisternTopic = prefs.getString("konker_cistern_topic", "") ?: "",
                tankTopic    = prefs.getString("konker_tank_topic", "") ?: ""
            )
            else -> MqttConfig.ThingsBoard(
                server       = prefs.getString("tb_server", "") ?: "",
                port         = prefs.getString("tb_port", "1883") ?: "1883",
                cisternToken = prefs.getString("tb_cistern_token", "") ?: "",
                tankToken    = prefs.getString("tb_tank_token", "") ?: ""
            )
        }
    }

    fun save(config: MqttConfig) {
        prefs.edit {
            putString("platform", config.platform.name)

            when (config) {
                is MqttConfig.ThingsBoard -> {
                    putString("tb_server",        config.server)
                    putString("tb_port",          config.port)
                    putString("tb_cistern_token", config.cisternToken)
                    putString("tb_tank_token",    config.tankToken)
                }
                is MqttConfig.Konker -> {
                    putString("konker_server",        config.server)
                    putString("konker_port",          config.port)
                    putString("konker_user",          config.user)
                    putString("konker_pass",          config.pass)
                    putString("konker_cistern_topic", config.cisternTopic)
                    putString("konker_tank_topic",    config.tankTopic)
                }
            }
        }
    }
}