#ifndef SOLEMATE_SERIAL_LOG_H
#define SOLEMATE_SERIAL_LOG_H

#include <Arduino.h>

// -----------------------------------------------------------------------------
// Centralized Serial logging controls
// Edit only this file to enable/disable logs by subsystem.
// -----------------------------------------------------------------------------

// Master switch
#define LOG_ENABLED 1

// Serial baud rate for this firmware.
// Default stays high for waveform streaming; change to 115200/9600 if your Serial Monitor isn't set to 2,000,000.
#ifndef SERIAL_BAUD
#define SERIAL_BAUD 2000000UL
#endif

// -----------------------------------------------------------------------------
// Per-sensor pipeline (1 = init + read + encrypt + BLE notify each loop)
// Set 0 to remove that sensor entirely (no I2C/ADC work, no crypto, no TX) — use to profile loop time.
// BLE characteristics still exist; key-exchange may skip init writes for disabled sensors.
// -----------------------------------------------------------------------------
#define SENSOR_IMU_ENABLED       1  // steps + motion + readIMU + IMU logs
#define SENSOR_FLEX_ENABLED        1   // flex read + edema characteristic
#define SENSOR_PPG_ENABLED         1  // MAX30102 + HR/SpO2 + ppg_print_serial
#define SENSOR_PRESSURE_ENABLED    1  // PHBLE 43x14 matrix scan + pressure BLE packets

// Subsystem switches
#define LOG_SYSTEM_ENABLED   1   // boot, BLE connect/disconnect, init messages
#define LOG_ENCRYPT_ENABLED  1     // encryption/key exchange errors
#define LOG_PPG_ENABLED      1   // PPG validity + HR/SpO2 + ratio/correlation
#define LOG_IMU_ENABLED      1   // IMU motion detection logs
#define LOG_FLEX_ENABLED     1   // flex calibration + live deviation prints (keep same format)
#define LOG_PRESSURE_ENABLED 0   // pressure min/max + stream start (set 1 to enable Serial lines)

// Full connected-loop timing: work_us = time before final delay(); total_us includes MAIN_LOOP_DELAY_MS.
// Requires LOG_ENABLED. Serial I/O adds skew; set LOOP_TIMING_LOG_EVERY_N > 1 to sample every Nth iteration.
#define LOG_LOOP_TIMING_ENABLED 1
#define LOOP_TIMING_LOG_EVERY_N 1u

// IMU log cadence (ms). Do not use tiny values (e.g. 5) — Serial will block the whole firmware.
#define IMU_LOG_PERIOD_MS 0u

// Pressure BLE: delay after each packet. 0 = fastest (~96 fewer ms per frame vs delay 1). Raise to 1–2 if BLE drops.
#define PRESSURE_PACKET_DELAY_MS 0u

// During matrix scan, call imu_pump_steps() every N columns (48 cols → 12 calls per frame).
#define PRESSURE_IMU_YIELD_EVERY_N_COLS 1

// Min/max frame log interval (ms). Full matrix stats once per period when LOG_PRESSURE_ENABLED.
#define PRESSURE_LOG_PERIOD_MS 0u

// -----------------------------------------------------------------------------
// Pressure matrix scheduling (non-blocking)
// We scan a few columns per loop iteration so we never stall PPG waveform timing.
// -----------------------------------------------------------------------------
#ifndef PRESSURE_SCAN_COLS_PER_STEP
#define PRESSURE_SCAN_COLS_PER_STEP 3
#endif
// BLE TX batching: send only a few pressure packets per loop iteration.
#ifndef PRESSURE_TX_PACKETS_PER_STEP
#define PRESSURE_TX_PACKETS_PER_STEP 6
#endif

// One combined line at end of each loop iteration (flex summary + pressure min/max). Set 0 to disable.
#define LOG_SENSOR_SNAPSHOT_ENABLED 0

// PPG: IR mean (18-bit DC) must exceed this to count as "finger" (software mode only; lower if you never pass the gate)
#define PPG_FINGER_IR_THRESHOLD 5000u

// PPG sample timing:
// - 0 = software (PPG_RDY / FIFO poll) — recommended on Nano 33 BLE (D13 is shared with LED; INT pin often unreliable).
// - 1 = try INT pin first, then software fallback; still uses PPG_INT_WAIT_MS timeout so the loop never hangs forever.
#define PPG_USE_INTERRUPT_PIN 0
#define PPG_INTERRUPT_PIN 13

// Effective PPG rate (Hz). Must match firmware: REG_SPO2_CONFIG = 100 SPS (e.g. 0x27) and REG_FIFO_CONFIG = 0x0F (FIFO avg = 1).
// Old 0x4F used 4x FIFO averaging on 100 SPS -> ~25 effective Hz; 0x0F removes averaging so software FS matches chip.
#define PPG_FS_HZ 100

// RF window: BUFFER_SIZE = (PPG_FS_HZ * PPG_RF_ST_NUM) / PPG_RF_ST_DEN samples.
// Examples: 5 s -> NUM=5 DEN=1 (500 samples) | 4 s -> NUM=4 DEN=1 (400) | 1 s -> NUM=1 DEN=1 (100)
#define PPG_RF_ST_NUM 5
#define PPG_RF_ST_DEN 1

// Per main loop, drain up to this many FIFO samples (FIFO depth 32; use <=32 to avoid overrun at 100 Hz).
#define PPG_MAX_SAMPLES_PER_LOOP 32

