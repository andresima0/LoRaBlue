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
}

void loop() {

    if (radio_rx_status == true) {
        radio_rx_status = false;

        if (receive_data(&data)) {
            Serial.printf("[receiver] Data Received: \n");
            Serial.printf("water_lvl: %.2f m\n", data.water_lvl);
            Serial.printf("turbidity: %.2f NTU\n", data.turbidity);
            Serial.printf("pump_status: %s\n", data.pump_status ? "ON" : "OFF");
            Serial.printf("bat_percent: %.2f %%\n", data.bat_percent);
        } else {
            Serial.println(F("Failed to receive data (CRC error)."));
        }
    }

  delay(10);
}