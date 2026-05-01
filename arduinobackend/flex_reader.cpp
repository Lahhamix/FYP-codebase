#include "flex_reader.h"
#include "serial_log.h"

// -----------------------------------------------------------------------------
// Flex swelling detection for the main firmware loop
//
// This mirrors flex_correct.cpp so the integrated firmware computes the same
// raw averages, circumferences, stable volume, baseline, and classification.
// - returns label-only for BLE: calibrating/none/mild/moderate/severe
// -----------------------------------------------------------------------------

static const uint8_t PIN_UL = A6;
static const uint8_t PIN_UR = A3;
static const uint8_t PIN_LL = A2;
static const uint8_t PIN_LR = A1;

static const float PI_F = 3.14159265f;
static const float D_CM = 1.1f;

static const uint16_t NUM_SAMPLES = 100;
static const uint8_t VOLUME_REPEATS = 5;
static const uint32_t FLEX_READING_PERIOD_MS = 10000UL;
static const uint32_t FLEX_SAMPLE_INTERVAL_MS = 10UL;
static const uint32_t AUTO_BASELINE_DURATION_MS = 60000UL;
static const uint8_t MAX_BASELINE_READINGS = 25;

// Calibration equations from flex_correct.cpp / Excel, now using 12-bit ADC.
static const float M_UL = -0.1426f;
static const float B_UL = 79.17f;
static const float M_UR = -0.3484f;
static const float B_UR = 175.59f;
static const float M_LL = -0.1941f;
static const float B_LL = 107.17f;
static const float M_LR = -0.1483f;
static const float B_LR = 83.73f;

struct VolumeDetails {
  float rawUL = 0.0f;
  float rawUR = 0.0f;
  float rawLL = 0.0f;
  float rawLR = 0.0f;
  float circUL = 0.0f;
  float circUR = 0.0f;
  float circLL = 0.0f;
  float circLR = 0.0f;
  float c1 = 0.0f;
  float c2 = 0.0f;
  float volume = 0.0f;
};

struct RepeatAccumulator {
  float sumUL = 0.0f;
  float sumUR = 0.0f;
  float sumLL = 0.0f;
  float sumLR = 0.0f;
  uint32_t count = 0;
};

static float s_baselineReadings[MAX_BASELINE_READINGS];
static uint8_t s_baselineCount = 0;
static uint32_t s_baselineStartMs = 0;
static uint32_t s_readingWindowStartMs = 0;
static uint32_t s_lastRawSampleMs = 0;
static float s_baselineVolume = 0.0f;
static bool s_baselineSet = false;
static float s_currentVolume = 0.0f;
static float s_lastSpreadPercent = 0.0f;
static VolumeDetails s_lastDetails;
static const char* s_lastLabel = "calibrating";
static RepeatAccumulator s_repeatAccumulators[VOLUME_REPEATS];

// Grade 3 hysteresis from flex_new.ino
static bool s_severeLatched = false;
static uint8_t s_aboveSevereCount = 0;
static uint8_t s_belowSevereCount = 0;

static float estimateFrustumVolume(float c1, float c2) {
  const float r1 = c1 / (2.0f * PI_F);
  const float r2 = c2 / (2.0f * PI_F);
  return (PI_F * D_CM / 3.0f) * ((r1 * r1) + (r1 * r2) + (r2 * r2));
}

static VolumeDetails makeVolumeDetails(float rawUL, float rawUR, float rawLL, float rawLR) {
  VolumeDetails d;
  d.rawUL = rawUL;
  d.rawUR = rawUR;
  d.rawLL = rawLL;
  d.rawLR = rawLR;

  d.circUL = M_UL * d.rawUL + B_UL;
  d.circUR = M_UR * d.rawUR + B_UR;
  d.circLL = M_LL * d.rawLL + B_LL;
  d.circLR = M_LR * d.rawLR + B_LR;

  d.c1 = (d.circUL + d.circUR) / 2.0f;
  d.c2 = (d.circLL + d.circLR) / 2.0f;
  d.volume = estimateFrustumVolume(d.c1, d.c2);
  return d;
}

static void resetStableSampling() {
  for (uint8_t i = 0; i < VOLUME_REPEATS; i++) {
    s_repeatAccumulators[i] = RepeatAccumulator{};
  }
  s_readingWindowStartMs = 0;
  s_lastRawSampleMs = 0;
}

