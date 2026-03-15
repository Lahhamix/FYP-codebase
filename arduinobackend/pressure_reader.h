#ifndef PRESSURE_READER_H
#define PRESSURE_READER_H

#include <Arduino.h>

// Matrix dimensions
#define NUM_COLS 48 
#define NUM_ROWS 16
#define NUM_VALUES (NUM_ROWS * NUM_COLS)

// Pin configuration
#define COL_S0 2
#define COL_S1 3
#define COL_S2 4
#define COL_S3 5
#define COL_SIG1 10
#define COL_SIG2 11
#define COL_SIG3 12
#define ROW_S0 6
#define ROW_S1 7
#define ROW_S2 8
#define ROW_S3 9
#define ROW_SIG A2

// Timing
#define COL_SETTLE_US 20
#define ROW_SETTLE_US 20

// BLE packet configuration
#define BLE_PACKET_SIZE 20
#define HEADER_SIZE 8
#define PAYLOAD_SIZE 12
#define SAMPLES_PER_PACKET 8
#define MAGIC_0 0xA5
#define MAGIC_1 0x5A

struct PressureFrame {
  uint16_t data[NUM_VALUES];  // Row-major order: row0, row1, ..., row15
  bool available;
};

// Initialize pressure matrix hardware
bool pressure_init();

// Scan matrix and return frame data
PressureFrame readPressure();

// Pack 12-bit samples into BLE packet format
void packSamples12(const uint16_t* samples, int count, uint8_t* out12);

// Get number of packets needed for full frame
int getPacketCount();

#endif
