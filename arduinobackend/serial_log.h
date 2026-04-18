#ifndef SOLEMATE_SERIAL_LOG_H
#define SOLEMATE_SERIAL_LOG_H

#include <Arduino.h>

// -----------------------------------------------------------------------------
// Centralized Serial logging controls
// Edit only this file to enable/disable logs by subsystem.
// -----------------------------------------------------------------------------

// Master switch
#define LOG_ENABLED 1

// Subsystem switches
#define LOG_SYSTEM_ENABLED   1   // boot, BLE connect/disconnect, init messages
#define LOG_ENCRYPT_ENABLED  1   // encryption/key exchange errors
#define LOG_PPG_ENABLED      1   // PPG validity + HR/SpO2 + ratio/correlation
#define LOG_IMU_ENABLED      1   // IMU motion detection logs
#define LOG_FLEX_ENABLED     1   // flex calibration + live deviation prints (keep same format)
#define LOG_PRESSURE_ENABLED 1   // pressure min/max + stream start (set 1 to enable Serial lines)

// IMU log cadence (ms). Do not use tiny values (e.g. 5) — Serial will block the whole firmware.
#define IMU_LOG_PERIOD_MS 25u

// Pressure BLE: delay after each packet. 0 = fastest (~96 fewer ms per frame vs delay 1). Raise to 1–2 if BLE drops.
#define PRESSURE_PACKET_DELAY_MS 0u

// During matrix scan, call imu_pump_steps() every N columns (48 cols → 12 calls per frame).
#define PRESSURE_IMU_YIELD_EVERY_N_COLS 4

// Min/max frame log interval (ms). Full matrix stats once per period when LOG_PRESSURE_ENABLED.
#define PRESSURE_LOG_PERIOD_MS 500u

// One combined line at end of each loop iteration (flex summary + pressure min/max). Set 0 to disable.
#define LOG_SENSOR_SNAPSHOT_ENABLED 0

// PPG: IR mean (18-bit DC) must exceed this to count as "finger" (software mode only; lower if you never pass the gate)
#define PPG_FINGER_IR_THRESHOLD 5000u

// PPG sample timing:
// - 0 = software (PPG_RDY / FIFO poll) — recommended on Nano 33 BLE (D13 is shared with LED; INT pin often unreliable).
// - 1 = try INT pin first, then software fallback; still uses PPG_INT_WAIT_MS timeout so the loop never hangs forever.
#define PPG_USE_INTERRUPT_PIN 1
#define PPG_INTERRUPT_PIN 13

// RF window length: seconds × 25 Hz = samples per HR/SpO2 update (smaller = faster prints, less stable). Was 4s/100 samples.
#define PPG_RF_ST_SECONDS 4

// Per main loop, drain up to this many FIFO samples (MAX30102 FIFO depth 32) so slow loops catch up instead of 1 sample/iteration.
// Reference sketch reads exactly 1 sample per step (no FIFO "catch-up" bursts).
// Keeping this at 1 makes the sampling more uniform and aligns periodicity detection.
#define PPG_MAX_SAMPLES_PER_LOOP 100

// When interrupt mode + HR & SpO2 valid: print this many "red,ir" CSV lines (set to 100 to match MAX30102_by_RF.ino full buffer dump)
// Match the reference sketch's full window dump when valid.
#define PPG_RED_IR_CSV_LINES 0

// Main loop delay after one full pass (lower = faster PPG window fill; higher = lower CPU / BLE load)
#define MAIN_LOOP_DELAY_MS 2

#if LOG_ENABLED
  #define LOG_IF(catEnabled, stmt) do { if (catEnabled) { stmt; } } while (0)
#else
  #define LOG_IF(catEnabled, stmt) do { (void)sizeof(catEnabled); } while (0)
#endif

#define LOG_SYSTEM(stmt)   LOG_IF(LOG_SYSTEM_ENABLED, stmt)
#define LOG_ENCRYPT(stmt)  LOG_IF(LOG_ENCRYPT_ENABLED, stmt)
#define LOG_PPG(stmt)      LOG_IF(LOG_PPG_ENABLED, stmt)
#define LOG_IMU(stmt)      LOG_IF(LOG_IMU_ENABLED, stmt)
#define LOG_FLEX(stmt)     LOG_IF(LOG_FLEX_ENABLED, stmt)
#define LOG_PRESSURE(stmt) LOG_IF(LOG_PRESSURE_ENABLED, stmt)
#define LOG_SNAPSHOT(stmt) LOG_IF(LOG_SENSOR_SNAPSHOT_ENABLED, stmt)

#endif

