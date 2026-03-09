#include "flex_reader.h"

// -----------------------------------------------------------------------------
// Flex sensors edema classification
// EXACT original behavior version
// - 60s calibration window (blocking, same as original)
// - baseline = MIN over 60s, computed on SMOOTHED readings and only when STABLE
// - moving average smoothing during normal operation
// - classification logic + thresholds unchanged
// -----------------------------------------------------------------------------

const int flexPin1 = A0;
const int flexPin2 = A1;

int baseline_value1 = 0;
int baseline_value2 = 0;

int current_value1 = 0;
int current_value2 = 0;

int deviation1 = 0;
int deviation2 = 0;
int total_deviation = 0;

// Thresholds unchanged
const int subclinical_threshold = 3;
const int mild_threshold        = 5;
const int moderate_threshold    = 10;
const int severe_threshold      = 20;

// Timing
const unsigned long CALIBRATION_MS  = 60000;
const unsigned long SAMPLE_DELAY_MS = 200;

// Smoothing window
const int MA_N = 10;
int buf1[MA_N], buf2[MA_N];
int idx = 0;
long sum1 = 0, sum2 = 0;
bool ma_init = false;

// Calibration robustness settings
const unsigned long WARMUP_MS = 5000;
const int STABLE_THRESH = 2;
const int STABLE_REQUIRED = 6;

bool calibrated = false;
bool initialized = false;

int movingAvgUpdate(int raw, int *buf, long &sum) {
  sum -= buf[idx];
  buf[idx] = raw;
  sum += buf[idx];
  return (int)(sum / MA_N);
}

void initMovingAvgTo(int init1, int init2) {
  sum1 = 0;
  sum2 = 0;
  for (int i = 0; i < MA_N; i++) {
    buf1[i] = init1;
    buf2[i] = init2;
    sum1 += buf1[i];
    sum2 += buf2[i];
  }
  idx = 0;
  ma_init = true;
}

bool flex_init() {
  calibrated = false;
  initialized = true;

  Serial.println("=== Calibration started (60 seconds) ===");
  Serial.println("Reading sensors only. No edema evaluation during calibration.");
  Serial.println("Baseline = MIN of SMOOTHED readings (stable-only). Thresholds unchanged.");

  return true;
}

bool flex_isCalibrated() {
  return calibrated;
}

