#ifndef SENSORS_H
#define SENSORS_H

#include <Arduino.h>

#define TURBIDITY_PIN A0

#define CLEAN_WATER 875
#define DIRTY_WATER 30

float get_turbidity(void);

#endif