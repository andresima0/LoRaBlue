#include "sensors.h"

TwoWire i2c_conn(NRF_TWIM1, NRF_TWIS1, SPIM1_SPIS1_TWIM1_TWIS1_SPI1_TWI1_IRQn,
                 MY_SDA, MY_SCL);
SFEVL53L1X distance_sensor(i2c_conn);

bool setup_sensors(void) {

    i2c_conn.begin();
    delay(50);

    if (distance_sensor.begin() != 0)
        return false;

    distance_sensor.setDistanceModeShort();
    distance_sensor.startRanging();

    pinMode(PUMP_STATUS_PIN, INPUT_PULLUP);

    pinMode(PIN_VBAT, INPUT);
    pinMode(VBAT_ENABLE, OUTPUT);
    digitalWrite(VBAT_ENABLE, HIGH);

    return true;
}

float get_turbidity(void) {
    long sum = 0;

  for (uint8_t i = 0; i < 10; i++) {
        sum += analogRead(TURBIDITY_PIN);
        delay(5);
    }

    int raw_read = constrain(sum / 10, DIRTY_WATER, CLEAN_WATER);
    float ntu_value = map(raw_read, DIRTY_WATER, CLEAN_WATER, 3000, 0);

    if (ntu_value < 0) {
        ntu_value = 0;
    }

    return ntu_value;
}

float get_water_lvl(void) {
    uint16_t distance = 0;

    if (distance_sensor.checkForDataReady()) {
        distance = distance_sensor.getDistance();
        distance_sensor.clearInterrupt();
    }
    return distance / 1000.0f;
}

bool get_pump_status(void) {
    return digitalRead(PUMP_STATUS_PIN) == LOW;
}

float get_battery_voltage(void) {
    analogReference(AR_DEFAULT);
    analogReadResolution(12);

    digitalWrite(VBAT_ENABLE, LOW);
    delay(5);
    uint16_t adcCount = analogRead(PIN_VBAT);
    digitalWrite(VBAT_ENABLE, HIGH);

    analogReference(AR_DEFAULT);
    analogReadResolution(10);

    float adcVoltage = adcCount * BATT_VREF / BATT_ADC_MAX;
    return adcVoltage * BATT_DIVIDER_RATIO;
}

float get_battery_percent(void) {
    float v = get_battery_voltage();
    float pct = (v - BATT_VOLT_EMPTY) / (BATT_VOLT_FULL - BATT_VOLT_EMPTY) * 100.0f;
    return constrain(pct, 0.0f, 100.0f);
}