#include "sensors.h"

float get_turbidity(void) {
    long sum = 0;

    for (uint8_t i = 0; i < 10; i++) {
        sum += analogRead(TURBIDITY_PIN);
        delay(5);
    }

    int raw_read = constrain(sum / 10, DIRTY_WATER, CLEAN_WATER);
    float percent = map(raw_read, DIRTY_WATER, CLEAN_WATER, 0, 100);

    Serial.print("[Sensor] RAW: ");
    Serial.print(raw_read);
    Serial.print(" | Turbidity: ");
    Serial.print(percent, 1);
    Serial.println("%");

    return percent;
}