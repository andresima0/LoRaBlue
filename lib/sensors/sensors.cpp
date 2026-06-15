#include "sensors.h"

TwoWire i2c_conn(NRF_TWIM1, NRF_TWIS1, SPIM1_SPIS1_TWIM1_TWIS1_SPI1_TWI1_IRQn,
                 MY_SDA, MY_SCL);
SFEVL53L1X distance_sensor(i2c_conn);

// #TODO: add initial steps check for turbidity value
bool setup_sensors(void) {

    i2c_conn.begin();
    delay(50);

    if (distance_sensor.begin() != 0)
        return false;

    distance_sensor.setDistanceModeShort();
    distance_sensor.startRanging();

    return true;
}

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

uint16_t get_water_lvl(void) {
    uint16_t distance = 0;

    if (distance_sensor.checkForDataReady()) {
        distance = distance_sensor.getDistance();
        distance_sensor.clearInterrupt();
    }
    return distance / 1000;
}