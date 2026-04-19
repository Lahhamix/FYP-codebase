#ifndef PPG_READER_H
#define PPG_READER_H

#include <Arduino.h>

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

/** Optional: print HR/SpO2 line + red,ir CSV lines when a new window completed (uses internal buffers). */
void ppg_print_serial_on_new_window(const PPGData& d);

#endif
