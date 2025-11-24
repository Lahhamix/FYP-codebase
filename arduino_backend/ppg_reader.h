#ifndef PPG_READER_H
#define PPG_READER_H

#include <Wire.h>
#include "MAX30105.h"
#include "heartRate.h"

struct PPGData {
  float beatsPerMinute;
  int beatAvg;
  int minAvg;
  int hrAvg;
  float espO2;
  double spO2;
  bool heartRateAvailable;
  bool spo2Available;
  long irValue;  // For debugging
};

bool ppg_init();
PPGData readPPG();

#endif

