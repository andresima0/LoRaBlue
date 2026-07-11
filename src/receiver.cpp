#include "bt.h"
#include "radio.h"
#include <Arduino.h>

void setup() {
    Serial.begin(115200);

    // Security delay for USB native da XIAO nRF52840
    while (!Serial && millis() < 5000);

    while (true) {
        bool status = enable_radio();

        if (!status) {
            Serial.println(F("Failed to initialize SX1262. Trying again..."));
            delay(1000);
            continue;
        }

        set_lora_rx_pins();
        break;
    }

    Serial.println(F("\n[SX1262] Initializing RX Node (LoRaBlue IoT Gateway)..."));
    setupBLE();
}

// ----------------------------------------------------------------------
// Prints the TelemetryData just received over LoRa as a JSON line,
// mirroring the [TX] {json} log on the transmitter side (see
// log_telemetry in radio_tx.cpp). This is the over-the-air payload
// itself — separate from the [BLE] dispatched line below, which is the
// (slightly different, BLE-specific) payload forwarded to the app.
// ----------------------------------------------------------------------
void log_received(const TelemetryData &d, float rssi) {
    if (d.id == DEVICE_ID_CISTERN) {
        Serial.printf(
            "[RX] {\"id\":%d,\"water_lvl\":%.2f,\"water_pump\":%s,"
            "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
            d.id, d.water_lvl, d.pump_status ? "true" : "false",
            d.bat_percent, rssi);
    } else if (d.id == DEVICE_ID_TANK) {
        Serial.printf(
            "[RX] {\"id\":%d,\"water_lvl\":%.2f,\"turbidity\":%.1f,"
            "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
            d.id, d.water_lvl, d.turbidity, d.bat_percent, rssi);
    } else {
        Serial.print(F("[RX] WARNING: unknown device id="));
        Serial.println(d.id);
    }
}

// ----------------------------------------------------------------------
// Builds the BLE JSON payload for a Cistern (id=1) reading. Field names
// match what JsonParser.kt expects on the Android side: id, water_lvl,
// water_pump, batt_lvl, rssi_lvl.
// ----------------------------------------------------------------------
void dispatch_cistern(const TelemetryData &d, float rssi) {
    char jsonPayload[150];
    snprintf(jsonPayload, sizeof(jsonPayload),
             "{\"id\":%d,\"water_lvl\":%.2f,\"water_pump\":%s,"
             "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
             DEVICE_ID_CISTERN, d.water_lvl,
             d.pump_status ? "true" : "false", d.bat_percent, rssi);

    bleSend(jsonPayload);
    Serial.print(F("[BLE] Cistern JSON dispatched: "));
    Serial.print(jsonPayload);
}

// ----------------------------------------------------------------------
// Builds the BLE JSON payload for a Tank (id=2) reading. Field names
// match what JsonParser.kt expects: id, water_lvl, turbidity, batt_lvl,
// rssi_lvl.
// ----------------------------------------------------------------------
void dispatch_tank(const TelemetryData &d, float rssi) {
    char jsonPayload[150];
    snprintf(jsonPayload, sizeof(jsonPayload),
             "{\"id\":%d,\"water_lvl\":%.2f,\"turbidity\":%.1f,"
             "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
             DEVICE_ID_TANK, d.water_lvl, d.turbidity, d.bat_percent, rssi);

    bleSend(jsonPayload);
    Serial.print(F("[BLE] Tank JSON dispatched: "));
    Serial.print(jsonPayload);
}

void loop() {

    if (hasNewBleCommand) {
        hasNewBleCommand = false;
        String command = bleCommandBuffer;

        if (command.equalsIgnoreCase("PING")) {
            digitalWrite(LED_RED, LOW);
            bleSend("{\"debug\":\"PONG\"}\n");
        } else if (command.equalsIgnoreCase("CLEAR")) {
            digitalWrite(LED_RED, HIGH);
            bleSend("{\"debug\":\"CLRD\"}\n");
        }
    }

    //Gateway Heartbeat
    if (millis() - lastHeartbeat > 5000) {
        Serial.println(F("[System] Gateway is listening..."));
        lastHeartbeat = millis();
    }

    if (radio_rx_status == true) {
        radio_rx_status = false;

        float rssi = radio.getRSSI();

        if (receive_data(&data)) {
            log_received(data, rssi);

            if (!Bluefruit.connected()) {
                Serial.println(F("[BLE] No device connected."));
            } else if (data.id == DEVICE_ID_CISTERN) {
                dispatch_cistern(data, rssi);
            } else if (data.id == DEVICE_ID_TANK) {
                dispatch_tank(data, rssi);
            } else {
                Serial.print(F("[receiver] Unknown device id, packet dropped: "));
                Serial.println(data.id);
            }

        } else {
            Serial.println(F("Failed to receive data (CRC error)."));
        }
    }

    delay(10);
}