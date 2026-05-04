/*
  Active Velostat matrix scanning from a physical 48 x 16 mux layout.
  Deleted channels are removed from the transmitted frame.

  Active output size:
    rows = 43
    cols = 14
    values = 602

  MODES:
    - USE_BLE = 1  --> send continuous BLE packets with packed 12-bit samples
    - USE_BLE = 0  --> send serial CSV text frames:
                       F,<602 values...>\n

  CONTINUOUS BLE PACKET FORMAT (20 bytes total):
    byte 0   : 0xA5
    byte 1   : 0x5A
    byte 2-3 : frame_id (little-endian uint16)
    byte 4-5 : start_index (little-endian uint16, 0..601)
    byte 6   : sample_count (1..8)
    byte 7   : flags
               bit0 = frame start
               bit1 = frame end
    byte 8.. : packed 12-bit payload

  Payload packing:
    - 8 samples max per packet
    - 8 packed 12-bit samples = 12 bytes
    - total = 8-byte header + 12-byte payload = 20 bytes

  Packing rule (every 2 samples -> 3 bytes):
      byte0 = sample0[7:0]
      byte1 = sample0[11:8] | (sample1[3:0] << 4)
      byte2 = sample1[11:4]

  Pins:
  - Rows driven by THREE digital 16-ch muxes (shared select lines)
      S0..S3 -> D2,D3,D4,D5
      SIG1   -> D10   (lowest mux: old PH_BLE_12bit direction, physical channels 15..0)
      SIG2   -> D11   (middle mux: old PH_BLE_12bit direction, physical channels 0..15)
      SIG3   -> D12   (highest mux: old PH_BLE_12bit direction, physical channels 15..0)

      Deleted row channels:
        SIG1/D10: C13, C14, C15
        SIG3/D12: C0, C1

  - Columns read through ONE analog 16-ch mux
      S0..S3 -> D6,D7,D8,D9
      SIG    -> A0
      old PH_BLE_12bit direction, physical channels 15..0

      Deleted column channels:
        analog mux: C0, C1
*/

#include <ArduinoBLE.h>
#include <stdint.h>

// =========================================================
// MODE SELECT
// =========================================================
#define USE_BLE 1 // 1 = BLE continuous stream, 0 = Serial CSV

// =========================================================
// PIN CONFIG
// =========================================================
const int ROW_DRV_S0 = 2;
const int ROW_DRV_S1 = 3;
const int ROW_DRV_S2 = 4;
const int ROW_DRV_S3 = 5;

const int ROW_DRV_SIG1 = 10;
const int ROW_DRV_SIG2 = 11;
const int ROW_DRV_SIG3 = 12;

const int COL_READ_S0 = 6;
const int COL_READ_S1 = 7;
const int COL_READ_S2 = 8;
const int COL_READ_S3 = 9;

const int COL_READ_SIG = A0;

// =========================================================
// ACTIVE MATRIX SIZE AFTER DELETED CHANNELS ARE REMOVED
// =========================================================
const int NUM_ROWS = 43;
const int NUM_COLS = 14;
const int NUM_VALUES = NUM_ROWS * NUM_COLS;

// =========================================================
// TIMING
// =========================================================
const int ROW_SETTLE_US = 20;
const int COL_SETTLE_US = 20;

// Now that packets are independent, frame delay can be small
const unsigned long FRAME_DELAY_MS = 10;

// Keep this low; increase only if notifications become unstable
const int PACKET_DELAY_MS = 2;

// =========================================================
// BLE CONFIG
// =========================================================
const uint8_t MAGIC_0 = 0xA5;
const uint8_t MAGIC_1 = 0x5A;

const int BLE_PACKET_SIZE = 20;
const int HEADER_SIZE = 8;
const int PAYLOAD_SIZE = 12;              // 8 packed 12-bit samples
const int SAMPLES_PER_PACKET = 8;         // 8 * 12 bits = 96 bits = 12 bytes

const uint8_t FLAG_FRAME_START = 0x01;
const uint8_t FLAG_FRAME_END   = 0x02;

BLEService pressureService("12345678-1234-5678-1234-56789abcdef0");
BLECharacteristic pressureChar(
  "12345678-1234-5678-1234-56789abcdef1",
  BLERead | BLENotify,
  BLE_PACKET_SIZE
);

