#ifndef IMU_READER_H
#define IMU_READER_H

#include <Arduino_BMI270_BMM150.h>

struct IMUData {
  float ax, ay, az;
  float gx, gy, gz;
  // Derived metrics (computed in readIMU when available)
  float aMag;       // |accel| in g
  float gMag;       // |gyro| in deg/s
  float aDyn;       // |aMag - lowpass(aMag)| in g (rough "dynamic accel")
  bool isMoving;    // debounced motion flag
  // Step counter (paper-based peak detection on filtered accel magnitude)
  uint32_t stepCount;   // total steps since boot
  bool available;
};

bool imu_init();
IMUData readIMU();

/** Call during long work (e.g. pressure matrix scan) to drain IMU accel and update step count. */
void imu_pump_steps();

#endif

