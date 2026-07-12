# LoRaBlue IoT

> **Rainwater Harvesting & Management System** — a long‑range (LoRa) telemetry system for a cistern and an elevated water tank, bridged to a mobile app over BLE and to the cloud (ThingsBoard / Konker) over MQTT.

## English Version

## 1. Overview

Rainwater is abundant and free, yet most households still use fully treated potable water for non‑potable uses (toilet flushing, garden irrigation, washing), which can account for up to 50% of domestic consumption. This project implements a low‑cost IoT system that automates the **capture, monitoring and pumping of rainwater** between a lower reservoir (**cistern**) and an elevated reservoir (**tank**), while giving the user full remote visibility of the system through an Android app and a cloud dashboard.

The system deliberately separates two independent layers:

1. **Safety / operation layer (physical, no radio required)** — float switches wired directly into the pump's power circuit guarantee the pump never runs dry (low‑level cistern float cuts power) and the tank never overflows (high‑level tank float cuts power; low‑level tank float re‑enables the pump). This layer works even if the gateway, the app, or the internet connection is down.
2. **Telemetry layer (LoRa + BLE + MQTT)** — two low‑power sensor nodes report water level, water pump status, turbidity and battery to a gateway over LoRa. The gateway forwards this data over BLE to an Android app, which converts raw sensor readings into meaningful values (water column height, fill percentage), displays live cards and history charts, and republishes everything to a cloud IoT platform via MQTT.

```
Rain → Roof/Gutters → Cistern ──(pump)──> Tank ──> Household non‑potable use
                         │                    │
                    [Sensor Node 1]      [Sensor Node 2]
                    XIAO nRF52840        XIAO nRF52840
                    + SX1262 (LoRa)      + SX1262 (LoRa)
                    ToF + pump status    ToF + turbidity
                         │                    │
                         └────────LoRa────────┘
                                   │
                         [Gateway Receiver]
                         XIAO nRF52840 + SX1262
                         (LoRa RX only, BLE peripheral / NUS)
                                   │
                                  BLE
                                   │
                       [Android App — Raspberry Pi 5]
                       Android AOSP 16 · Kotlin (MVVM)
                                   │
                                 MQTT
                                   │
                    [ThingsBoard]        [Konker]
                    (production)         (optional / legacy)
```

