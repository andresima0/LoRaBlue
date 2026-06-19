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

// ------------------------------------
// SHARED)
// ------------------------------------
bool enable_radio(void);

// ------------------------------------
// EXCLUSIVE TO TRANSMITTER(TX)
// ------------------------------------
extern unsigned long last_tx_time;
extern const unsigned long tx_interval;
void set_lora_tx_pins(void);
bool send_data(TelemetryData d);

// ------------------------------------
// EXCLUSIVE TO RECEIVER (RX)
// ------------------------------------
extern volatile bool radio_rx_status;
void set_lora_rx_pins(void);
bool receive_data(TelemetryData *d);
void lora_rx_callback(void);

#endif