// When HR & SpO2 valid: print this many IR-only lines (one sample per line). 0 = off. Cap is BUFFER_SIZE.
#define PPG_IR_CSV_LINES 0

// -----------------------------------------------------------------------------
// BLE publish gating (ms)
// These control how often we NOTIFY the phone. Acquisition still runs continuously.
// Default: 60s so BLE/crypto doesn't steal time from PPG/pressure.
// -----------------------------------------------------------------------------
#ifndef BLE_PUBLISH_STEPS_MS
#define BLE_PUBLISH_STEPS_MS 250u
#endif
#ifndef BLE_PUBLISH_MOTION_MS
#define BLE_PUBLISH_MOTION_MS 250u   // keep motion near-realtime by default
#endif
#ifndef BLE_PUBLISH_HR_MS
#define BLE_PUBLISH_HR_MS 0u   // normal behavior: publish whenever HR window is valid
#endif
#ifndef BLE_PUBLISH_SPO2_MS
#define BLE_PUBLISH_SPO2_MS 0u // normal behavior: publish whenever SpO2 window is valid
#endif
#ifndef BLE_PUBLISH_FLEX_MS
#define BLE_PUBLISH_FLEX_MS 250u
#endif

// -----------------------------------------------------------------------------
// Sensor read / compute gating (ms)
// These gate the *work* itself (not just BLE TX) so the loop can prioritize
// realtime pressure scanning + stable PPG waveform streaming.
// -----------------------------------------------------------------------------
#ifndef SENSOR_FLEX_READ_MS
#define SENSOR_FLEX_READ_MS 250u
#endif
// HR/SpO2 RF algorithm compute (still collects PPG continuously for FIFO + waveform ring).
#ifndef PPG_METRICS_COMPUTE_MS
#define PPG_METRICS_COMPUTE_MS 0u  // normal behavior: compute on every completed RF window
#endif

// -----------------------------------------------------------------------------
// PPG waveform window BLE TX (625 processed samples captured during plot mode)
// Transmitted in normal mode in small bursts to avoid disturbing loop timing.
// -----------------------------------------------------------------------------
#ifndef PPG_WAVE_BLE_PACKETS_PER_STEP
#define PPG_WAVE_BLE_PACKETS_PER_STEP 1u
#endif

#ifndef PPG_WAVE_PACKET_SPACING_MS
#define PPG_WAVE_PACKET_SPACING_MS 8u
#endif

// Samples per PPG waveform BLE chunk (each sample is int32).
// 14 samples -> 56 data bytes; with the 8-byte P/W header this encrypts to 80 bytes,
// which fits the existing 81-byte BLE characteristic as [len][ciphertext].
#ifndef PPG_WAVE_SAMPLES_PER_CHUNK
#define PPG_WAVE_SAMPLES_PER_CHUNK 14u
#endif

// Repeat the same captured waveform window so Android can fill any chunks missed while pressure streams.
#ifndef PPG_WAVE_REPEAT_CYCLES
#define PPG_WAVE_REPEAT_CYCLES 2u
#endif

// -----------------------------------------------------------------------------
// Automatic plot-mode schedule (no hotkeys)
// Starts in normal mode, every PLOT_MODE_EVERY_MS enters plot mode for PLOT_MODE_DURATION_MS,
// then returns to normal. Repeats forever.
// -----------------------------------------------------------------------------
#ifndef PLOT_MODE_FIRST_DELAY_MS
#define PLOT_MODE_FIRST_DELAY_MS 8000u
#endif
#ifndef PLOT_MODE_EVERY_MS
#define PLOT_MODE_EVERY_MS 60000u
#endif
#ifndef PLOT_MODE_DURATION_MS
#define PLOT_MODE_DURATION_MS 6000u
#endif

// -----------------------------------------------------------------------------
// PPG waveform serial stream (for ppg_waveform/plot_ppg_model.py style)
// Prints exactly one signed integer per line at PPG_WAVEFORM_HZ, independent of BLE/pressure load.
// -----------------------------------------------------------------------------
#ifndef PPG_WAVEFORM_STREAM_ENABLED
#define PPG_WAVEFORM_STREAM_ENABLED 1
#endif
// Test mode (like ppg_waveform.ino):
// 0 = plot values (one int per line)
// 1 = print "Samples/sec: N" once per second
#ifndef PPG_WAVEFORM_TEST_MODE
#define PPG_WAVEFORM_TEST_MODE 0
#endif
#ifndef PPG_WAVEFORM_HZ
#define PPG_WAVEFORM_HZ 125u
#endif
#ifndef PPG_WAVEFORM_RING_SAMPLES
#define PPG_WAVEFORM_RING_SAMPLES 2048u
#endif

// Match `ppg_waveform/ppg_waveform.ino` processing by default:
// - EMA smoothing
// - inversion (multiply by -1)
#ifndef PPG_WAVEFORM_EMA_ALPHA
#define PPG_WAVEFORM_EMA_ALPHA 0.2f
#endif
#ifndef PPG_WAVEFORM_INVERT
#define PPG_WAVEFORM_INVERT 1
#endif

// Main loop delay after one full pass (lower = faster PPG window fill; higher = lower CPU / BLE load)
#define MAIN_LOOP_DELAY_MS 0

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

#if LOG_LOOP_TIMING_ENABLED
  #define LOG_LOOP_TIMING(stmt) do { stmt; } while (0)
#else
  #define LOG_LOOP_TIMING(stmt) do { (void)0; } while (0)
#endif

#endif
