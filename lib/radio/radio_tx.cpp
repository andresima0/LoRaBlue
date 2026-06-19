#include "radio.h"

unsigned long last_tx_time = 0;
const unsigned long tx_interval = 1e4; 

void set_lora_tx_pins(void) { 
    radio.setRfSwitchPins(LORA_RF_SW, LORA_RF_SW); 
}

bool send_data(TelemetryData d) {
    Serial.println(F("\n-- Transmitindo via LoRa --"));
    
    digitalWrite(LED_GREEN, LOW);
    int transmitState = radio.transmit((uint8_t *)&d, sizeof(TelemetryData));
    digitalWrite(LED_GREEN, HIGH);

    if (transmitState == RADIOLIB_ERR_NONE) {
        Serial.println(F("Success!"));
        return true;
    } else {
        Serial.print(F("Failed! Error code: "));
        Serial.println(transmitState);
        return false;
    }
}