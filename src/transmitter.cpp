#include <Arduino.h>
#include "radio.h"
#include "sensors.h"

void setup() {
    bool radio_status = false;
    radio_status = enable_radio();

    if (radio_status) {
        while(1) { 
            delay(100);
        };
    }

    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    set_lora_tx_pins();
}

void loop() {
    data.turbidity = get_turbidity();
    /*
    data.water_lvl = get_water_lvl();
    data.pump_status = get_pump_status();
    data.bat_lvl = get_bat_lvl();
    */

    send_data(data);
  }