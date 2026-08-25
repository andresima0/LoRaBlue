#include "radio.h"
#include "sensors.h"
#include <Arduino.h>

#ifndef DEVICE_ID
#error "DEVICE_ID not defined — set build_flags = -DDEVICE_ID=DEVICE_ID_CISTERN (or _TANK) in platformio.ini"
#endif

void setup() {
    Serial.begin(115200);

    // Initialize LoRa Radio
    bool radio_status = enable_radio();

    if (radio_status == false) {
        delay(3e3);
        Serial.println("Radio functionality must be on!");
        delay(3e3);

        while (1) {
            Serial.println("Check LoRa connections!");
            delay(100);
        }
    }

    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    // Hardware setup
    set_lora_tx_pins();
    // Initialize I2C and VL53L1X
    setup_sensors();

    data.id = DEVICE_ID;

    Serial.print(F("[TX] Device role: "));
    Serial.println(DEVICE_ID == DEVICE_ID_CISTERN ? "Cistern" : "Tank");
}

void loop() {

    if (millis() - last_tx_time >= tx_interval || last_tx_time == 0) {
        last_tx_time = millis();

        data.water_lvl = get_water_lvl();
        data.bat_percent = get_battery_percent();

     #if DEVICE_ID == DEVICE_ID_CISTERN
            data.turbidity = 0.0f;
            data.pump_status = get_pump_status();
            
        #elif DEVICE_ID == DEVICE_ID_TANK
            data.turbidity = get_turbidity();
            data.pump_status = false;
        #endif

        send_data(data);
    }
}