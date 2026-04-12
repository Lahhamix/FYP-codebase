#include "imu_reader.h"
#include <math.h>

// Motion detection tuning (units: accel in g, gyro in deg/s)
#ifndef IMU_MOTION_ACCEL_DYN_G
#define IMU_MOTION_ACCEL_DYN_G 0.1f
#endif
#ifndef IMU_MOTION_GYRO_DPS
#define IMU_MOTION_GYRO_DPS 30.0f
#endif
#ifndef IMU_ACCEL_LP_ALPHA
#define IMU_ACCEL_LP_ALPHA 0.90f  // closer to 1.0 = slower baseline (gravity) tracking
#endif
#ifndef IMU_MOTION_DEBOUNCE_SAMPLES
#define IMU_MOTION_DEBOUNCE_SAMPLES 1
#endif

#ifndef IMU_STEP_LPF_ALPHA
#define IMU_STEP_LPF_ALPHA 0.85f
#endif
#ifndef IMU_STEP_PEAK_THRESHOLD_G
#define IMU_STEP_PEAK_THRESHOLD_G 1.15f
#endif
#ifndef IMU_STEP_MIN_INTERVAL_MS
#define IMU_STEP_MIN_INTERVAL_MS 250ul
#endif

static inline float imu_mag3(float x, float y, float z) {
  return sqrtf(x * x + y * y + z * z);
}

static float s_lastAx = 0, s_lastAy = 0, s_lastAz = 1;
static float s_lastGx = 0, s_lastGy = 0, s_lastGz = 0;

static uint32_t s_lastStepTime = 0;
static uint32_t s_stepCount = 0;
static float s_f_prev2 = 0.0f, s_f_prev1 = 0.0f, s_f_curr = 0.0f;
static float s_magFiltered = 0.0f;
static bool s_stepFirst = true;
static bool s_unitsDetected = false;
static float s_accelToG = 1.0f;

/** Drain accel FIFO and update step count. Returns number of accel samples processed. */
static int imu_drain_accel_for_steps(uint32_t nowMs) {
  int n = 0;
  while (IMU.accelerationAvailable()) {
    float ax, ay, az;
    IMU.readAcceleration(ax, ay, az);
    s_lastAx = ax;
    s_lastAy = ay;
    s_lastAz = az;

    if (!s_unitsDetected) {
      float mag = imu_mag3(ax, ay, az);
      s_accelToG = (mag > 4.0f) ? (1.0f / 9.80665f) : 1.0f;
      s_unitsDetected = true;
    }

    float magRaw = imu_mag3(ax, ay, az);
    float magG = magRaw * s_accelToG;

    if (s_stepFirst) {
      s_magFiltered = magG;
      s_f_prev2 = s_magFiltered;
      s_f_prev1 = s_magFiltered;
      s_f_curr = s_magFiltered;
      s_stepFirst = false;
    } else {
      s_magFiltered = IMU_STEP_LPF_ALPHA * s_magFiltered + (1.0f - IMU_STEP_LPF_ALPHA) * magG;
    }

    s_f_prev2 = s_f_prev1;
    s_f_prev1 = s_f_curr;
    s_f_curr = s_magFiltered;

    bool isLocalMax = (s_f_prev1 > s_f_prev2) && (s_f_prev1 > s_f_curr);
    bool aboveThresh = (s_f_prev1 > IMU_STEP_PEAK_THRESHOLD_G);
    bool enoughTimePassed =
        ((uint32_t)(nowMs - s_lastStepTime) >= (uint32_t)IMU_STEP_MIN_INTERVAL_MS);

    if (isLocalMax && aboveThresh && enoughTimePassed) {
      s_stepCount++;
      s_lastStepTime = nowMs;
    }

    n++;
    yield();
  }
  return n;
}

void imu_pump_steps() {
  (void)imu_drain_accel_for_steps((uint32_t)millis());
}

bool imu_init() {
  return IMU.begin();
}

IMUData readIMU() {
  IMUData data{};
  data.available = false;
  data.stepCount = s_stepCount;

  static bool hasLast = false;

  bool any = false;
  while (IMU.gyroscopeAvailable()) {
    IMU.readGyroscope(s_lastGx, s_lastGy, s_lastGz);
    any = true;
    yield();
  }

  const uint32_t now = (uint32_t)millis();
  const int nAccel = imu_drain_accel_for_steps(now);
  if (nAccel > 0) {
    any = true;
  }

  if (any) {
    hasLast = true;
  }

  if (any || hasLast) {
    hasLast = true;
    data.ax = s_lastAx;
    data.ay = s_lastAy;
    data.az = s_lastAz;
    data.gx = s_lastGx;
    data.gy = s_lastGy;
    data.gz = s_lastGz;
    data.available = true;

    data.aMag = imu_mag3(data.ax, data.ay, data.az);
    data.gMag = imu_mag3(data.gx, data.gy, data.gz);

    static bool lpInit = false;
    static float aMagLP = 1.0f;
    if (!lpInit) {
      aMagLP = data.aMag;
      lpInit = true;
    } else {
      aMagLP = (IMU_ACCEL_LP_ALPHA * aMagLP) + ((1.0f - IMU_ACCEL_LP_ALPHA) * data.aMag);
    }
    data.aDyn = fabsf(data.aMag - aMagLP);

    data.stepCount = s_stepCount;

    const bool movingNow = (data.aDyn >= IMU_MOTION_ACCEL_DYN_G) || (data.gMag >= IMU_MOTION_GYRO_DPS);
    static bool movingDebounced = false;
    static uint8_t streak = 0;

    if (movingNow == movingDebounced) {
      streak = 0;
    } else {
      if (streak < 255) streak++;
      if (streak >= IMU_MOTION_DEBOUNCE_SAMPLES) {
        movingDebounced = movingNow;
        streak = 0;
      }
    }
    data.isMoving = movingDebounced;
  }

  // After accel drain, step count must reflect FIFO even when we skip the block above (no new gyro/accel this tick)
  data.stepCount = s_stepCount;

  return data;
}