static float finishStableVolume() {
  float sum = 0.0f;
  float minV = 100000.0f;
  float maxV = -100000.0f;
  uint8_t usedRepeats = 0;

  for (uint8_t i = 0; i < VOLUME_REPEATS; i++) {
    const RepeatAccumulator& acc = s_repeatAccumulators[i];
    if (acc.count == 0) continue;

    VolumeDetails details = makeVolumeDetails(
      acc.sumUL / (float)acc.count,
      acc.sumUR / (float)acc.count,
      acc.sumLL / (float)acc.count,
      acc.sumLR / (float)acc.count
    );
    s_lastDetails = details;

    sum += details.volume;
    if (details.volume < minV) minV = details.volume;
    if (details.volume > maxV) maxV = details.volume;
    usedRepeats++;

  }

  if (usedRepeats == 0) {
    s_lastSpreadPercent = 0.0f;
    return 0.0f;
  }

  const float avg = sum / (float)usedRepeats;
  s_lastSpreadPercent = (avg != 0.0f) ? (((maxV - minV) / avg) * 100.0f) : 0.0f;
  return avg;
}

static bool collectStableVolume(uint32_t now) {
  if (s_readingWindowStartMs == 0) {
    s_readingWindowStartMs = now;
  }

  const uint32_t elapsedMs = (uint32_t)(now - s_readingWindowStartMs);

  if (s_lastRawSampleMs == 0 || (uint32_t)(now - s_lastRawSampleMs) >= FLEX_SAMPLE_INTERVAL_MS) {
    s_lastRawSampleMs = now;
    uint8_t repeatIndex = (uint8_t)(((uint64_t)elapsedMs * VOLUME_REPEATS) / FLEX_READING_PERIOD_MS);
    if (repeatIndex >= VOLUME_REPEATS) {
      repeatIndex = VOLUME_REPEATS - 1;
    }

    RepeatAccumulator& acc = s_repeatAccumulators[repeatIndex];
    acc.sumUL += (float)analogRead(PIN_UL);
    acc.sumUR += (float)analogRead(PIN_UR);
    acc.sumLL += (float)analogRead(PIN_LL);
    acc.sumLR += (float)analogRead(PIN_LR);
    acc.count++;
  }

  if (elapsedMs < FLEX_READING_PERIOD_MS) {
    return false;
  }

  s_currentVolume = finishStableVolume();
  resetStableSampling();
  return true;
}

static void sortArray(float* arr, int n) {
  for (int i = 0; i < n - 1; i++) {
    for (int j = i + 1; j < n; j++) {
      if (arr[j] < arr[i]) {
        const float tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
      }
    }
  }
}

static float computeMedian(const float* src, int n) {
  if (n <= 0) return 0.0f;
  float tmp[MAX_BASELINE_READINGS];
  for (int i = 0; i < n; i++) tmp[i] = src[i];
  sortArray(tmp, n);
  if ((n & 1) == 1) return tmp[n / 2];
  return (tmp[(n / 2) - 1] + tmp[n / 2]) / 2.0f;
}

static void resetSevereLogic() {
  s_severeLatched = false;
  s_aboveSevereCount = 0;
  s_belowSevereCount = 0;
}

static const char* classifySwelling(float percentChange) {
  const float GRADE1_MIN = 5.0f;
  const float GRADE2_MIN = 10.0f;
  const float GRADE3_ON = 30.0f;
  const float GRADE3_OFF = 29.0f;

  if (percentChange > GRADE3_ON) {
    s_aboveSevereCount++;
  } else {
    s_aboveSevereCount = 0;
  }

  if (s_aboveSevereCount >= 2) {
    s_severeLatched = true;
    s_belowSevereCount = 0;
  }

  if (s_severeLatched) {
    if (percentChange < GRADE3_OFF) {
      s_belowSevereCount++;
    } else {
      s_belowSevereCount = 0;
    }

    if (s_belowSevereCount >= 2) {
      s_severeLatched = false;
      s_belowSevereCount = 0;
      s_aboveSevereCount = 0;
    } else {
      return "severe";
    }
  }

  if (percentChange < GRADE1_MIN) return "none";
  if (percentChange <= GRADE2_MIN) return "mild";
  if (percentChange <= GRADE3_ON) return "moderate";
  return "severe";
}

