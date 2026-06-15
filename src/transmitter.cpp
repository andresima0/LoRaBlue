#include <Arduino.h>
#include "radio.h"
#include "sensors.h"

void setup() {
    Serial.begin(115200);

    // Initialize LoRa Radio (no halting on failure)
    bool radio_status = enable_radio();

    // LED Setup
    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    // Hardware setup
    set_lora_tx_pins();
    setup_sensors(); // Initialize I2C and VL53L1X (no halting on failure)

    // Battery read adjustments
    pinMode(VBAT_ENABLE, OUTPUT); 
    digitalWrite(VBAT_ENABLE, HIGH); 
    analogReadResolution(10);
}

void loop() {
    // Non-blocking timer to respect the 10-second tx_interval
    if (millis() - last_tx_time >= tx_interval || last_tx_time == 0) {
        last_tx_time = millis();

        data.turbidity = get_turbidity();
        data.water_lvl = get_water_lvl() / 1000.0; // Converted to meters
        
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