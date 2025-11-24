#include "imu_reader.h"

bool imu_init() {
  return IMU.begin();
}

IMUData readIMU() {
  IMUData data;
  data.available = false;
  
  if (IMU.accelerationAvailable() && IMU.gyroscopeAvailable()) {
    IMU.readAcceleration(data.ax, data.ay, data.az);
    IMU.readGyroscope(data.gx, data.gy, data.gz);
    data.available = true;
  }
  
  return data;
}

