package com.android.lorablue.mqtt

import android.util.Log
import com.android.lorablue.data.TelemetryData
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

    fun publish(config: MqttConfig, telemetry: TelemetryData) {
        if (!config.isComplete) {
            Log.w(TAG, "Publish skipped — MQTT config incomplete (server/topic missing)")
            return
        }

        val payload = JSONObject().apply {
            put("water", telemetry.waterLevel)
            put("turbidity", telemetry.turbidity)
            put("pump", if (telemetry.pumpOn) 1 else 0)
            put("batt", telemetry.batteryPercent)
            put("rssi", telemetry.rssiDbm)
        }.toString()

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
                client.publish(config.topic, MqttMessage(payload.toByteArray()).apply { qos = 1 })
                client.disconnect()
                client.close()
                payload
            }

            result.fold(
                onSuccess = {
                    Log.d(TAG, "Published to ${config.topic}: $it")
                    onPublishResult(true, "Published to Konker: ${config.topic}")
                },
                onFailure = { e ->
                    val msg = formatError(e)
                    Log.e(TAG, msg)
                    onPublishResult(false, msg)
                }
            )
        }
    }

    private fun formatError(e: Throwable): String {
        val reason = (e as? MqttException)?.let { "reasonCode=${it.reasonCode} " } ?: ""
        return "MQTT error: ${e.javaClass.simpleName} $reason${e.message ?: ""}"
    }
}