> **Note:** the state of the safety floats (cistern low level, tank high/low level) is **not** currently reported to the app or the cloud — only the resulting pump ON/OFF status is (via the cistern's LoRa node). Instrumenting the floats directly is listed as a future improvement.

## 2. Repository layout

```
.
├── app/                          # Android application (Kotlin, Gradle)
│   └── app/src/main/java/com/android/lorablue/
│       ├── ble/                  # BLE layer: GATT lifecycle, operation queue, constants
│       ├── data/                 # JSON parsing, telemetry models, water level math
│       ├── chart/                # 10-minute history screen (custom Canvas chart)
│       └── mqtt/                 # MQTT publisher + ThingsBoard/Konker config UI
├── firmware/                     # PlatformIO firmware (Arduino framework, C++)
│   ├── src/main.cpp              # Shared TX/RX logic, selected via build flags
│   └── platformio.ini            # lorablue_tx / lorablue_rx environments
└── LICENSE                       # MIT
```

## 3. System architecture

### 3.1 Hardware

| Component | Model | Role |
|---|---|---|
| Sensor node MCU (×2) + Gateway receiver MCU | Seeed Studio **XIAO nRF52840** + **Wio‑SX1262** module | Sensor reading + LoRa TX (nodes) / LoRa RX + BLE peripheral (gateway) |
| Gateway host | **Raspberry Pi 5** (8 GB RAM, BCM2712 quad‑core Cortex‑A76 @ 2.4 GHz) | Runs Android AOSP 16, hosts the app, BLE central, publishes MQTT |
| Water level sensor | **VL53L1X** (Time‑of‑Flight, I²C) | Measures the air gap from the sensor (top) to the water surface |
| Water quality sensor | **DFRobot SEN0189** (analog turbidity) | Measures turbidity (NTU) — tank node only |
| Safety sensors | Horizontal reed‑switch floats | Cistern low level / Tank high level / Tank low level — mechanical safety loop, independent of the radio system |
| Actuator | Submersible pump + relay | Lifts water from cistern to tank |
| Power (sensor nodes) | Li‑Ion 3.7 V, 5200 mAh | Powers each battery‑operated sensor node |
| Power (gateway) | 5 V / 3 A mains adapter | Powers the Raspberry Pi 5 + LoRa receiver |

### 3.2 Radio link — LoRa (field communication)

- **Chip:** Semtech SX1262, via the [RadioLib](https://github.com/jgromes/RadioLib) library (`^6.6.0`).
- **Configuration:** 915 MHz, 125 kHz bandwidth, spreading factor 7, coding rate 4/5, sync word `0x12`, output power 22 dBm.
- **Topology:** simple point‑to‑point (P2P) — no LoRaWAN gateway/network server required, appropriate for a small, fixed number of sensor nodes.
- **Why LoRa:** long range and very low power draw compared to Wi‑Fi/BLE, penetrates walls/slabs between the reservoirs and the gateway, and license‑free ISM band.

### 3.3 BLE link — internal gateway communication

- **Profile:** Nordic UART Service (NUS), implemented with Adafruit's `bluefruit.h` (firmware side) and Android's native BLE APIs (app side).
- **Roles:** the LoRa receiver module acts as **BLE peripheral** (advertises as `LoRaBlue_Gateway`); the Android app acts as **BLE central** (scans, connects, subscribes to notifications).
- **UUIDs:** one Service UUID (used for scan filtering — more reliable than name filtering, since the device name often doesn't resolve during scanning), one RX characteristic (app → board commands) and one TX characteristic (board → app telemetry notifications), plus the standard CCCD descriptor to enable notifications.
- **Connection lifecycle:** Scanning → Connecting → Negotiating MTU (247 bytes, the BLE 4.2+ maximum) → Service Discovery → Connected. All GATT writes (descriptor/characteristic) are serialized through an operation queue (`ConcurrentLinkedQueue`), since Android's BLE stack only tolerates one pending GATT operation at a time — firing writes concurrently is a common source of silent failures. On disconnect, the GATT cache is force‑cleared via reflection (`gatt.refresh()`) so a reconnect always re‑discovers fresh services.
- **Message framing:** the firmware always terminates a JSON payload with `\n`. Because BLE can fragment a message across several notifications regardless of the negotiated MTU, the app reassembles chunks in a buffer and only parses a message once a `\n` is found (with a safety cap that clears the buffer if no delimiter ever arrives, to avoid a memory leak on firmware regressions).
- **Multiplexing:** both sensor nodes' data flow through the *same* physical LoRa receiver → BLE link. Each JSON message carries an `"id"` field (`1` = Cistern, `2` = Tank) used to route the reading to the correct UI card — this is how the single shared BLE connection safely serves two logically distinct devices.

**Board → App JSON (example):**
```json
{"water":1.23,"turbidity":45.6,"pump":1,"batt":88.0,"rssi":-67.5}
```

**App → Board commands:** `PING` (turns the board's red LED on, replies `{"debug":"PONG"}`) and `CLEAR` (turns it off, replies `{"debug":"CLRD"}`) — used from the app's **TX TEST** button to validate the link end‑to‑end.

### 3.4 MQTT — cloud communication

- **Library:** Eclipse Paho MQTT Client (`org.eclipse.paho.client.mqttv3`).
- **Pattern:** connect → publish once → disconnect, per reading — acceptable given the current telemetry rate (a reading every few seconds at most); consider a persistent client if the rate increases significantly.
- Both platforms can be **enabled simultaneously**; every incoming reading is published to every currently enabled platform.

| Platform | Identity model | Topic | Status in this project |
|---|---|---|---|
| **ThingsBoard** | Per‑device access token, used as MQTT username | Fixed: `v1/devices/me/telemetry` | **Production** — adopted after Konker showed connectivity instability during testing |
| **Konker** | Shared username/password | Configurable per device | Kept as a configurable option in the code, no longer used in operation |

The published payload always carries the **already‑converted** water reading — `water_dpt` (water column height, meters) and `water_pct` (fill percentage) — computed from the raw ToF sensor distance and the user‑configured total reservoir depth, plus `batt_lvl`, `rssi_lvl`, and either `water_pump` (Cistern) or `turbidity` (Tank). Both `water_dpt`/`water_pct` are sent as JSON `null` until the reservoir depth has been configured in the app.

### 3.5 Android application

- **Language / architecture:** Kotlin, MVVM (`AndroidViewModel` + `LiveData`), so state survives configuration changes (e.g. screen rotation).
- **Target host:** designed to run on a Raspberry Pi 5 with Android AOSP 16 acting as the gateway's brain, but works on any Android device (phone/tablet) with BLE support, `minSdk 24`.
- **Main screens:**
  - **MainActivity** — two live cards (Cistern / Tank) with water level (m + %), pump status or turbidity, battery, RSSI; buttons for `SCAN & CONNECT`, `TX TEST` and `PLATFORM SETTINGS`.
  - **Gear icon per card** → `TankDepthConfigDialog`, to input the reservoir's total depth (sensor‑to‑bottom distance), required to convert the raw distance into a usable water column height/percentage (`WaterLevelCalculator`).
  - **ChartActivity** — 10‑minute rolling history per device, metric selector (Water Level / Battery / RSSI / Turbidity or Pump Status, depending on the device), rendered with a custom `Canvas`‑based line chart (no external charting library).
  - **MqttSettingsActivity** — platform selector (ThingsBoard/Konker) with independent, persisted forms for each, so switching platforms never discards the other's previously entered credentials.
- **Local persistence:** `SharedPreferences` + JSON (Room was intentionally avoided due to a Kotlin/KSP‑vs‑AGP classpath conflict in this project's Gradle setup), with a 1‑hour retention window feeding the 10‑minute chart.

## 4. Requirements

### 4.1 Firmware

- [PlatformIO Core](https://platformio.org/install) (CLI) **or** [VS Code](https://code.visualstudio.com/) + the *PlatformIO IDE* extension.
- Git.
- A USB cable and drivers for the Seeed XIAO nRF52840 (board id: `seeed-xiao-afruitnrf52-nrf52840`).
- At least 3 boards for a full setup: 2 sensor nodes (cistern + tank) + 1 gateway receiver — or start with a TX/RX pair for a minimal bench test.

### 4.2 Android application

- [Android Studio](https://developer.android.com/studio) (recent stable release) **or** the Gradle wrapper included in `app/` (no separate Gradle install needed).
- JDK 21 (bundled with recent Android Studio, or install separately).
- Android SDK: `compileSdk 36` (minor API level 1), `targetSdk 36`, `minSdk 24`.
- A device to run the app on: Raspberry Pi 5 with Android AOSP 16 (as used in the reference prototype) **or** any Android phone/tablet with Bluetooth Low Energy support.
- Internet access on the running device, for MQTT publishing.

## 5. Installation & build guide

### 5.1 Clone the repository

```bash
git clone https://github.com/andresima0/LoRaBlue.git
cd LoRaBlue
```

### 5.2 Build & flash the firmware

```bash
cd firmware

# Build the sensor node (TX) firmware
pio run -e lorablue_tx

# Build the gateway receiver (RX) firmware
pio run -e lorablue_rx

# Flash to a connected board (repeat per role, one board at a time)
pio run -e lorablue_tx -t upload
pio run -e lorablue_rx -t upload

# Open the serial monitor to see debug output (115200 baud)
pio device monitor
```

Both environments share the same `src/main.cpp`; the active role is selected purely through the build flag defined in `platformio.ini` (`-D LORABLUE_TX` or `-D LORABLUE_RX`). Flash the TX firmware to both sensor nodes (cistern and tank) and the RX firmware to the gateway receiver board.

**Radio wiring (SX1262 module, as wired in `main.cpp`):**

| Signal | XIAO nRF52840 pin |
|---|---|
| NSS (chip select) | D4 |
| DIO1 | D1 |
| NRST | D2 |
| BUSY | D3 |
| RF switch | D5 |

### 5.3 Build & run the Android app

**Option A — Android Studio**
1. Open the `app/` folder as a project in Android Studio.
2. Let Gradle sync (dependencies are pulled automatically: AndroidX, Material, Eclipse Paho MQTT, splash‑screen library).
3. Connect your target device (Raspberry Pi 5 running Android, or an Android phone/tablet) with USB debugging / ADB access enabled, or start an emulator (note: BLE requires a physical device).
4. Click **Run** ▶.

**Option B — command line**

```bash
cd app
./gradlew assembleDebug     # builds app/app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # installs on a connected/adb-reachable device
```

On first launch, grant the requested runtime permissions:
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+) or `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (older Android), required to scan and connect via BLE.
- Internet access (`INTERNET` / `ACCESS_NETWORK_STATE`) is a normal manifest permission used for MQTT.

## 6. Usage guide

1. **Power up** the gateway receiver and at least one sensor node.
2. Open the app and tap **SCAN & CONNECT**. The status label cycles through *Scanning → Connecting → Negotiating MTU → Connected (MTU 247)*.
3. As telemetry arrives, the **Cistern** and **Tank** cards update independently and in real time.
4. Tap the **gear icon** next to "Water Level" on each card to enter that reservoir's total depth (sensor‑to‑bottom distance, in meters). The card immediately switches from `‑‑ m (set depth)` to a computed height + fill percentage.
5. Tap **PLATFORM SETTINGS** to configure MQTT publishing: pick ThingsBoard and/or Konker, fill in the broker/credentials/topics for whichever reservoirs you want reported, enable the platform(s), and save.
6. Tap either card to open its **10‑minute history chart**; use the spinner to switch between metrics available for that device (Water Level, Battery and RSSI are common to both; Pump Status is Cistern‑only, Turbidity is Tank‑only).
7. Use **TX TEST** to send a `PING`/`CLEAR` command to the gateway board and confirm the round‑trip over BLE (the board's onboard LED toggles accordingly) — handy for verifying the link independently of LoRa/sensor data.

## 7. Known limitations & future improvements

**Current limitations**
- Local telemetry history is capped at a 10‑minute rolling window kept in local storage on a single device — there is no multi‑device sync or long‑term cloud persistence yet.
- Each MQTT publish opens a brand‑new connection (connect → publish → disconnect); fine at the current telemetry rate, but a persistent client would be more efficient at higher frequencies.
- No additional BLE security (e.g. authenticated pairing) is implemented — acceptable for prototype/testing use, but should be addressed before a permanent installation.
- The safety‑loop floats (cistern low level, tank high/low level) are not yet reported to the app or the cloud; only the resulting pump ON/OFF state is, via the cistern node.
- Water level and turbidity values transmitted by the sensor firmware in this reference build are **simulated** (`random()`); only the sensor node's battery reading is real. Wiring the actual VL53L1X / SEN0189 readings is a direct drop‑in replacement in `main.cpp`.

**Planned improvements**
- Push notifications for critical levels and communication failures.
- Stronger BLE security (authentication + encryption).
- Pre‑built cloud dashboards for both supported platforms, to ease onboarding for new users.
- Cloud‑side long‑term telemetry persistence, enabling consumption and water‑quality trend analysis over time.
- Direct instrumentation of the safety floats for full visibility of the mechanical safety layer.

## 8. References

- Seeed Studio — *XIAO nRF52840 & Wio‑SX1262 Kit* documentation: https://wiki.seeedstudio.com/xiao_nrf52840&_wio_SX1262_kit_for_meshtastic/
- Android Developers — *Bluetooth Low Energy overview*: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
- Eclipse Foundation — *Eclipse Paho MQTT Client*: https://eclipse.dev/paho/ · https://github.com/eclipse-paho/paho.mqtt.java
- ThingsBoard — *MQTT Device API*: https://thingsboard.io/docs/reference/mqtt-api/
- Konker Labs — *Platform guide*: https://konker.atlassian.net/wiki/spaces/DEV/pages/28180518 · https://github.com/KonkerLabs/konker-platform
- Nordic Semiconductor — *Nordic UART Service (NUS)*: https://docs.nordicsemi.com/bundle/ncs-3.0.2/page/nrf/libraries/bluetooth/services/nus.html
- Jan Gromeš — *RadioLib*: https://github.com/jgromes/RadioLib
- Adafruit — *Adafruit_nRF52_Arduino* (Bluefruit BSP): https://github.com/adafruit/Adafruit_nRF52_Arduino
- SparkFun — *VL53L1X Arduino Library*: https://github.com/sparkfun/SparkFun_VL53L1X_Arduino_Library
- DFRobot — *Gravity Analog Turbidity Sensor (SEN0189)*: https://wiki.dfrobot.com/Turbidity_sensor_SKU__SEN0189
- PlatformIO — Documentation: https://docs.platformio.org/

## 9. License

Distributed under the **MIT License** — see [`LICENSE`](./LICENSE).

---

## Versão Português

## 1. Visão geral

A água da chuva é abundante e gratuita, mas grande parte das residências ainda utiliza água potável tratada para usos que não exigem potabilidade (descarga sanitária, rega de jardim, lavagem de calçadas), que podem representar até 50% do consumo doméstico. Este projeto implementa um sistema IoT de baixo custo que automatiza a **captação, o monitoramento e o bombeamento de água pluvial** entre um reservatório inferior (**cisterna**) e um reservatório superior (**tanque/caixa d'água**), dando ao usuário visibilidade remota completa do sistema por meio de um aplicativo Android e de um dashboard na nuvem.

O sistema separa deliberadamente duas camadas independentes:

1. **Camada de funcionamento e segurança (física, sem rádio)** — boias tipo reed‑switch ligadas diretamente ao circuito de alimentação da bomba garantem que ela nunca opere a seco (a boia de nível baixo da cisterna corta a alimentação) e que o tanque nunca transborde (a boia de nível alto do tanque corta a alimentação; a boia de nível baixo do tanque volta a acionar a bomba). Essa camada funciona mesmo que o gateway, o app ou a conexão com a internet estejam fora do ar.
2. **Camada de telemetria (LoRa + BLE + MQTT)** — dois nós sensores de baixo consumo reportam nível de água, status da bomba, turbidez e bateria a um gateway via LoRa. O gateway repassa esses dados via BLE a um aplicativo Android, que converte as leituras brutas em valores compreensíveis (altura de coluna d'água, percentual de enchimento), exibe cartões em tempo real e gráficos de histórico, e republica tudo em uma plataforma IoT na nuvem via MQTT.

```
Chuva → Telhado/Calhas → Cisterna ──(bomba)──> Tanque ──> Uso não potável residencial
                            │                     │
                     [Nó Sensor 1]           [Nó Sensor 2]
                     XIAO nRF52840           XIAO nRF52840
                     + SX1262 (LoRa)         + SX1262 (LoRa)
                     TOF + status bomba      TOF + turbidez
                            │                     │
                            └────────LoRa─────────┘
                                      │
                            [Receptor / Gateway]
                            XIAO nRF52840 + SX1262
                            (LoRa somente RX, periférico BLE / NUS)
                                      │
                                     BLE
                                      │
                          [App Android — Raspberry Pi 5]
                          Android AOSP 16 · Kotlin (MVVM)
                                      │
                                    MQTT
                                      │
                       [ThingsBoard]         [Konker]
                       (produção)             (opcional / legado)
```

> **Observação:** o estado das boias de segurança (nível baixo da cisterna, nível alto/baixo do tanque) **não** é atualmente reportado ao app nem à nuvem — apenas o resultado indireto (bomba ligada/desligada) é, via o nó LoRa da cisterna. A instrumentação direta dessas boias é indicada como melhoria futura.

## 2. Estrutura do repositório

```
.
├── app/                          # Aplicativo Android (Kotlin, Gradle)
│   └── app/src/main/java/com/android/lorablue/
│       ├── ble/                  # Camada BLE: ciclo de vida GATT, fila de operações, constantes
│       ├── data/                 # Parsing de JSON, modelos de telemetria, cálculo de nível
│       ├── chart/                # Tela de histórico de 10 minutos (gráfico Canvas próprio)
│       └── mqtt/                 # Publicador MQTT + UI de configuração ThingsBoard/Konker
├── firmware/                     # Firmware PlatformIO (framework Arduino, C++)
│   ├── src/main.cpp              # Lógica compartilhada TX/RX, selecionada via build flags
│   └── platformio.ini            # Ambientes lorablue_tx / lorablue_rx
└── LICENSE                       # MIT
```

## 3. Arquitetura do sistema

### 3.1 Hardware

| Componente | Modelo | Função |
|---|---|---|
| MCU dos nós sensores (×2) + MCU do receptor gateway | Seeed Studio **XIAO nRF52840** + módulo **Wio‑SX1262** | Leitura de sensores + TX LoRa (nós) / RX LoRa + periférico BLE (gateway) |
| Host do gateway | **Raspberry Pi 5** (8 GB RAM, BCM2712 quad‑core Cortex‑A76 @ 2,4 GHz) | Executa Android AOSP 16, hospeda o app, atua como central BLE, publica via MQTT |
| Sensor de nível | **VL53L1X** (Time‑of‑Flight, I²C) | Mede o vão de ar entre o sensor (topo) e a superfície da água |
| Sensor de qualidade da água | **DFRobot SEN0189** (turbidez analógico) | Mede a turbidez (NTU) — apenas no nó do tanque |
| Sensores de segurança | Boias reed‑switch horizontais | Nível baixo da cisterna / nível alto do tanque / nível baixo do tanque — laço de segurança mecânico, independente do sistema de rádio |
| Atuador | Bomba submersa + relé | Eleva a água da cisterna para o tanque |
| Alimentação (nós sensores) | Bateria Li‑Ion 3,7 V, 5200 mAh | Alimenta cada nó sensor operado a bateria |
| Alimentação (gateway) | Fonte de rede 5 V / 3 A | Alimenta o Raspberry Pi 5 + receptor LoRa |

### 3.2 Enlace de rádio — LoRa (comunicação de campo)

- **Chip:** Semtech SX1262, via a biblioteca [RadioLib](https://github.com/jgromes/RadioLib) (`^6.6.0`).
- **Configuração:** 915 MHz, banda de 125 kHz, spreading factor 7, taxa de codificação 4/5, syncword `0x12`, potência de saída 22 dBm.
- **Topologia:** ponto a ponto (P2P) simples — sem necessidade de gateway/servidor de rede LoRaWAN, adequado a um número pequeno e fixo de nós sensores.
- **Por que LoRa:** longo alcance e consumo muito baixo em comparação a Wi‑Fi/BLE, atravessa paredes/lajes entre os reservatórios e o gateway, e opera em faixa ISM livre de licenciamento.

### 3.3 Enlace BLE — comunicação interna do gateway

- **Perfil:** Nordic UART Service (NUS), implementado com `bluefruit.h` da Adafruit (lado firmware) e as APIs BLE nativas do Android (lado app).
- **Papéis:** o módulo receptor LoRa atua como **periférico BLE** (anuncia como `LoRaBlue_Gateway`); o app Android atua como **central BLE** (escaneia, conecta e assina notificações).
- **UUIDs:** um UUID de Serviço (usado para filtrar o escaneamento — mais confiável que filtrar por nome, já que o nome do dispositivo frequentemente não resolve durante o scan), uma característica RX (comandos app → placa) e uma característica TX (notificações de telemetria placa → app), além do descritor CCCD padrão para habilitar notificações.
- **Ciclo de vida da conexão:** Escaneando → Conectando → Negociando MTU (247 bytes, o máximo permitido pela especificação BLE 4.2+) → Descoberta de Serviços → Conectado. Todas as escritas GATT (descritor/característica) são serializadas por uma fila de operações (`ConcurrentLinkedQueue`), já que a pilha BLE do Android tolera apenas uma operação GATT pendente por vez — disparar escritas concorrentemente é uma causa comum de falhas silenciosas. Ao desconectar, o cache GATT é forçadamente limpo via reflexão (`gatt.refresh()`), garantindo que uma reconexão sempre redescubra os serviços atualizados.
- **Enquadramento de mensagens:** o firmware sempre termina um payload JSON com `\n`. Como o BLE pode fragmentar uma mensagem em várias notificações independentemente do MTU negociado, o app remonta os fragmentos em um buffer e só interpreta a mensagem quando encontra um `\n` (com um limite de segurança que limpa o buffer caso nenhum delimitador chegue, evitando vazamento de memória em caso de regressão no firmware).
- **Multiplexação:** os dados dos dois nós sensores trafegam pelo *mesmo* enlace físico receptor LoRa → BLE. Cada mensagem JSON carrega um campo `"id"` (`1` = Cisterna, `2` = Tanque) usado para rotear a leitura ao card correto na interface — é assim que uma única conexão BLE compartilhada atende com segurança a dois dispositivos logicamente distintos.

**JSON Placa → App (exemplo):**
```json
{"water":1.23,"turbidity":45.6,"pump":1,"batt":88.0,"rssi":-67.5}
```

**Comandos App → Placa:** `PING` (acende o LED vermelho da placa, responde `{"debug":"PONG"}`) e `CLEAR` (apaga o LED, responde `{"debug":"CLRD"}`) — usados a partir do botão **TX TEST** do app para validar o enlace de ponta a ponta.

### 3.4 MQTT — comunicação com a nuvem

- **Biblioteca:** Eclipse Paho MQTT Client (`org.eclipse.paho.client.mqttv3`).
- **Padrão:** conectar → publicar uma vez → desconectar, a cada leitura — aceitável na frequência atual de telemetria (uma leitura a cada poucos segundos, no máximo); considere um cliente persistente caso a frequência aumente significativamente.
- Ambas as plataformas podem ser **habilitadas simultaneamente**; toda leitura recebida é publicada em todas as plataformas atualmente habilitadas.

| Plataforma | Modelo de identidade | Tópico | Status neste projeto |
|---|---|---|---|
| **ThingsBoard** | Token de acesso por dispositivo, usado como usuário MQTT | Fixo: `v1/devices/me/telemetry` | **Produção** — adotado após a Konker apresentar instabilidade de conectividade nos testes |
| **Konker** | Usuário/senha compartilhados | Configurável por dispositivo | Mantido como opção configurável no código, não mais utilizado em operação |

O payload publicado sempre carrega a leitura de água **já convertida** — `water_dpt` (altura da coluna d'água, em metros) e `water_pct` (percentual de enchimento) — calculados a partir da distância bruta do sensor TOF e da profundidade total do reservatório configurada pelo usuário, além de `batt_lvl`, `rssi_lvl`, e `water_pump` (Cisterna) ou `turbidity` (Tanque). Ambos `water_dpt`/`water_pct` são enviados como `null` em JSON até que a profundidade do reservatório seja configurada no app.

### 3.5 Aplicativo Android

- **Linguagem / arquitetura:** Kotlin, MVVM (`AndroidViewModel` + `LiveData`), garantindo que o estado sobreviva a mudanças de configuração (ex.: rotação de tela).
- **Host alvo:** projetado para rodar em um Raspberry Pi 5 com Android AOSP 16 atuando como o "cérebro" do gateway, mas funciona em qualquer dispositivo Android (celular/tablet) com suporte a BLE, `minSdk 24`.
- **Telas principais:**
  - **MainActivity** — dois cartões em tempo real (Cisterna / Tanque) com nível de água (m e %), status da bomba ou turbidez, bateria, RSSI; botões `SCAN & CONNECT`, `TX TEST` e `PLATFORM SETTINGS`.
  - **Ícone de engrenagem por card** → `TankDepthConfigDialog`, para informar a profundidade total do reservatório (distância sensor‑ao‑fundo), necessária para converter a distância bruta em altura de coluna d'água/percentual utilizável (`WaterLevelCalculator`).
  - **ChartActivity** — histórico móvel de 10 minutos por dispositivo, seletor de métrica (Nível de Água / Bateria / RSSI / Turbidez ou Status da Bomba, conforme o dispositivo), renderizado com um gráfico de linha próprio baseado em `Canvas` (sem biblioteca externa de gráficos).
  - **MqttSettingsActivity** — seletor de plataforma (ThingsBoard/Konker) com formulários independentes e persistidos para cada uma, de modo que alternar de plataforma nunca descarta as credenciais previamente cadastradas da outra.
- **Persistência local:** `SharedPreferences` + JSON (o Room foi deliberadamente evitado devido a um conflito de classpath Kotlin/KSP vs. AGP na configuração Gradle deste projeto), com uma janela de retenção de 1 hora alimentando o gráfico de 10 minutos.

## 4. Requisitos

### 4.1 Firmware

- [PlatformIO Core](https://platformio.org/install) (CLI) **ou** [VS Code](https://code.visualstudio.com/) + a extensão *PlatformIO IDE*.
- Git.
- Um cabo USB e os drivers para o Seeed XIAO nRF52840 (id da board: `seeed-xiao-afruitnrf52-nrf52840`).
- Pelo menos 3 placas para uma montagem completa: 2 nós sensores (cisterna + tanque) + 1 receptor gateway — ou comece com um par TX/RX para um teste de bancada mínimo.

### 4.2 Aplicativo Android

- [Android Studio](https://developer.android.com/studio) (versão estável recente) **ou** o Gradle wrapper incluído em `app/` (não é necessário instalar o Gradle separadamente).
- JDK 21 (já incluso em versões recentes do Android Studio, ou instalável separadamente).
- Android SDK: `compileSdk 36` (minor API level 1), `targetSdk 36`, `minSdk 24`.
- Um dispositivo para rodar o app: Raspberry Pi 5 com Android AOSP 16 (como no protótipo de referência) **ou** qualquer celular/tablet Android com suporte a Bluetooth Low Energy.
- Acesso à internet no dispositivo em execução, para a publicação MQTT.

## 5. Guia de instalação e build

### 5.1 Clonar o repositório

```bash
git clone https://github.com/andresima0/LoRaBlue.git
cd LoRaBlue
```

### 5.2 Compilar e gravar o firmware

```bash
cd firmware

# Compila o firmware do nó sensor (TX)
pio run -e lorablue_tx

# Compila o firmware do receptor gateway (RX)
pio run -e lorablue_rx

# Grava na placa conectada (repita por papel, uma placa por vez)
pio run -e lorablue_tx -t upload
pio run -e lorablue_rx -t upload

# Abre o monitor serial para ver o output de debug (115200 baud)
pio device monitor
```

Os dois ambientes compartilham o mesmo `src/main.cpp`; o papel ativo é selecionado somente pela build flag definida em `platformio.ini` (`-D LORABLUE_TX` ou `-D LORABLUE_RX`). Grave o firmware TX em ambos os nós sensores (cisterna e tanque) e o firmware RX na placa do receptor gateway.

**Ligação do rádio (módulo SX1262, conforme `main.cpp`):**

| Sinal | Pino no XIAO nRF52840 |
|---|---|
| NSS (chip select) | D4 |
| DIO1 | D1 |
| NRST | D2 |
| BUSY | D3 |
| RF switch | D5 |

### 5.3 Compilar e executar o app Android

**Opção A — Android Studio**
1. Abra a pasta `app/` como um projeto no Android Studio.
2. Deixe o Gradle sincronizar (as dependências são baixadas automaticamente: AndroidX, Material, Eclipse Paho MQTT, biblioteca de splash‑screen).
3. Conecte o dispositivo alvo (Raspberry Pi 5 rodando Android, ou um celular/tablet Android) com depuração USB / acesso ADB habilitado, ou inicie um emulador (atenção: BLE requer um dispositivo físico).
4. Clique em **Run** ▶.

**Opção B — linha de comando**

```bash
cd app
./gradlew assembleDebug     # gera app/app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # instala em um dispositivo conectado/acessível via adb
```

Na primeira execução, conceda as permissões solicitadas em tempo de execução:
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+) ou `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (Android mais antigo), necessárias para escanear e conectar via BLE.
- Acesso à internet (`INTERNET` / `ACCESS_NETWORK_STATE`) é uma permissão normal de manifesto, usada para o MQTT.

## 6. Guia de uso

1. **Ligue** o receptor gateway e ao menos um nó sensor.
2. Abra o app e toque em **SCAN & CONNECT**. O rótulo de status percorre *Escaneando → Conectando → Negociando MTU → Conectado (MTU 247)*.
3. Conforme a telemetria chega, os cartões **Cisterna** e **Tanque** são atualizados de forma independente e em tempo real.
4. Toque no **ícone de engrenagem** ao lado de "Water Level" em cada card para informar a profundidade total daquele reservatório (distância sensor‑ao‑fundo, em metros). O card muda imediatamente de `‑‑ m (set depth)` para a altura calculada + percentual de enchimento.
5. Toque em **PLATFORM SETTINGS** para configurar a publicação MQTT: escolha ThingsBoard e/ou Konker, preencha o broker/credenciais/tópicos para os reservatórios desejados, habilite a(s) plataforma(s) e salve.
6. Toque em qualquer card para abrir seu **gráfico de histórico de 10 minutos**; use o seletor para alternar entre as métricas disponíveis para aquele dispositivo (Nível de Água, Bateria e RSSI são comuns a ambos; Status da Bomba é exclusivo da Cisterna, Turbidez é exclusiva do Tanque).
7. Use **TX TEST** para enviar um comando `PING`/`CLEAR` à placa gateway e confirmar o percurso completo via BLE (o LED da placa acende/apaga de acordo) — útil para verificar o enlace independentemente dos dados de LoRa/sensores.

## 7. Limitações conhecidas e melhorias futuras

**Limitações atuais**
- O histórico local de telemetria fica restrito a uma janela móvel de 10 minutos mantida no armazenamento local de um único dispositivo — ainda não há sincronização entre aparelhos nem persistência de longo prazo na nuvem.
- Cada publicação MQTT abre uma nova conexão (conectar → publicar → desconectar); adequado à frequência atual de telemetria, mas um cliente persistente seria mais eficiente em frequências maiores.
- Não há segurança adicional implementada na conexão BLE (ex.: pareamento autenticado) — aceitável para uso em protótipo/testes, mas deve ser endereçado antes de uma instalação permanente.
- As boias do laço de segurança (nível baixo da cisterna, nível alto/baixo do tanque) ainda não são reportadas ao app nem à nuvem; apenas o estado resultante da bomba (ligada/desligada) é, via o nó da cisterna.
- Os valores de nível de água e turbidez transmitidos pelo firmware sensor nesta versão de referência são **simulados** (`random()`); apenas a leitura de bateria do nó sensor é real. Conectar as leituras reais do VL53L1X / SEN0189 é uma substituição direta em `main.cpp`.

**Melhorias planejadas**
- Notificações push para níveis críticos e falhas de comunicação.
- Reforço da segurança BLE (autenticação + criptografia).
- Dashboards na nuvem pré‑configurados para ambas as plataformas suportadas, facilitando a adoção por novos usuários.
- Persistência de longo prazo da telemetria na nuvem, permitindo análises de tendência de consumo e qualidade da água ao longo do tempo.
- Instrumentação direta das boias de segurança, para visibilidade completa da camada mecânica de segurança.

## 8. Referências

- Seeed Studio — documentação do *XIAO nRF52840 & Wio‑SX1262 Kit*: https://wiki.seeedstudio.com/xiao_nrf52840&_wio_SX1262_kit_for_meshtastic/
- Android Developers — *Bluetooth Low Energy overview*: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
- Eclipse Foundation — *Eclipse Paho MQTT Client*: https://eclipse.dev/paho/ · https://github.com/eclipse-paho/paho.mqtt.java
- ThingsBoard — *MQTT Device API*: https://thingsboard.io/docs/reference/mqtt-api/
- Konker Labs — guia da plataforma: https://konker.atlassian.net/wiki/spaces/DEV/pages/28180518 · https://github.com/KonkerLabs/konker-platform
- Nordic Semiconductor — *Nordic UART Service (NUS)*: https://docs.nordicsemi.com/bundle/ncs-3.0.2/page/nrf/libraries/bluetooth/services/nus.html
- Jan Gromeš — *RadioLib*: https://github.com/jgromes/RadioLib
- Adafruit — *Adafruit_nRF52_Arduino* (BSP Bluefruit): https://github.com/adafruit/Adafruit_nRF52_Arduino
- SparkFun — *VL53L1X Arduino Library*: https://github.com/sparkfun/SparkFun_VL53L1X_Arduino_Library
- DFRobot — *Gravity Analog Turbidity Sensor (SEN0189)*: https://wiki.dfrobot.com/Turbidity_sensor_SKU__SEN0189
- PlatformIO — Documentação: https://docs.platformio.org/

## 9. Licença

Distribuído sob a **Licença MIT** — veja [`LICENSE`](./LICENSE).
