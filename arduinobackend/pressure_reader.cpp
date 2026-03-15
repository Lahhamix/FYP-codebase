#include "pressure_reader.h"

static uint16_t frameBuffer[NUM_ROWS][NUM_COLS];

void setMux4(int s0, int s1, int s2, int s3, int channel) {
  digitalWrite(s0, (channel & 0x01) ? HIGH : LOW);
  digitalWrite(s1, (channel & 0x02) ? HIGH : LOW);
  digitalWrite(s2, (channel & 0x04) ? HIGH : LOW);
  digitalWrite(s3, (channel & 0x08) ? HIGH : LOW);
}

void disableAllColumns() {
  digitalWrite(COL_SIG1, LOW);
  digitalWrite(COL_SIG2, LOW);
  digitalWrite(COL_SIG3, LOW);
}

void setColumn(int colIndex) {
  disableAllColumns();

  if (colIndex < 16) {
    setMux4(COL_S0, COL_S1, COL_S2, COL_S3, colIndex);
    delayMicroseconds(COL_SETTLE_US);
    digitalWrite(COL_SIG1, HIGH);
  } else if (colIndex < 32) {
    int ch = colIndex - 16;
    setMux4(COL_S0, COL_S1, COL_S2, COL_S3, ch);
    delayMicroseconds(COL_SETTLE_US);
    digitalWrite(COL_SIG2, HIGH);
  } else {
    int ch = colIndex - 32;
    setMux4(COL_S0, COL_S1, COL_S2, COL_S3, ch);
    delayMicroseconds(COL_SETTLE_US);
    digitalWrite(COL_SIG3, HIGH);
  }
}

uint16_t readRow12(int rowIndex) {
  setMux4(ROW_S0, ROW_S1, ROW_S2, ROW_S3, rowIndex);
  delayMicroseconds(ROW_SETTLE_US);
  return (uint16_t)analogRead(ROW_SIG) & 0x0FFF;
}

bool pressure_init() {
  // Configure column pins
  pinMode(COL_S0, OUTPUT);
  pinMode(COL_S1, OUTPUT);
  pinMode(COL_S2, OUTPUT);
  pinMode(COL_S3, OUTPUT);
  pinMode(COL_SIG1, OUTPUT);
  pinMode(COL_SIG2, OUTPUT);
  pinMode(COL_SIG3, OUTPUT);

  // Configure row pins
  pinMode(ROW_S0, OUTPUT);
  pinMode(ROW_S1, OUTPUT);
  pinMode(ROW_S2, OUTPUT);
  pinMode(ROW_S3, OUTPUT);

  disableAllColumns();

  // Set ADC resolution to 12-bit
  #ifdef analogReadResolution
  analogReadResolution(12);
  #endif

  return true;
}

PressureFrame readPressure() {
  PressureFrame frame;
  frame.available = false;

  // Scan matrix
  for (int c = 0; c < NUM_COLS; c++) {
    setColumn(c);
    for (int r = 0; r < NUM_ROWS; r++) {
      frameBuffer[r][c] = readRow12(r);
    }
  }
  disableAllColumns();

  // Flatten to row-major order
  int idx = 0;
  for (int r = 0; r < NUM_ROWS; r++) {
    for (int c = 0; c < NUM_COLS; c++) {
      frame.data[idx++] = frameBuffer[r][c] & 0x0FFF;
    }
  }

  frame.available = true;
  return frame;
}

void packSamples12(const uint16_t* samples, int count, uint8_t* out12) {
  // Zero-fill payload
  for (int i = 0; i < PAYLOAD_SIZE; i++) {
    out12[i] = 0;
  }

  int outIndex = 0;
  int i = 0;

  // Pack pairs of samples
  while (i + 1 < count) {
    uint16_t a = samples[i] & 0x0FFF;
    uint16_t b = samples[i + 1] & 0x0FFF;

    out12[outIndex++] = (uint8_t)(a & 0xFF);
    out12[outIndex++] = (uint8_t)(((a >> 8) & 0x0F) | ((b & 0x0F) << 4));
    out12[outIndex++] = (uint8_t)((b >> 4) & 0xFF);

    i += 2;
  }

  // Handle odd count (last sample)
  if (i < count) {
    uint16_t a = samples[i] & 0x0FFF;
    out12[outIndex++] = (uint8_t)(a & 0xFF);
    out12[outIndex++] = (uint8_t)((a >> 8) & 0x0F);
  }
}

int getPacketCount() {
  return (NUM_VALUES + SAMPLES_PER_PACKET - 1) / SAMPLES_PER_PACKET;
}
