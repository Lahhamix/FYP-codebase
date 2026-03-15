#ifndef IMU_READER_H
#define IMU_READER_H

#include <Arduino_BMI270_BMM150.h>

struct IMUData {
  float ax, ay, az;
  float gx, gy, gz;
  bool available;
};

bool imu_init();
IMUData readIMU();

#endif

