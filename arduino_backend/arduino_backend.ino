#include <ArduinoBLE.h>
#include "imu_reader.h"
#include "ppg_reader.h"

// -----------------------------------------------------------------------------
// BLE Service and Characteristics - NO ENCRYPTION
// -----------------------------------------------------------------------------
// Service: Cura Wearable Service
BLEService wearableService("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00");

// Each sensor type uses a SEPARATE BLE characteristic (separate channel)
// Data sent as PLAIN TEXT CSV format (no encryption)

// Characteristic: Accelerometer Data (ax,ay,az) - Channel 1
BLEStringCharacteristic accelChar(
  "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  64  // Reduced size for plain text
);

// Characteristic: Gyroscope Data (gx,gy,gz) - Channel 2
BLEStringCharacteristic gyroChar(
  "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  64
);

// Characteristic: Heart Rate Data (bpm,beatAvg,minAvg,hrAvg) - Channel 3
BLEStringCharacteristic heartRateChar(
  "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  64
);

// Characteristic: SpO2 Data (espO2,spO2) - Channel 4
BLEStringCharacteristic spo2Char(
  "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  64
);

// -----------------------------------------------------------------------------
// SETUP
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(9600);
  while (!Serial);

  Serial.println("🔬 Initializing wearable sensors...");

  // Initialize IMU
  if (!imu_init()) {
    Serial.println("❌ Failed to initialize IMU!");
    while (1);
  }
  Serial.println("✅ IMU initialized.");

  // Initialize PPG
  if (!ppg_init()) {
    Serial.println("❌ Failed to initialize PPG!");
    while (1);
  }
  Serial.println("✅ PPG initialized.");

  // Initialize BLE
  if (!BLE.begin()) {
    Serial.println("❌ Starting BLE failed!");
    while (1);
  }

  BLE.setLocalName("Cura");
  BLE.setDeviceName("Cura");
  BLE.setAdvertisedService(wearableService);

  wearableService.addCharacteristic(accelChar);
  wearableService.addCharacteristic(gyroChar);
  wearableService.addCharacteristic(heartRateChar);
  wearableService.addCharacteristic(spo2Char);
  BLE.addService(wearableService);

  accelChar.writeValue("0.00,0.00,0.00");
  gyroChar.writeValue("0.00,0.00,0.00");
  heartRateChar.writeValue("--");
  spo2Char.writeValue("--");

  BLE.advertise();
  Serial.println("📡 Advertising as Cura...");
  Serial.println("📝 Sending PLAIN TEXT data (no encryption)");
}

// -----------------------------------------------------------------------------
// LOOP - Synchronized data streaming
// -----------------------------------------------------------------------------
void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("🔗 Connected to: ");
    Serial.println(central.address());

    while (central.connected()) {
      // Read and stream IMU data - PLAIN TEXT
      IMUData imuData = readIMU();
      if (imuData.available) {
        String accelData = String(imuData.ax, 2) + "," + String(imuData.ay, 2) + "," + String(imuData.az, 2);
        String gyroData = String(imuData.gx, 2) + "," + String(imuData.gy, 2) + "," + String(imuData.gz, 2);
        
        Serial.print("[ACCEL] ");
        Serial.println(accelData);
        accelChar.writeValue(accelData);
        
        Serial.print("[GYRO] ");
        Serial.println(gyroData);
        gyroChar.writeValue(gyroData);
      }

      // Read and stream PPG data - PLAIN TEXT
      PPGData ppgData = readPPG();
      
      if (ppgData.heartRateAvailable) {
        String hrData = String(ppgData.beatsPerMinute, 1) + "," + 
                        String(ppgData.beatAvg) + "," + 
                        String(ppgData.minAvg) + "," + 
                        String(ppgData.hrAvg);
        heartRateChar.writeValue(hrData);
      }

      if (ppgData.spo2Available) {
        String spo2Data = String(ppgData.espO2, 1) + "," + String(ppgData.spO2, 1);
        spo2Char.writeValue(spo2Data);
      }

      delay(100); // ~10 Hz synchronized update rate
    }

    Serial.println("🔌 Disconnected.");
  }
}

