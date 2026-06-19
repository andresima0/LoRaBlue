#include "radio.h"
#include "sensors.h"
#include <Arduino.h>

void setup() {
    Serial.begin(115200);

    while (true) {
        bool status = enable_radio();

        if (status == false) {
            Serial.println("[receiver] radio isn't working!");
            delay(256);
            continue;
        }

        set_lora_tx_pins();
        break;
    }
}

void loop() {

    if (radio_rx_status == true) {
        radio_rx_status = false;

        receive_data(&data);

        Serial.printf("[receiver] Data Received: \n");
        Serial.printf("water_lvl: %.2f\n", data.water_lvl);
        Serial.printf("turbidity: %.2f\n", data.turbidity);
        Serial.printf("pump_status: %d\n", data.pump_status);
        Serial.printf("bat_percent: %.2f\n", data.bat_percent);
    }

    delay(128);
}