// =========================================================
// STORAGE
// =========================================================
static uint16_t frame12[NUM_ROWS][NUM_COLS];
static uint16_t linearFrame[NUM_VALUES];
static uint16_t frameCounter = 0;

// =========================================================
// HELPERS
// =========================================================
void setMux4(int s0, int s1, int s2, int s3, int channel) {
  digitalWrite(s0, (channel & 0x01) ? HIGH : LOW);
  digitalWrite(s1, (channel & 0x02) ? HIGH : LOW);
  digitalWrite(s2, (channel & 0x04) ? HIGH : LOW);
  digitalWrite(s3, (channel & 0x08) ? HIGH : LOW);
}

void disableAllRows() {
  digitalWrite(ROW_DRV_SIG1, LOW);
  digitalWrite(ROW_DRV_SIG2, LOW);
  digitalWrite(ROW_DRV_SIG3, LOW);
}

void setRow(int rowIndex) {
  disableAllRows();

  if (rowIndex < 13) {
    // SIG1/D10 old direction is 15..0. Channels 13..15 do not exist, so keep 12..0.
    int ch = 12 - rowIndex;
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG1, HIGH);
  } else if (rowIndex < 29) {
    // SIG2/D11 old direction is 0..15 and all channels exist.
    int ch = rowIndex - 13;
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG2, HIGH);
  } else {
    // SIG3/D12 old direction is 15..0. Channels 0..1 do not exist, so keep 15..2.
    int ch = 15 - (rowIndex - 29);
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG3, HIGH);
  }
}

uint16_t readColumn12(int colIndex) {
  // Analog old direction is 15..0. Channels 0..1 do not exist, so keep 15..2.
  int ch = 15 - colIndex;
  setMux4(COL_READ_S0, COL_READ_S1, COL_READ_S2, COL_READ_S3, ch);
  delayMicroseconds(COL_SETTLE_US);
  return (uint16_t)analogRead(COL_READ_SIG) & 0x0FFF;
}

// Scan matrix into frame12[r][c]
void scanMatrix12bit() {
  for (int r = 0; r < NUM_ROWS; r++) {
    setRow(r);
    for (int c = 0; c < NUM_COLS; c++) {
      frame12[r][c] = readColumn12(c);
    }
  }
  disableAllRows();
}

// Flatten frame12 into linearFrame in row-major order
void flattenFrameRowMajor() {
  int idx = 0;
  for (int r = 0; r < NUM_ROWS; r++) {
    for (int c = 0; c < NUM_COLS; c++) {
      linearFrame[idx++] = frame12[r][c] & 0x0FFF;
    }
  }
}

// Pack up to 8 12-bit samples into 12 bytes max.
// Any unused payload bytes are zero-filled.
void packSamples12(const uint16_t* samples, int count, uint8_t* out12) {
  for (int i = 0; i < PAYLOAD_SIZE; i++) {
    out12[i] = 0;
  }

  int outIndex = 0;
  int i = 0;

  while (i + 1 < count) {
    uint16_t a = samples[i] & 0x0FFF;
    uint16_t b = samples[i + 1] & 0x0FFF;

    out12[outIndex++] = (uint8_t)(a & 0xFF);
    out12[outIndex++] = (uint8_t)(((a >> 8) & 0x0F) | ((b & 0x0F) << 4));
    out12[outIndex++] = (uint8_t)((b >> 4) & 0xFF);

    i += 2;
  }

  // If odd count remains, pack one sample into 2 bytes-equivalent pattern
  if (i < count) {
    uint16_t a = samples[i] & 0x0FFF;
    out12[outIndex++] = (uint8_t)(a & 0xFF);
    out12[outIndex++] = (uint8_t)((a >> 8) & 0x0F);
  }
}

// =========================================================
// FRAME STATS (same print in BLE and serial, always)
// =========================================================
void printFrameRawMinMax() {
  uint16_t pMin = 0x0FFF, pMax = 0;
  for (int r = 0; r < NUM_ROWS; r++) {
    for (int c = 0; c < NUM_COLS; c++) {
      uint16_t v = frame12[r][c] & 0x0FFF;
      if (v < pMin) pMin = v;
      if (v > pMax) pMax = v;
    }
  }
  // Serial.print("[PRESSURE] frame raw min=");
  //Serial.print(pMin);
  //Serial.print(" max=");
  //Serial.println(pMax);
}

