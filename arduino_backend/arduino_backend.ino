#include <ArduinoBLE.h>
#include <string.h>
#include "imu_reader.h"
#include "ppg_reader.h"
#include "encryption.h"
#include "key_exchange.h"

// -----------------------------------------------------------------------------
// BLE Services and Characteristics
// -----------------------------------------------------------------------------
// Service: Wearable sensor data (encrypted)
BLEService wearableService("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00");

// Key exchange service - MUST match Android MainActivity.kt
BLEService keyExchangeService(KEY_EXCHANGE_SERVICE_UUID);
BLECharacteristic phonePublicKeyChar(PHONE_PUBLIC_KEY_CHAR_UUID, BLEWrite, 65);
BLECharacteristic peripheralPublicKeyChar(PERIPHERAL_PUBLIC_KEY_CHAR_UUID, BLERead, 65);

// Each characteristic reserves 1 byte for length + 80 bytes ciphertext
static const int kCharacteristicSize = 81;

BLECharacteristic accelChar(
  "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

BLECharacteristic gyroChar(
  "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

BLECharacteristic heartRateChar(
  "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

BLECharacteristic spo2Char(
  "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Called when Android writes its public key - derive keys and init encryption
void onPhoneKeyWritten(BLEDevice central, BLECharacteristic characteristic) {
  const uint8_t* data = characteristic.value();
  size_t len = characteristic.valueLength();
  if (key_exchange_process_phone_key(data, len)) {
    encryption_init_from_key_exchange();
    // Write initial values so Android gets them when it enables notifications
    EncryptedPayload accelInit, gyroInit, heartInit, spo2Init;
    if (encryptAccel("0.00,0.00,0.00", accelInit))
      writeEncryptedValue(accelChar, accelInit, "accelerometer (init)");
    if (encryptGyro("0.00,0.00,0.00", gyroInit))
      writeEncryptedValue(gyroChar, gyroInit, "gyroscope (init)");
    if (encryptHeartRate("--", heartInit))
      writeEncryptedValue(heartRateChar, heartInit, "heart rate (init)");
    if (encryptSpO2("--", spo2Init))
      writeEncryptedValue(spo2Char, spo2Init, "SpO2 (init)");
  }
}

// -----------------------------------------------------------------------------
// SETUP
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(9600);
  // Don't block on Serial - Arduino must advertise even when not connected to USB
  delay(500);  // Brief delay for Serial to init (optional, for debug)
  Serial.println("🔬 Initializing wearable sensors...");

  // Seed RNG for ECDH key generation (use analog pin + time for entropy)
  randomSeed(analogRead(0) + micros());

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
    while (1);
  }

  // Key exchange: generate keypair, prepare peripheral public key
  key_exchange_init();

  BLE.setLocalName("SoleMate");
  BLE.setDeviceName("SoleMate");
  BLE.setAdvertisedService(wearableService);

  // Key exchange service (Android must complete this before sensor data)
  phonePublicKeyChar.setEventHandler(BLEWritten, onPhoneKeyWritten);
  peripheralPublicKeyChar.writeValue(key_exchange_get_peripheral_public_key(), 65);
  keyExchangeService.addCharacteristic(phonePublicKeyChar);
  keyExchangeService.addCharacteristic(peripheralPublicKeyChar);
  BLE.addService(keyExchangeService);

  // Wearable sensor service
  wearableService.addCharacteristic(accelChar);
  wearableService.addCharacteristic(gyroChar);
  wearableService.addCharacteristic(heartRateChar);
  wearableService.addCharacteristic(spo2Char);
  BLE.addService(wearableService);

  BLE.advertise();
  Serial.println("📡 Advertising as SoleMate...");
  Serial.println("🔐 ECDH key exchange + AES-128-CBC encryption");
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
  BLE.poll();  // Process BLE events (connection, writes, etc.)
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("🔗 Connected to: ");
    Serial.println(central.address());

    while (central.connected()) {
      BLE.poll();  // Must poll to receive Android's key exchange write
      // Only stream encrypted data after key exchange is complete
      if (!encryption_is_ready()) {
        delay(50);
        continue;
      }

      // Read and stream IMU data
      IMUData imuData = readIMU();
      if (imuData.available) {
        String accelData = String(imuData.ax, 2) + "," + String(imuData.ay, 2) + "," + String(imuData.az, 2);
        String gyroData = String(imuData.gx, 2) + "," + String(imuData.gy, 2) + "," + String(imuData.gz, 2);
        EncryptedPayload accelEncrypted;
        EncryptedPayload gyroEncrypted;
        
        //Serial.print("[ACCEL] ");
        //Serial.println(accelData);
        if (encryptAccel(accelData, accelEncrypted)) {
          writeEncryptedValue(accelChar, accelEncrypted, "accelerometer");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt accelerometer data");
        }
        
        //Serial.print("[GYRO] ");
        //Serial.println(gyroData);
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

