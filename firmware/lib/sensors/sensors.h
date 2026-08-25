#ifndef SENSORS_H
#define SENSORS_H

#include <Arduino.h>
#include <SparkFun_VL53L1X.h>
#include <Wire.h>

#define TURBIDITY_PIN A0
#define CLEAN_WATER 875
#define DIRTY_WATER 30

#define PUMP_STATUS_PIN D0

#define MY_SDA (6u)
#define MY_SCL (7u)

#define BATT_VREF     3.6f
#define BATT_ADC_MAX  4096.0f
#define BATT_DIVIDER_RATIO (1510.0f / 510.0f)
#define BATT_VOLT_EMPTY 3.3f
#define BATT_VOLT_FULL  4.2f

extern TwoWire i2c_conn;
extern SFEVL53L1X distance_sensor;

bool setup_sensors(void);

float get_turbidity(void);
float get_water_lvl(void);
bool  get_pump_status(void);
float get_battery_percent(void);
float get_battery_voltage(void);

#endif