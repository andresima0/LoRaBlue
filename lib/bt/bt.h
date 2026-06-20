#include <Arduino.h>
#include <bluefruit.h>

extern BLEUart bleuart;

volatile bool loraPacketReady = false;
String bleCommandBuffer = "";
volatile bool hasNewBleCommand = false;
unsigned long lastHeartbeat = 0;

void setupBLE(void);
void bleUartRxCallback(uint16_t conn_hdl);
void bleSend(const char *msg);