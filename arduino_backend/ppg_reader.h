#ifndef PPG_READER_H
#define PPG_READER_H

#include <Wire.h>
#include "MAX30105.h"
#include "heartRate.h"

struct PPGData {
  int32_t beatsPerMinute;
  double spO2;
  bool heartRateAvailable;
  bool spo2Available;
  int8_t validHeartRate;   // 1 if heart rate is valid, 0 otherwise
  int8_t validSPO2;        // 1 if SpO2 is valid, 0 otherwise
};

bool ppg_init();
PPGData readPPG();
void resetSpo2();

#endif