// =========================================================
// SERIAL CSV OUTPUT (optional; min/max is printed in loop)
// =========================================================
void sendFrameSerialCSV() {
  Serial.print("F");
  for (int r = 0; r < NUM_ROWS; r++) {
    for (int c = 0; c < NUM_COLS; c++) {
      Serial.print(",");
      Serial.print(frame12[r][c] & 0x0FFF);
    }
  }
  Serial.println();
}

// =========================================================
// BLE CONTINUOUS STREAM OUTPUT
// =========================================================
void sendFrameContinuousBLE() {
#if USE_BLE
  if (!BLE.connected()) {
    return;
  }

  flattenFrameRowMajor();

  uint8_t packet[BLE_PACKET_SIZE];
  uint8_t payload[PAYLOAD_SIZE];

  for (uint16_t startIndex = 0; startIndex < NUM_VALUES; startIndex += SAMPLES_PER_PACKET) {
    BLE.poll();

    int remaining = NUM_VALUES - startIndex;
    uint8_t sampleCount = (remaining >= SAMPLES_PER_PACKET) ? SAMPLES_PER_PACKET : remaining;

    packSamples12(&linearFrame[startIndex], sampleCount, payload);

    uint8_t flags = 0;
    if (startIndex == 0) {
      flags |= FLAG_FRAME_START;
    }
    if ((startIndex + sampleCount) >= NUM_VALUES) {
      flags |= FLAG_FRAME_END;
    }

    packet[0] = MAGIC_0;
    packet[1] = MAGIC_1;
    packet[2] = (uint8_t)(frameCounter & 0xFF);
    packet[3] = (uint8_t)((frameCounter >> 8) & 0xFF);
    packet[4] = (uint8_t)(startIndex & 0xFF);
    packet[5] = (uint8_t)((startIndex >> 8) & 0xFF);
    packet[6] = sampleCount;
    packet[7] = flags;

    for (int i = 0; i < PAYLOAD_SIZE; i++) {
      packet[HEADER_SIZE + i] = payload[i];
    }

    pressureChar.writeValue(packet, BLE_PACKET_SIZE);
    delay(PACKET_DELAY_MS);
  }

  frameCounter++;
#endif
}

// =========================================================
// SETUP
// =========================================================
void setup() {
  pinMode(ROW_DRV_S0, OUTPUT);
  pinMode(ROW_DRV_S1, OUTPUT);
  pinMode(ROW_DRV_S2, OUTPUT);
  pinMode(ROW_DRV_S3, OUTPUT);

  pinMode(ROW_DRV_SIG1, OUTPUT);
  pinMode(ROW_DRV_SIG2, OUTPUT);
  pinMode(ROW_DRV_SIG3, OUTPUT);

  pinMode(COL_READ_S0, OUTPUT);
  pinMode(COL_READ_S1, OUTPUT);
  pinMode(COL_READ_S2, OUTPUT);
  pinMode(COL_READ_S3, OUTPUT);

  disableAllRows();

  Serial.begin(115200);
  delay(500);

  analogReadResolution(12);

#if USE_BLE
  if (!BLE.begin()) {
    Serial.println("BLE init failed.");
    while (1) {
      delay(1000);
    }
  }

  // Desired connection interval; actual result depends on central too
  BLE.setConnectionInterval(8, 12);

  BLE.setLocalName("Velo2");
  BLE.setAdvertisedService(pressureService);
  pressureService.addCharacteristic(pressureChar);
  BLE.addService(pressureService);

  uint8_t initPacket[BLE_PACKET_SIZE] = {0};
  initPacket[0] = MAGIC_0;
  initPacket[1] = MAGIC_1;
  pressureChar.writeValue(initPacket, BLE_PACKET_SIZE);

  BLE.advertise();
  Serial.println("BLE advertising as 'Velo2' (continuous stream mode)");
#else
  /* Serial CSV mode: [PRESSURE] frame raw min/max and F,... lines are printed */
#endif
}

// =========================================================
// LOOP
// =========================================================
void loop() {
#if USE_BLE
  BLE.poll();
#endif

  scanMatrix12bit();

  // Same print in both modes, even when no BLE device connected
  printFrameRawMinMax();

#if USE_BLE
  sendFrameContinuousBLE();
  delay(FRAME_DELAY_MS);
#else
  sendFrameSerialCSV();
  delay(FRAME_DELAY_MS);
#endif
}
