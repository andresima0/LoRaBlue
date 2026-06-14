#include <Arduino.h>
#include <RadioLib.h>

#ifdef LORAIN_RX
#include <bluefruit.h>
#endif

// =============================================================================
// COMMON: PIN DEFINITIONS & RADIO INSTANCE
// =============================================================================
#define LORA_NSS   D4
#define LORA_DIO1  D1
#define LORA_NRST  D2
#define LORA_BUSY  D3
#define LORA_RF_SW D5

SX1262 radio = new Module(LORA_NSS, LORA_DIO1, LORA_NRST, LORA_BUSY);

// =============================================================================
// COMMON: NEW DATA STRUCTURE
// =============================================================================
struct __attribute__((packed)) TelemetryData {
  float water_level;     // Water level in meters (m)
  float turbidity;       // Turbidity in NTU
  bool  pump_active;     // Pump status (ON/OFF)
  float battery_percent; // Real Transmitter Battery (%)
};

// =============================================================================
// TX SPECIFIC VARIABLES & FUNCTIONS
// =============================================================================
#ifdef LORAIN_TX
TelemetryData txData;
unsigned long lastTransmitTime = 0;
const unsigned long transmitInterval = 10000; // 10 seconds

// Helper function for mapping with decimal places
float mapFloat(float x, float in_min, float in_max, float out_min, float out_max) {
  return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
}

// Real Battery Reading (Specific to XIAO nRF52840 hardware)
float readRealBattery() {
  // Activate the battery reading pin on the board
  digitalWrite(VBAT_ENABLE, LOW); 
  delay(5); // Time for the voltage to stabilize in the divider
  
  int rawBat = analogRead(PIN_VBAT);
  digitalWrite(VBAT_ENABLE, HIGH); // Turn off to save energy
  
  // On the XIAO, the voltage drops across a voltage divider.
  // Approximate values (10-bit ADC): 4.2V (100%) = ~430 RAW | 3.2V (0%) = ~330 RAW
  int constrainedBat = constrain(rawBat, 330, 430);
  return mapFloat(constrainedBat, 330, 430, 0.0, 100.0);
}
#endif

// =============================================================================
// RX SPECIFIC VARIABLES & FUNCTIONS
// =============================================================================
#ifdef LORAIN_RX
TelemetryData rxData;
BLEUart bleuart;

volatile bool loraPacketReady = false;
String        bleCommandBuffer = "";
volatile bool hasNewBleCommand = false;
unsigned long lastHeartbeat    = 0;

void onLoRaReceive() {
  loraPacketReady = true;
}

void bleSend(const char* msg) {
  bleuart.print(msg);
  bleuart.flush();
  yield();
}

void bleUartRxCallback(uint16_t conn_hdl) {
  (void) conn_hdl;
  bleCommandBuffer = bleuart.readString();
  bleCommandBuffer.trim();
  hasNewBleCommand = true;
}

void setupBLE() {
  Serial.println(F("[BLE] Initializing Bluetooth Low Energy..."));

  Bluefruit.configPrphConn(247, 247, 2, 2);
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("LoRain_Gateway");

  bleuart.setRxCallback(bleUartRxCallback);
  bleuart.begin();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(bleuart);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.addName();

  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  Serial.println(F("[BLE] Advertising as 'LoRain_Gateway'"));
}
#endif

// =============================================================================
// COMMON: SETUP
// =============================================================================
void setup() {
  Serial.begin(115200);
  
  #ifdef LORAIN_TX
  pinMode(LED_GREEN, OUTPUT);
  digitalWrite(LED_GREEN, HIGH);
  
  // Configuration of the microcontroller's internal battery pins
  pinMode(VBAT_ENABLE, OUTPUT); 
  digitalWrite(VBAT_ENABLE, HIGH); 
  analogReadResolution(10);
  randomSeed(analogRead(A0));
  #endif

  #ifdef LORAIN_RX
  pinMode(LED_BLUE, OUTPUT);
  pinMode(LED_RED,  OUTPUT);
  digitalWrite(LED_BLUE, HIGH);
  digitalWrite(LED_RED,  HIGH);
  #endif

  while (!Serial && millis() < 3000); 

  #ifdef LORAIN_TX
  Serial.println(F("\n[SX1262] Initializing TX Node..."));
  #endif

  #ifdef LORAIN_RX
  Serial.println(F("\n[SX1262] Initializing RX Node (LoRain IoT Gateway)..."));
  setupBLE();
  #endif

  int state = radio.begin(915.0, 125.0, 7, 5, 0x12, 22, 8);
  if (state == RADIOLIB_ERR_NONE) {
    Serial.println(F("[SX1262] Initialization successful!"));
  } else {
    Serial.print(F("[SX1262] Initialization failed, code: "));
    Serial.println(state);
    while (true);
  }

  #ifdef LORAIN_TX
  radio.setRfSwitchPins(LORA_RF_SW, LORA_RF_SW);
  #endif

  #ifdef LORAIN_RX
  radio.setRfSwitchPins(LORA_RF_SW, RADIOLIB_NC);
  radio.setPacketReceivedAction(onLoRaReceive);
  Serial.println(F("[SX1262] Starting continuous receive mode..."));
  radio.startReceive();
  #endif
}

