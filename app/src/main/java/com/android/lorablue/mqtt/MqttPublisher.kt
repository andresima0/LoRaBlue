package com.android.lorablue.mqtt

import android.util.Log
import com.android.lorablue.data.TelemetryReading
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Publishes telemetry to a Konker MQTT broker. Connects, publishes one
 * message, then disconnects — this is the connect-per-publish pattern from
 * wifiradar's btnPublish flow, not a long-lived persistent connection.
 *
 * Cistern and Tank readings go to different topics (config.cisternTopic /
 * config.tankTopic) since Konker treats them as separate devices. Each
 * call publishes exactly one reading to exactly one topic — there is no
 * batching of both devices into a single MQTT message.
 *
 * A short-lived connection per message is intentional here: telemetry from
 * the LoRa gateway arrives sporadically (every few seconds at most), so
 * paying the TCP+MQTT handshake cost per publish is simpler and more
 * robust against flaky Wi-Fi than maintaining a background keep-alive
 * connection. If publish frequency increases later, this is the place to
 * switch to a persistent client with reconnect logic.
 *
 * Every call runs on a background thread (kotlin.concurrent.thread) because
 * MqttClient.connect()/publish() are blocking calls and must never run on
 * the main/UI thread.
 */
class MqttPublisher {

    companion object {
        private const val TAG = "MQTT_PUB"
    }

    var onPublishResult: (success: Boolean, message: String) -> Unit = { _, _ -> }

    fun publish(config: MqttConfig, reading: TelemetryReading) {
        val topic: String
        val payload: String

        when (reading) {
            is TelemetryReading.Cistern -> {
                if (!config.isCisternComplete) {
                    Log.w(TAG, "Publish skipped — Cistern topic not configured")
                    return
                }
                topic = config.cisternTopic
                payload = JSONObject().apply {
                    put("id", TelemetryReading.DEVICE_ID_CISTERN)
                    put("water_lvl", reading.waterLevel)
                    put("water_pump", reading.pumpOn)
                    put("batt_lvl", reading.batteryPercent)
                    put("rssi_lvl", reading.rssiDbm)
                }.toString()
            }

            is TelemetryReading.Tank -> {
                if (!config.isTankComplete) {
                    Log.w(TAG, "Publish skipped — Tank topic not configured")
                    return
                }
                topic = config.tankTopic
                payload = JSONObject().apply {
                    put("id", TelemetryReading.DEVICE_ID_TANK)
                    put("water_lvl", reading.waterLevel)
                    put("turbidity", reading.turbidity)
                    put("batt_lvl", reading.batteryPercent)
                    put("rssi_lvl", reading.rssiDbm)
                }.toString()
            }
        }

        if (config.server.isBlank()) {
            Log.w(TAG, "Publish skipped — MQTT server not configured")
            return
        }

        thread {
            val result = runCatching {
                val clientId = "lorablue-pub-" + System.currentTimeMillis()
                val client = MqttClient(config.brokerUrl, clientId, MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 20
                    if (config.user.isNotEmpty()) {
                        userName = config.user
                        if (config.pass.isNotEmpty()) password = config.pass.toCharArray()
                    }
                }
                client.connect(opts)
                client.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 1 })
                client.disconnect()
                client.close()
                payload
            }

            result.fold(
                onSuccess = {
                    Log.d(TAG, "Published to $topic: $it")
                    onPublishResult(true, "Published to Konker [$topic]")
                },
                onFailure = { e ->
                    val msg = formatError(e, topic)
                    Log.e(TAG, msg)
                    onPublishResult(false, msg)
                }
            )
        }
    }

    private fun formatError(e: Throwable, topic: String): String {
        val reason = (e as? MqttException)?.let { "reasonCode=${it.reasonCode} " } ?: ""
        return "MQTT error [$topic]: ${e.javaClass.simpleName} $reason${e.message ?: ""}"
    }
}