static void logFlexSample(float percentChange, const char* label) {
#if LOG_ENABLED && LOG_FLEX_ENABLED
  Serial.print(F("[FLEX] UL raw="));
  Serial.print(s_lastDetails.rawUL, 2);
  Serial.print(F(" UR="));
  Serial.print(s_lastDetails.rawUR, 2);
  Serial.print(F(" LL="));
  Serial.print(s_lastDetails.rawLL, 2);
  Serial.print(F(" LR="));
  Serial.print(s_lastDetails.rawLR, 2);
  Serial.print(F(" | circ UL="));
  Serial.print(s_lastDetails.circUL, 2);
  Serial.print(F(" UR="));
  Serial.print(s_lastDetails.circUR, 2);
  Serial.print(F(" LL="));
  Serial.print(s_lastDetails.circLL, 2);
  Serial.print(F(" LR="));
  Serial.print(s_lastDetails.circLR, 2);
  Serial.print(F(" | C1="));
  Serial.print(s_lastDetails.c1, 2);
  Serial.print(F(" C2="));
  Serial.print(s_lastDetails.c2, 2);
  Serial.print(F(" | V="));
  Serial.print(s_currentVolume, 2);
  Serial.print(F(" spread="));
  Serial.print(s_lastSpreadPercent, 2);
  Serial.print(F("%"));
  if (s_baselineSet) {
    Serial.print(F(" base="));
    Serial.print(s_baselineVolume, 2);
    Serial.print(F(" d%="));
    Serial.print(percentChange, 2);
    Serial.print(F(" label="));
    Serial.println(label);
  } else {
    Serial.print(F(" baseline "));
    Serial.print(s_baselineCount);
    Serial.print(F("/"));
    Serial.print((int)MAX_BASELINE_READINGS);
    Serial.println(F(" calibrating"));
  }
#else
  (void)percentChange;
  (void)label;
#endif
}

bool flex_init() {
#ifdef analogReadResolution
  analogReadResolution(12);
#endif
  s_baselineStartMs = 0;
  s_baselineCount = 0;
  s_baselineVolume = 0.0f;
  s_baselineSet = false;
  s_currentVolume = 0.0f;
  s_lastSpreadPercent = 0.0f;
  s_lastLabel = "calibrating";
  resetStableSampling();
  resetSevereLogic();
  LOG_FLEX(Serial.println(F("[FLEX] flex_correct-compatible 10s non-blocking swelling algorithm enabled.")));
  return true;
}

bool flex_isCalibrated() {
  return s_baselineSet;
}

FlexData readFlex() {
  FlexData data{};
  data.raw1 = (int)s_lastDetails.rawUL;
  data.raw2 = (int)s_lastDetails.rawUR;
  data.filtered1 = 0;
  data.filtered2 = 0;
  data.baseline1 = 0;
  data.baseline2 = 0;
  data.deviation1 = 0;
  data.deviation2 = 0;
  data.totalDeviation = 0;
  data.edemaLabel = s_lastLabel;
  data.calibrated = s_baselineSet;
  data.dataAvailable = false;

  const uint32_t now = (uint32_t)millis();
  if (s_baselineStartMs == 0) {
    s_baselineStartMs = now;
  }

  if (!collectStableVolume(now)) {
    return data;
  }

  float percentChange = 0.0f;
  if (!s_baselineSet) {
    const uint32_t elapsedMs = (uint32_t)(millis() - s_baselineStartMs);
    if (elapsedMs < AUTO_BASELINE_DURATION_MS && s_baselineCount < MAX_BASELINE_READINGS) {
      s_baselineReadings[s_baselineCount++] = s_currentVolume;
    }

    if (elapsedMs >= AUTO_BASELINE_DURATION_MS || s_baselineCount >= MAX_BASELINE_READINGS) {
      s_baselineVolume = computeMedian(s_baselineReadings, (int)s_baselineCount);
      s_baselineSet = (s_baselineVolume > 0.0f);
      resetSevereLogic();
      s_lastLabel = s_baselineSet ? "none" : "calibrating";
      LOG_FLEX(Serial.print(F("[FLEX] baseline saved V=")));
      LOG_FLEX(Serial.println(s_baselineVolume, 2));
    } else {
      s_lastLabel = "calibrating";
    }
  } else {
    percentChange = ((s_currentVolume - s_baselineVolume) / s_baselineVolume) * 100.0f;
    s_lastLabel = classifySwelling(percentChange);
  }

  logFlexSample(percentChange, s_lastLabel);

  data.raw1 = (int)s_lastDetails.rawUL;
  data.raw2 = (int)s_lastDetails.rawUR;
  data.edemaLabel = s_lastLabel;
  data.calibrated = s_baselineSet;
  data.dataAvailable = true;
  return data;
}
