#ifndef IMU_READER_H
#define IMU_READER_H

#include <Arduino_BMI270_BMM150.h>

// Limit how many gyro/accel samples we read per call. Uncapped drains could stall the
// main loop for tens of ms and overflow the MAX30102 FIFO (≈32 samples @ 100 Hz → RF -999).
#ifndef IMU_MAX_GYRO_READS_PER_LOOP
#define IMU_MAX_GYRO_READS_PER_LOOP 4
#endif
#ifndef IMU_MAX_ACCEL_READS_PER_LOOP
#define IMU_MAX_ACCEL_READS_PER_LOOP 4
#endif

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

