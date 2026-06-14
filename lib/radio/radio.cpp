#include "radio.h"

SX1262 radio = new Module(LORA_NSS, LORA_DIO1, LORA_NRST, LORA_BUSY);
unsigned long last_tx_time = 0;
const unsigned long tx_interval = 1e3;
TelemetryData data = {0};

bool enable_radio(void) {
    // Carrier Frequency: 915MHz
    // Bandwidth: 125.0kHz
    // Spreading Factor: 7
    // Coding Rate: 5
    // 1-byte LoRa Sync Word: 0x12
    // Output Power: 22dBm
    // LoRa Preamble Length in Symbols: 8
    int state = radio.begin(915.0, 125.0, 7, 5, 0x12, 22, 8);

    if (state == RADIOLIB_ERR_NONE) {
        Serial.println(F("[SX1262] Initialization successful!"));
        return true;

    } else {
        Serial.print(F("[SX1262] Initialization failed, code: "));
        Serial.println(state);
        return false;
    }
}

void set_lora_tx_pins(void) {
    radio.setRfSwitchPins(LORA_RF_SW, LORA_RF_SW);
}

bool send_data(TelemetryData data) {
    Serial.print(F("Water Level: ")); Serial.print(data.water_lvl); Serial.println(F(" m"));
    Serial.print(F("Turbidity:   ")); Serial.print(data.turbidity); Serial.println(F(" NTU"));
    Serial.print(F("Water Pump:  ")); Serial.println(data.pump_status ? "ON" : "OFF");
    Serial.print(F("TX Battery:  ")); Serial.print(data.bat_status); Serial.println(F(" %"));

    digitalWrite(LED_GREEN, LOW);
    int transmitState = radio.transmit((uint8_t*)&data, sizeof(TelemetryData));
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