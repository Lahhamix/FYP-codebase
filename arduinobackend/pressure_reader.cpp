#include "pressure_reader.h"
#include "serial_log.h"

static uint16_t frameBuffer[NUM_ROWS][NUM_COLS];
static uint16_t s_scanCol = 0;
static volatile bool s_frameReady = false;
static PressureFrame s_readyFrame;

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

void pressure_scan_begin() {
  s_scanCol = 0;
  s_frameReady = false;
}

static void finalize_ready_frame() {
  // Flatten to row-major order into the ready frame.
  int idx = 0;
  for (int r = 0; r < NUM_ROWS; r++) {
    for (int c = 0; c < NUM_COLS; c++) {
      s_readyFrame.data[idx++] = frameBuffer[r][c] & 0x0FFF;
    }
  }
  s_readyFrame.available = true;
  s_frameReady = true;
}

bool pressure_scan_step(int maxCols, void (*yieldFn)(void)) {
  if (maxCols <= 0) maxCols = 1;
  if (s_frameReady) {
    return true;
  }

  int did = 0;
  while (s_scanCol < NUM_COLS && did < maxCols) {
    setColumn((int)s_scanCol);
    for (int r = 0; r < NUM_ROWS; r++) {
      frameBuffer[r][s_scanCol] = readRow12(r);
    }
    s_scanCol++;
    did++;
    if (yieldFn != nullptr && PRESSURE_IMU_YIELD_EVERY_N_COLS > 0 &&
        ((int)s_scanCol % PRESSURE_IMU_YIELD_EVERY_N_COLS) == 0) {
      yieldFn();
    }
  }

  if (s_scanCol >= NUM_COLS) {
    disableAllColumns();
    finalize_ready_frame();
    // Prepare for next scan immediately; caller can call pressure_scan_begin() if they want explicit control.
    s_scanCol = 0;
    return true;
  }

  return false;
}

bool pressure_take_frame(PressureFrame* out) {
  if (out == nullptr || !s_frameReady) {
    return false;
  }
  *out = s_readyFrame;
  s_frameReady = false;
  return true;
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
