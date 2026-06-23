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
            Serial.printf("\n[receiver] Data Received: \n");
            Serial.printf("water_lvl: %.2f m\n", data.water_lvl);
            Serial.printf("turbidity: %.2f NTU\n", data.turbidity);
            Serial.printf("pump_status: %s\n", data.pump_status ? "ON" : "OFF");
            Serial.printf("bat_percent: %.2f %%\n", data.bat_percent);

            if (Bluefruit.connected()) {
                char jsonPayload[150];
                snprintf(jsonPayload, sizeof(jsonPayload),
                         "{\"water\":%.2f,\"turbidity\":%.1f,\"pump\":%d,"
                         "\"batt\":%.1f,\"rssi\":%.1f}\n",
                         data.water_lvl, data.turbidity,
                         data.pump_status ? 1 : 0, data.bat_percent, rssi);

                bleSend(jsonPayload);
                Serial.print(F("[BLE] JSON dispatched: "));
                Serial.print(jsonPayload);

            } else {
                Serial.println(F("[BLE] No device connected."));
            }

        } else {
            Serial.println(F("Failed to receive data (CRC error)."));
        }
    }

    delay(10);
}