FlexData readFlex() {
  FlexData data;
  data.raw1 = 0;
  data.raw2 = 0;
  data.filtered1 = 0;
  data.filtered2 = 0;
  data.baseline1 = baseline_value1;
  data.baseline2 = baseline_value2;
  data.deviation1 = 0;
  data.deviation2 = 0;
  data.totalDeviation = 0;
  data.edemaLabel = calibrated ? "none" : "calibrating";
  data.calibrated = calibrated;
  data.dataAvailable = false;

  if (!initialized) {
    return data;
  }

  // -------------------------
  // Calibration phase
  // -------------------------
  if (!calibrated) {
    unsigned long start_ms = millis();

    long preAcc1 = 0, preAcc2 = 0;
    int preN = 0;

    unsigned long preStart = millis();
    while (millis() - preStart < 1000) {
      int r1 = analogRead(flexPin1);
      int r2 = analogRead(flexPin2);
      preAcc1 += r1;
      preAcc2 += r2;
      preN++;
      delay(SAMPLE_DELAY_MS);
    }

    int preMean1 = (int)(preAcc1 / preN);
    int preMean2 = (int)(preAcc2 / preN);

    initMovingAvgTo(preMean1, preMean2);

    int minFilt1 = 1023;
    int minFilt2 = 1023;

    int minRaw1 = 1023, minRaw2 = 1023;
    int maxRaw1 = 0,    maxRaw2 = 0;

    int stableCount1 = 0;
    int stableCount2 = 0;

    while (millis() - start_ms < CALIBRATION_MS) {
      unsigned long t = millis() - start_ms;

      int raw1 = analogRead(flexPin1);
      int raw2 = analogRead(flexPin2);

      int filt1 = movingAvgUpdate(raw1, buf1, sum1);
      int filt2 = movingAvgUpdate(raw2, buf2, sum2);

      idx++;
      if (idx >= MA_N) idx = 0;

      if (raw1 < minRaw1) minRaw1 = raw1;
      if (raw2 < minRaw2) minRaw2 = raw2;
      if (raw1 > maxRaw1) maxRaw1 = raw1;
      if (raw2 > maxRaw2) maxRaw2 = raw2;

      if (t >= WARMUP_MS) {
        if (abs(raw1 - filt1) <= STABLE_THRESH) stableCount1++;
        else stableCount1 = 0;

        if (abs(raw2 - filt2) <= STABLE_THRESH) stableCount2++;
        else stableCount2 = 0;

        if (stableCount1 >= STABLE_REQUIRED) {
          if (filt1 < minFilt1) minFilt1 = filt1;
        }
        if (stableCount2 >= STABLE_REQUIRED) {
          if (filt2 < minFilt2) minFilt2 = filt2;
        }
      }

      Serial.print("CAL,t=");
      Serial.print(t);
      Serial.print(",raw1="); Serial.print(raw1);
      Serial.print(",filt1="); Serial.print(filt1);
      Serial.print(",raw2="); Serial.print(raw2);
      Serial.print(",filt2="); Serial.println(filt2);

      delay(SAMPLE_DELAY_MS);
    }

    if (minFilt1 == 1023) minFilt1 = preMean1;
    if (minFilt2 == 1023) minFilt2 = preMean2;

    baseline_value1 = minFilt1;
    baseline_value2 = minFilt2;

    initMovingAvgTo(baseline_value1, baseline_value2);

    Serial.println("=== Calibration complete ===");
    Serial.print("Sensor1 baseline(MIN filt, stable-only)="); Serial.println(baseline_value1);
    Serial.print("Sensor1 raw(min/max)="); Serial.print(minRaw1); Serial.print("/"); Serial.println(maxRaw1);

    Serial.print("Sensor2 baseline(MIN filt, stable-only)="); Serial.println(baseline_value2);
    Serial.print("Sensor2 raw(min/max)="); Serial.print(minRaw2); Serial.print("/"); Serial.println(maxRaw2);

    Serial.println("=== Starting edema classification ===");
    Serial.println("time_s,raw1,raw2,filt1,filt2,dev1,dev2,total_dev,label");

    calibrated = true;

    data.baseline1 = baseline_value1;
    data.baseline2 = baseline_value2;
    data.edemaLabel = "calibrating";
    data.calibrated = true;
    data.dataAvailable = false;

    return data;
  }

  // -------------------------
  // Normal phase
  // -------------------------
  int raw1 = analogRead(flexPin1);
  int raw2 = analogRead(flexPin2);

  current_value1 = movingAvgUpdate(raw1, buf1, sum1);
  current_value2 = movingAvgUpdate(raw2, buf2, sum2);

  idx++;
  if (idx >= MA_N) idx = 0;

  deviation1 = abs(current_value1 - baseline_value1);
  deviation2 = abs(current_value2 - baseline_value2);
  total_deviation = (deviation1 + deviation2) / 2;

  const char* label;
  if (total_deviation >= severe_threshold) label = "severe";
  else if (total_deviation >= moderate_threshold) label = "moderate";
  else if (total_deviation >= mild_threshold) label = "mild";
  else if (total_deviation >= subclinical_threshold) label = "subclinical";
  else label = "none";

  float t_s = millis() / 1000.0;

  Serial.print(t_s, 2); Serial.print(",");
  Serial.print(raw1); Serial.print(",");
  Serial.print(raw2); Serial.print(",");
  Serial.print(current_value1); Serial.print(",");
  Serial.print(current_value2); Serial.print(",");
  Serial.print(deviation1); Serial.print(",");
  Serial.print(deviation2); Serial.print(",");
  Serial.print(total_deviation); Serial.print(",");
  Serial.println(label);

  data.raw1 = raw1;
  data.raw2 = raw2;
  data.filtered1 = current_value1;
  data.filtered2 = current_value2;
  data.baseline1 = baseline_value1;
  data.baseline2 = baseline_value2;
  data.deviation1 = deviation1;
  data.deviation2 = deviation2;
  data.totalDeviation = total_deviation;
  data.edemaLabel = label;
  data.calibrated = true;
  data.dataAvailable = true;

  delay(SAMPLE_DELAY_MS);

  return data;
}