#ifndef FLEX_READER_H
#define FLEX_READER_H

#include <Arduino.h>

struct FlexData {
  int raw1;
  int raw2;

  int filtered1;
  int filtered2;

  int baseline1;
  int baseline2;

  int deviation1;
  int deviation2;
  int totalDeviation;

  const char* edemaLabel;

  bool calibrated;
  bool dataAvailable;
};

bool flex_init();
FlexData readFlex();
bool flex_isCalibrated();

#endif