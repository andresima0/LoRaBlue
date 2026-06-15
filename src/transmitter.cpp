#include "radio.h"
#include "sensors.h"
#include <Arduino.h>

void setup() {
    Serial.begin(115200);

    // Initialize LoRa Radio
    bool radio_status = enable_radio();

    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    // Hardware setup
    set_lora_tx_pins();
    // Initialize I2C and VL53L1X
    setup_sensors();

    // Battery read adjustments
    pinMode(VBAT_ENABLE, OUTPUT);
    digitalWrite(VBAT_ENABLE, HIGH);
    analogReadResolution(10);
}

void loop() {

    if (millis() - last_tx_time >= tx_interval || last_tx_time == 0) {
        last_tx_time = millis();

        data.turbidity = get_turbidity();
        data.water_lvl = get_water_lvl();

        // Default values to prevent the Android app from crashing
        data.pump_status = false;
        data.bat_percent = 100;
        /*
        data.pump_status = get_pump_status();
        data.bat_percent = get_bat_lvl();
        */

        send_data(data);
    }
}