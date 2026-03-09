#include "flex_reader.h"

// -----------------------------------------------------------------------------
// Flex sensors edema classification
// Non-blocking module version for backend/app integration
// - 60s calibration window
// - baseline = MIN over 60s, computed on smoothed readings and only when stable
// - moving average smoothing during normal operation
// - thresholds unchanged
// -----------------------------------------------------------------------------

const int flexPin1 = A0;
const int flexPin2 = A1;

// Baselines
int baseline_value1 = 0;
int baseline_value2 = 0;

// Current filtered values
int current_value1 = 0;
int current_value2 = 0;

// Deviations
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
const unsigned long WARMUP_MS       = 5000;

// Smoothing
const int MA_N = 10;
int buf1[MA_N];
int buf2[MA_N];
int idx = 0;
long sum1 = 0;
long sum2 = 0;
bool ma_init = false;

// Calibration robustness
const int STABLE_THRESH   = 2;
const int STABLE_REQUIRED = 6;

// Calibration state
bool calibrated = false;
bool calibrationStarted = false;

unsigned long calibrationStartMs = 0;
unsigned long lastSampleMs = 0;

// Pre-calibration initialization
long preAcc1 = 0;
long preAcc2 = 0;
int preN = 0;
unsigned long preStartMs = 0;
bool preSamplingStarted = false;
bool preSamplingDone = false;

// Calibration mins
int minFilt1 = 1023;
int minFilt2 = 1023;
int minRaw1 = 1023;
int minRaw2 = 1023;
int maxRaw1 = 0;
int maxRaw2 = 0;

int stableCount1 = 0;
int stableCount2 = 0;

// -----------------------------------------------------------------------------

int movingAvgUpdate(int raw, int *buf, long &sum, int index) {
  sum -= buf[index];
  buf[index] = raw;
  sum += buf[index];
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

const char* classifyEdema(int totalDeviation) {
  if (totalDeviation >= severe_threshold) return "severe";
  if (totalDeviation >= moderate_threshold) return "moderate";
  if (totalDeviation >= mild_threshold) return "mild";
  if (totalDeviation >= subclinical_threshold) return "subclinical";
  return "none";
}

bool flex_init() {
  calibrated = false;
  calibrationStarted = true;
  calibrationStartMs = millis();
  lastSampleMs = 0;

  preAcc1 = 0;
  preAcc2 = 0;
  preN = 0;
  preStartMs = millis();
  preSamplingStarted = true;
  preSamplingDone = false;

  minFilt1 = 1023;
  minFilt2 = 1023;
  minRaw1 = 1023;
  minRaw2 = 1023;
  maxRaw1 = 0;
  maxRaw2 = 0;

  stableCount1 = 0;
  stableCount2 = 0;

  ma_init = false;
  idx = 0;

  Serial.println("[FLEX] Initialized. Starting 60s calibration...");
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
  data.edemaLabel = "calibrating";
  data.calibrated = calibrated;
  data.dataAvailable = false;

  unsigned long now = millis();

  // Respect sampling interval
  if (now - lastSampleMs < SAMPLE_DELAY_MS) {
    return data;
  }
  lastSampleMs = now;

  // ---------------------------------------------------------------------------
  // Calibration phase
  // ---------------------------------------------------------------------------
  if (!calibrated) {
    // Step 1: collect ~1 second of samples to initialize moving average
    if (!preSamplingDone) {
      int r1 = analogRead(flexPin1);
      int r2 = analogRead(flexPin2);

      preAcc1 += r1;
      preAcc2 += r2;
      preN++;

      if (now - preStartMs >= 1000) {
        int preMean1 = (preN > 0) ? (int)(preAcc1 / preN) : 0;
        int preMean2 = (preN > 0) ? (int)(preAcc2 / preN) : 0;

        initMovingAvgTo(preMean1, preMean2);
        preSamplingDone = true;

        Serial.print("[FLEX] Pre-calibration mean1: ");
        Serial.println(preMean1);
        Serial.print("[FLEX] Pre-calibration mean2: ");
        Serial.println(preMean2);
      }

      return data;
    }

    int raw1 = analogRead(flexPin1);
    int raw2 = analogRead(flexPin2);

    int filt1 = movingAvgUpdate(raw1, buf1, sum1, idx);
    int filt2 = movingAvgUpdate(raw2, buf2, sum2, idx);

    idx++;
    if (idx >= MA_N) idx = 0;

    unsigned long t = now - calibrationStartMs;

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

    data.raw1 = raw1;
    data.raw2 = raw2;
    data.filtered1 = filt1;
    data.filtered2 = filt2;
    data.edemaLabel = "calibrating";
    data.calibrated = false;
    data.dataAvailable = true;

    Serial.print("[FLEX] CAL raw1=");
    Serial.print(raw1);
    Serial.print(" filt1=");
    Serial.print(filt1);
    Serial.print(" raw2=");
    Serial.print(raw2);
    Serial.print(" filt2=");
    Serial.println(filt2);

    if (t >= CALIBRATION_MS) {
      if (minFilt1 == 1023) minFilt1 = (preN > 0) ? (int)(preAcc1 / preN) : raw1;
      if (minFilt2 == 1023) minFilt2 = (preN > 0) ? (int)(preAcc2 / preN) : raw2;

      baseline_value1 = minFilt1;
      baseline_value2 = minFilt2;

      initMovingAvgTo(baseline_value1, baseline_value2);

      calibrated = true;

      Serial.println("[FLEX] Calibration complete.");
      Serial.print("[FLEX] Baseline1 = ");
      Serial.println(baseline_value1);
      Serial.print("[FLEX] Baseline2 = ");
      Serial.println(baseline_value2);
    }

    return data;
  }

  // ---------------------------------------------------------------------------
  // Normal phase
  // ---------------------------------------------------------------------------
  int raw1 = analogRead(flexPin1);
  int raw2 = analogRead(flexPin2);

  current_value1 = movingAvgUpdate(raw1, buf1, sum1, idx);
  current_value2 = movingAvgUpdate(raw2, buf2, sum2, idx);

  idx++;
  if (idx >= MA_N) idx = 0;

  deviation1 = abs(current_value1 - baseline_value1);
  deviation2 = abs(current_value2 - baseline_value2);
  total_deviation = (deviation1 + deviation2) / 2;

  const char* label = classifyEdema(total_deviation);

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

  Serial.print("[FLEX] raw1=");
  Serial.print(raw1);
  Serial.print(" raw2=");
  Serial.print(raw2);
  Serial.print(" filt1=");
  Serial.print(current_value1);
  Serial.print(" filt2=");
  Serial.print(current_value2);
  Serial.print(" dev1=");
  Serial.print(deviation1);
  Serial.print(" dev2=");
  Serial.print(deviation2);
  Serial.print(" total=");
  Serial.print(total_deviation);
  Serial.print(" label=");
  Serial.println(label);

  return data;
}