#include "radio.h"

unsigned long last_tx_time = 0;
const unsigned long tx_interval = 1e4;

void set_lora_tx_pins(void) {
    radio.setRfSwitchPins(LORA_RF_SW, LORA_RF_SW);
}

void log_telemetry(const TelemetryData &d) {
    if (d.id == DEVICE_ID_CISTERN) {
        Serial.printf(
            "[TX] {\"id\":%d,\"water_lvl\":%.2f,\"water_pump\":%s,\"batt_lvl\":%.1f}\n",
            d.id, d.water_lvl, d.pump_status ? "true" : "false", d.bat_percent);
    } else if (d.id == DEVICE_ID_TANK) {
        Serial.printf(
            "[TX] {\"id\":%d,\"water_lvl\":%.2f,\"turbidity\":%.1f,\"batt_lvl\":%.1f}\n",
            d.id, d.water_lvl, d.turbidity, d.bat_percent);
    } else {
        Serial.print(F("[TX] WARNING: unknown device id="));
        Serial.println(d.id);
    }
}

bool send_data(TelemetryData d) {
    Serial.println(F("\n-- Transmitindo via LoRa --"));
    log_telemetry(d);

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