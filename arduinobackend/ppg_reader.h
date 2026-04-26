#ifndef PPG_READER_H
#define PPG_READER_H

#include <Arduino.h>
#include "serial_log.h"

struct PPGData {
  int32_t beatsPerMinute;
  double spO2;
  bool heartRateAvailable;
  bool spo2Available;
  int8_t validHeartRate;
  int8_t validSPO2;

  float ratio;
  float correl;

  bool fingerDetected;

  /** True only on iterations where a full RF window just finished (new HR/SpO2 computed). */
  bool newWindow;
};

bool ppg_init();

/** Call once per main loop: collects one PPG sample per call (non-blocking vs other sensors). */
PPGData ppg_tick();

/** Optional: print HR/SpO2 line + IR sample lines when a new window completed (uses internal buffers). */
void ppg_print_serial_on_new_window(const PPGData& d);

/** Enable/disable HR/SpO2 metrics compute (sampling continues regardless). */
void ppg_set_metrics_enabled(bool enabled);

#if PPG_WAVEFORM_STREAM_ENABLED
/** Set MAX30102 to a higher sample rate while plotting (restores defaults when disabled). */
void ppg_set_plot_mode(bool enabled);

/** Pop (or interpolate) the next waveform sample for serial streaming at PPG_WAVEFORM_HZ. */
bool ppg_waveform_next(int32_t* outSample);

/** Drain the ring and return the most recent IR sample (ppg_waveform.ino semantics). */
bool ppg_waveform_take_latest(uint32_t* outIr);

/**
 * Plot-mode fast path: non-blocking FIFO drain that returns the latest IR sample if available.
 * This avoids the blocking waits inside ppg_tick()/readNextSampleFromFifo and matches the
 * "latest sample" semantics of `ppg_waveform/ppg_waveform.ino`.
 */
bool ppg_waveform_acquire_latest(uint32_t* outIr);
#endif

#endif
