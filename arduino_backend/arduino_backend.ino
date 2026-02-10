#include <ArduinoBLE.h>
#include <string.h>
#include "imu_reader.h"
#include "ppg_reader.h"
#include "encryption.h"

// -----------------------------------------------------------------------------
// BLE Service and Characteristics - Encrypted payloads
// -----------------------------------------------------------------------------
// Service: Cura Wearable Service
BLEService wearableService("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00");

// Each sensor type uses a SEPARATE BLE characteristic (separate channel)
// Data is encrypted (AES-128-CBC) and Base64 encoded before transmission

// Each characteristic reserves 1 byte for length + 80 bytes ciphertext
static const int kCharacteristicSize = 81;

// Characteristic: Accelerometer Data (ax,ay,az) - Channel 1
BLECharacteristic accelChar(
  "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Characteristic: Gyroscope Data (gx,gy,gz) - Channel 2
BLECharacteristic gyroChar(
  "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Characteristic: Heart Rate Data (bpm,beatAvg,minAvg,hrAvg) - Channel 3
BLECharacteristic heartRateChar(
  "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Characteristic: SpO2 Data (espO2,spO2) - Channel 4
BLECharacteristic spo2Char(
  "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
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

  encryption_init();

  BLE.setLocalName("SoleMate");
  BLE.setDeviceName("SoleMate");
  BLE.setAdvertisedService(wearableService);

  wearableService.addCharacteristic(accelChar);
  wearableService.addCharacteristic(gyroChar);
  wearableService.addCharacteristic(heartRateChar);
  wearableService.addCharacteristic(spo2Char);
  BLE.addService(wearableService);

  EncryptedPayload accelInit;
  EncryptedPayload gyroInit;
  EncryptedPayload heartInit;
  EncryptedPayload spo2Init;
  if (encryptAccel("0.00,0.00,0.00", accelInit)) {
    writeEncryptedValue(accelChar, accelInit, "accelerometer (init)");
  }
  if (encryptGyro("0.00,0.00,0.00", gyroInit)) {
    writeEncryptedValue(gyroChar, gyroInit, "gyroscope (init)");
  }
  if (encryptHeartRate("--", heartInit)) {
    writeEncryptedValue(heartRateChar, heartInit, "heart rate (init)");
  }
  if (encryptSpO2("--", spo2Init)) {
    writeEncryptedValue(spo2Char, spo2Init, "SpO2 (init)");
  }

  BLE.advertise();
  Serial.println("📡 Advertising as SoleMate...");
  Serial.println("🔐 Streaming encrypted sensor data");
}

// -----------------------------------------------------------------------------
// LOOP - Synchronized data streaming
// -----------------------------------------------------------------------------

void writeEncryptedValue(BLECharacteristic& characteristic, const EncryptedPayload& payload, const char* label) {
  if (payload.length == 0 || payload.length > sizeof(payload.data)) {
    Serial.print("[ENCRYPTION] Invalid payload length for ");
    Serial.println(label);
    return;
  }

  uint8_t framedPayload[sizeof(payload.data) + 1];
  framedPayload[0] = (uint8_t)payload.length;
  memcpy(&framedPayload[1], payload.data, payload.length);
  characteristic.writeValue(framedPayload, payload.length + 1);
}

void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("🔗 Connected to: ");
    Serial.println(central.address());

    while (central.connected()) {
      // Read and stream IMU data
      IMUData imuData = readIMU();
      if (imuData.available) {
        String accelData = String(imuData.ax, 2) + "," + String(imuData.ay, 2) + "," + String(imuData.az, 2);
        String gyroData = String(imuData.gx, 2) + "," + String(imuData.gy, 2) + "," + String(imuData.gz, 2);
        EncryptedPayload accelEncrypted;
        EncryptedPayload gyroEncrypted;
        
        // Serial.print("[ACCEL] ");
        // Serial.println(accelData);
        if (encryptAccel(accelData, accelEncrypted)) {
          writeEncryptedValue(accelChar, accelEncrypted, "accelerometer");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt accelerometer data");
        }
        
        // Serial.print("[GYRO] ");
        // Serial.println(gyroData);
        if (encryptGyro(gyroData, gyroEncrypted)) {
          writeEncryptedValue(gyroChar, gyroEncrypted, "gyroscope");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt gyroscope data");
        }
      }

      // Read and stream PPG data
      PPGData ppgData = readPPG();
      
      if (ppgData.heartRateAvailable && ppgData.validHeartRate) {
        String hrData = String(ppgData.beatsPerMinute);
        EncryptedPayload hrEncrypted;
        if (encryptHeartRate(hrData, hrEncrypted)) {
          writeEncryptedValue(heartRateChar, hrEncrypted, "heart rate");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt heart rate data");
        }
      }

      if (ppgData.spo2Available && ppgData.validSPO2) {
        String spo2Data = String(ppgData.spO2, 1);
        EncryptedPayload spo2Encrypted;
        if (encryptSpO2(spo2Data, spo2Encrypted)) {
          writeEncryptedValue(spo2Char, spo2Encrypted, "SpO2");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt SpO2 data");
        }
      }

      delay(100); // ~10 Hz synchronized update rate
    }

    Serial.println("🔌 Disconnected.");
  }
}

