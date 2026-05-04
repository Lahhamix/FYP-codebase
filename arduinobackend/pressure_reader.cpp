#include "pressure_reader.h"
#include "serial_log.h"

static uint16_t frameBuffer[NUM_ROWS][NUM_COLS];
static uint16_t s_scanRow = 0;
static volatile bool s_frameReady = false;
static PressureFrame s_readyFrame;

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
    // SIG1/D10 keeps physical channels 12..0; channels 13..15 are deleted.
    int ch = 12 - rowIndex;
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG1, HIGH);
  } else if (rowIndex < 29) {
    // SIG2/D11 keeps physical channels 0..15.
    int ch = rowIndex - 13;
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG2, HIGH);
  } else {
    // SIG3/D12 keeps physical channels 15..2; channels 0..1 are deleted.
    int ch = 15 - (rowIndex - 29);
    setMux4(ROW_DRV_S0, ROW_DRV_S1, ROW_DRV_S2, ROW_DRV_S3, ch);
    delayMicroseconds(ROW_SETTLE_US);
    digitalWrite(ROW_DRV_SIG3, HIGH);
  }
}

uint16_t readColumn12(int colIndex) {
  // Analog column mux keeps physical channels 15..2; channels 0..1 are deleted.
  int ch = 15 - colIndex;
  setMux4(COL_READ_S0, COL_READ_S1, COL_READ_S2, COL_READ_S3, ch);
  delayMicroseconds(COL_SETTLE_US);
  return (uint16_t)analogRead(COL_READ_SIG) & 0x0FFF;
}

bool pressure_init() {
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

  // Set ADC resolution to 12-bit
  #ifdef analogReadResolution
  analogReadResolution(12);
  #endif

  return true;
}

void pressure_scan_begin() {
  s_scanRow = 0;
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

bool pressure_scan_step(int maxRows, void (*yieldFn)(void)) {
  if (maxRows <= 0) maxRows = 1;
  if (s_frameReady) {
    return true;
  }

  int did = 0;
  while (s_scanRow < NUM_ROWS && did < maxRows) {
    setRow((int)s_scanRow);
    for (int c = 0; c < NUM_COLS; c++) {
      frameBuffer[s_scanRow][c] = readColumn12(c);
    }
    s_scanRow++;
    did++;
    if (yieldFn != nullptr && PRESSURE_IMU_YIELD_EVERY_N_COLS > 0 &&
        ((int)s_scanRow % PRESSURE_IMU_YIELD_EVERY_N_COLS) == 0) {
      yieldFn();
    }
  }

  if (s_scanRow >= NUM_ROWS) {
    disableAllRows();
    finalize_ready_frame();
    // Prepare for next scan immediately; caller can call pressure_scan_begin() if they want explicit control.
    s_scanRow = 0;
    return true;
  }

  return false;
}

bool pressure_scan_next_row(PressureRow* out, void (*yieldFn)(void)) {
  if (out == nullptr) {
    return false;
  }

  out->available = false;
  const uint16_t rowIndex = s_scanRow;
  setRow((int)rowIndex);
  for (int c = 0; c < NUM_COLS; c++) {
    const uint16_t value = readColumn12(c);
    frameBuffer[rowIndex][c] = value;
    out->data[c] = value;
  }

  out->rowIndex = rowIndex;
  out->frameStart = (rowIndex == 0);
  out->frameEnd = (rowIndex == (NUM_ROWS - 1));
  out->available = true;

  s_scanRow++;
  if (yieldFn != nullptr) {
    yieldFn();
  }

  if (s_scanRow >= NUM_ROWS) {
    disableAllRows();
    finalize_ready_frame();
    s_scanRow = 0;
  }

  return true;
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
