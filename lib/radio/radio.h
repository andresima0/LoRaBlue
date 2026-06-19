#ifndef RADIO_H
#define RADIO_H

#include <Arduino.h>
#include <RadioLib.h>

// lora module pins
#define LORA_NSS D4
#define LORA_DIO1 D1
#define LORA_NRST D2
#define LORA_BUSY D3
#define LORA_RF_SW D5

struct TelemetryData {
    float water_lvl;
    float turbidity;
    bool pump_status;
    float bat_percent;
};

extern SX1262 radio;
extern TelemetryData data;
extern unsigned long last_tx_time;
extern const unsigned long tx_interval;
extern bool radio_rx_status;

// general procedures
bool enable_radio(void);

// tx procedures
void set_lora_tx_pins(void);
bool send_data(TelemetryData data);

// rx procedures
void set_lora_rx_pins(void);
bool receive_data(TelemetryData *data);
void lora_rx_callback(void);

#endif
