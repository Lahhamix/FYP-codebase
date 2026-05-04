#ifndef PRESSURE_READER_H
#define PRESSURE_READER_H

#include <Arduino.h>

// Active matrix dimensions after removing unused PHBLE mux channels.
#define NUM_ROWS 43
#define NUM_COLS 14
#define NUM_VALUES (NUM_ROWS * NUM_COLS)

// PHBLE pin configuration:
// Rows are driven by three digital muxes; columns are read through one analog mux.
#define ROW_DRV_S0 2
#define ROW_DRV_S1 3
#define ROW_DRV_S2 4
#define ROW_DRV_S3 5
#define ROW_DRV_SIG1 10
#define ROW_DRV_SIG2 11
#define ROW_DRV_SIG3 12
#define COL_READ_S0 6
#define COL_READ_S1 7
#define COL_READ_S2 8
#define COL_READ_S3 9
#define COL_READ_SIG A0

// Timing
#define ROW_SETTLE_US 20
#define COL_SETTLE_US 20

// BLE packet configuration
#define BLE_PACKET_SIZE 20
#define HEADER_SIZE 8
#define PAYLOAD_SIZE 12
#define SAMPLES_PER_PACKET 8
#define MAGIC_0 0xA5
#define MAGIC_1 0x5A

struct PressureFrame {
  uint16_t data[NUM_VALUES];  // Row-major order: row0, row1, ..., row42
  bool available;
};

struct PressureRow {
  uint16_t rowIndex;
  uint16_t data[NUM_COLS];
  bool frameStart;
  bool frameEnd;
  bool available;
};

// Initialize pressure matrix hardware
bool pressure_init();

// -----------------------------------------------------------------------------
// Non-blocking scan API (recommended)
// -----------------------------------------------------------------------------
/** Start a new scan from row 0. Safe to call at any time. */
void pressure_scan_begin();

/**
 * Scan up to maxRows rows this call. Returns true if a full frame completed during this step.
 * Calls yieldFn periodically while scanning so BLE/IMU work does not stall.
 */
bool pressure_scan_step(int maxRows, void (*yieldFn)(void));

/** Scan exactly the next row and return it immediately for rolling BLE streaming. */
bool pressure_scan_next_row(PressureRow* out, void (*yieldFn)(void));

/** If a completed frame is ready, copy it into out and clear the ready flag. */
bool pressure_take_frame(PressureFrame* out);

// Pack 12-bit samples into BLE packet format
void packSamples12(const uint16_t* samples, int count, uint8_t* out12);

// Get number of packets needed for full frame
int getPacketCount();

#endif