// =============================================================================
// COMMON: LOOP
// =============================================================================
void loop() {

  // ---------------------------------------------------------------------------
  // TX LOGIC
  // ---------------------------------------------------------------------------
  #ifdef LORAIN_TX
  if (millis() - lastTransmitTime >= transmitInterval || lastTransmitTime == 0) {
    lastTransmitTime = millis();

    Serial.println(F("\n------------------------------------"));
    Serial.println(F("Collecting telemetry..."));

    // 1. Simulated Random Data
    txData.water_level = random(0, 500) / 100.0; // From 0.00m to 5.00m
    txData.turbidity = random(0, 1000) / 10.0;   // From 0.0 to 100.0 NTU
    txData.pump_active = random(0, 2);           // 0 or 1 (ON/OFF)
    
    // 2. Real Physical Data
    txData.battery_percent = readRealBattery();  // TX board battery (%)

    Serial.print(F("Water Level: ")); Serial.print(txData.water_level); Serial.println(F(" m"));
    Serial.print(F("Turbidity:   ")); Serial.print(txData.turbidity); Serial.println(F(" NTU"));
    Serial.print(F("Water Pump:  ")); Serial.println(txData.pump_active ? "ON" : "OFF");
    Serial.print(F("TX Battery:  ")); Serial.print(txData.battery_percent); Serial.println(F(" %"));

    Serial.print(F("[SX1262] Transmitting packet... "));
    
    digitalWrite(LED_GREEN, LOW);
    int transmitState = radio.transmit((uint8_t*)&txData, sizeof(TelemetryData));
    digitalWrite(LED_GREEN, HIGH);

    if (transmitState == RADIOLIB_ERR_NONE) {
      Serial.println(F("Success!"));
    } else {
      Serial.print(F("Failed! Error code: "));
      Serial.println(transmitState);
    }
  }
  #endif

  // ---------------------------------------------------------------------------
  // RX LOGIC
  // ---------------------------------------------------------------------------
  #ifdef LORAIN_RX
  if (hasNewBleCommand) {
    hasNewBleCommand = false;
    String command = bleCommandBuffer;

    if (command.equalsIgnoreCase("PING")) {
      digitalWrite(LED_RED, LOW);
      bleSend("{\"debug\":\"PONG\"}\n");
    } else if (command.equalsIgnoreCase("CLEAR")) {
      digitalWrite(LED_RED, HIGH);
      bleSend("{\"debug\":\"CLRD\"}\n");
    }
  }

  if (millis() - lastHeartbeat > 5000) {
    Serial.println(F("[System] Gateway is listening..."));
    lastHeartbeat = millis();
  }

  if (loraPacketReady) {
    loraPacketReady = false;
    digitalWrite(LED_BLUE, LOW);

    int state = radio.readData((uint8_t*)&rxData, sizeof(TelemetryData));

    if (state == RADIOLIB_ERR_NONE) {
      // 3. Real Physical Data (RSSI) Measured by the Gateway
      float rssi = radio.getRSSI();

      Serial.println(F("\n====== [ INCOMING TELEMETRY ] ======"));
      Serial.print(F("Water Level:  ")); Serial.print(rxData.water_level, 2); Serial.println(F(" m"));
      Serial.print(F("Turbidity:    ")); Serial.print(rxData.turbidity, 1); Serial.println(F(" NTU"));
      Serial.print(F("Water Pump:   ")); Serial.println(rxData.pump_active ? "ON" : "OFF");
      Serial.print(F("TX Battery:   ")); Serial.print(rxData.battery_percent, 1); Serial.println(F(" %"));
      Serial.print(F("Link RSSI:    ")); Serial.print(rssi, 1); Serial.println(F(" dBm"));

      if (Bluefruit.connected()) {
        char jsonPayload[150];
        // Final JSON format with the new keys
        snprintf(jsonPayload, sizeof(jsonPayload),
                 "{\"water\":%.2f,\"turbidity\":%.1f,\"pump\":%d,\"batt\":%.1f,\"rssi\":%.1f}\n",
                 rxData.water_level,
                 rxData.turbidity,
                 rxData.pump_active ? 1 : 0,
                 rxData.battery_percent,
                 rssi);

        bleSend(jsonPayload);
        Serial.print(F("[BLE] JSON dispatched: "));
        Serial.print(jsonPayload);

      } else {
        Serial.println(F("[BLE] No device connected."));
      }

    } else if (state == RADIOLIB_ERR_CRC_MISMATCH) {
      Serial.println(F("[SX1262] CRC error — packet corrupted."));
    } 

    delay(10);
    digitalWrite(LED_BLUE, HIGH);
    radio.startReceive();
  }
  #endif
}