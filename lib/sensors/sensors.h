#ifndef SENSORS_H
#define SENSORS_H

#include <Arduino.h>
#include <SparkFun_VL53L1X.h>
#include <Wire.h>

#define TURBIDITY_PIN A0
#define PUMP_STATUS_PIN D0

#define CLEAN_WATER 875
#define DIRTY_WATER 30

#define MY_SDA (6u)
#define MY_SCL (7u)

#define BATT_ADC_MIN 330
#define BATT_ADC_MAX 430

extern TwoWire i2c_conn;
extern SFEVL53L1X distance_sensor;

bool setup_sensors(void);

float get_turbidity(void);
float get_water_lvl(void);
bool  get_pump_status(void);
float get_battery_percent(void);

#endif