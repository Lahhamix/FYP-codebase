#include <ArduinoBLE.h>
#include <string.h>
#include "imu_reader.h"
#include "ppg_reader.h"
#include "flex_reader.h"
#include "pressure_reader.h"
#include "encryption.h"
#include "key_exchange.h"

// Set to 1 to enable encryption for pressure matrix (must match Android PRESSURE_MATRIX_ENCRYPTION_ENABLED)
#define PRESSURE_ENCRYPTION_ENABLED 1

// Pressure: 20-byte plain (8 header + 12 payload) or 24-byte encrypted (8 header + 16 ciphertext)
#define PRESSURE_HEADER_SIZE 8
#define PRESSURE_PAYLOAD_SIZE 12
#if PRESSURE_ENCRYPTION_ENABLED
  static const int kPressurePacketSize = PRESSURE_HEADER_SIZE + 16;  // 8 + 16 encrypted
#else
  static const int kPressurePacketSize = PRESSURE_HEADER_SIZE + PRESSURE_PAYLOAD_SIZE;  // 20
#endif

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

BLECharacteristic edemaChar(
  "9a8b0006-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Pressure: 20-byte plain or 24-byte encrypted (kPressurePacketSize set above)
BLECharacteristic pressureChar(
  "9a8b0007-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kPressurePacketSize
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
    EncryptedPayload edemaInit;
    if (encryptFlex("calibrating,0,0,0", edemaInit))
      writeEncryptedValue(edemaChar, edemaInit, "flex edema (init)");
    // Send initial pressure packet (20 bytes plain or 24 bytes with encrypted payload)
#if PRESSURE_ENCRYPTION_ENABLED
    uint8_t initPressure[kPressurePacketSize] = {0};
    initPressure[0] = 0xA5;
    initPressure[1] = 0x5A;
    // bytes 8..23 are zeros; Android treats all-zero ciphertext as init and returns 12 zeros
    pressureChar.writeValue(initPressure, kPressurePacketSize);
#else
    uint8_t initPacket[20] = {0};
    initPacket[0] = 0xA5;
    initPacket[1] = 0x5A;
    pressureChar.writeValue(initPacket, 20);
#endif
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
  analogReadResolution(12);

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

  // Initialize Flex sensors
  if (!flex_init()) {
    Serial.println("❌ Failed to initialize Flex sensors!");
    while (1);
  }
  Serial.println("✅ Flex sensors initialized.");

  // Initialize Pressure matrix
  if (!pressure_init()) {
    Serial.println("❌ Failed to initialize Pressure matrix!");
    while (1);
  }
  Serial.println("✅ Pressure matrix initialized.");

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
  wearableService.addCharacteristic(edemaChar);
  wearableService.addCharacteristic(pressureChar);
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

    static bool pressureStreamingStarted = false;  // reset when disconnected (see below)

    while (central.connected()) {
      BLE.poll();  // Must poll to receive Android's key exchange write

      // Always sample PPG so BPM/SpO2 remain visible in Serial Monitor even
      // if BLE key exchange is delayed or fails.
      PPGData ppgData = readPPG();

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

      if (ppgData.heartRateAvailable && ppgData.validHeartRate) {
        String hrData = String(ppgData.beatsPerMinute);
        EncryptedPayload hrEncrypted;
        if (encryptHeartRate(hrData, hrEncrypted)) {
          Serial.print("[PPG] Heart Rate: ");
          Serial.print(hrData);
          Serial.println(" BPM");
          writeEncryptedValue(heartRateChar, hrEncrypted, "heart rate");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt heart rate data");
        }
      }

      if (ppgData.spo2Available && ppgData.validSPO2) {
        String spo2Data = String(ppgData.spO2, 1);
        EncryptedPayload spo2Encrypted;
        if (encryptSpO2(spo2Data, spo2Encrypted)) {
          Serial.print("[PPG] SpO2: ");
          Serial.print(spo2Data);
          Serial.println(" %");
          writeEncryptedValue(spo2Char, spo2Encrypted, "SpO2");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt SpO2 data");
        }
      }

      // Read and stream Flex edema data
      FlexData flexData = readFlex();
      if (flexData.dataAvailable) {
        String edemaData = String(flexData.edemaLabel) + "," +
                           String(flexData.totalDeviation) + "," +
                           String(flexData.deviation1) + "," +
                           String(flexData.deviation2);
        EncryptedPayload edemaEncrypted;
        if (encryptFlex(edemaData, edemaEncrypted)) {
          writeEncryptedValue(edemaChar, edemaEncrypted, "flex edema");
        } else {
          Serial.println("[ENCRYPTION] Failed to encrypt flex edema data");
        }
      }

      // Read and stream Pressure matrix (Python-style: 20-byte packets, rolling buffer on phone)
      static uint16_t frameCounter = 0;
      PressureFrame pressureFrame = readPressure();
      if (pressureFrame.available) {
        if (!pressureStreamingStarted) {
          //Serial.println("[PRESSURE] 📤 Streaming started (20-byte packets, Python-style).");
          pressureStreamingStarted = true;
        }
        // Min/max of raw frame (same style as Android Logcat stats)
        uint16_t pMin = 0x0FFF, pMax = 0;
        for (int i = 0; i < NUM_VALUES; i++) {
          uint16_t v = pressureFrame.data[i] & 0x0FFF;
          if (v < pMin) pMin = v;
          if (v > pMax) pMax = v;
        }
        //Serial.print("[PRESSURE] frame raw min=");
        //Serial.print(pMin);
        //Serial.print(" max=");
        //Serial.println(pMax);

        for (uint16_t startIndex = 0; startIndex < NUM_VALUES; startIndex += SAMPLES_PER_PACKET) {
          BLE.poll();
          int remaining = NUM_VALUES - startIndex;
          uint8_t sampleCount = (remaining >= SAMPLES_PER_PACKET) ? SAMPLES_PER_PACKET : remaining;

          uint8_t payload[12];
          packSamples12(&pressureFrame.data[startIndex], sampleCount, payload);

          // 8-byte header (always plain)
          uint8_t header[PRESSURE_HEADER_SIZE];
          header[0] = 0xA5;
          header[1] = 0x5A;
          header[2] = (uint8_t)(frameCounter & 0xFF);
          header[3] = (uint8_t)((frameCounter >> 8) & 0xFF);
          header[4] = (uint8_t)(startIndex & 0xFF);
          header[5] = (uint8_t)((startIndex >> 8) & 0xFF);
          header[6] = sampleCount;
          header[7] = (startIndex == 0) ? 0x01 : ((startIndex + sampleCount >= NUM_VALUES) ? 0x02 : 0x00);

#if PRESSURE_ENCRYPTION_ENABLED
          uint8_t encryptedPayload[16];
          size_t encryptedLen = 0;
          if (encryptPressurePayload(payload, 12, encryptedPayload, &encryptedLen) && encryptedLen == 16) {
            uint8_t packet[kPressurePacketSize];
            memcpy(packet, header, PRESSURE_HEADER_SIZE);
            memcpy(&packet[PRESSURE_HEADER_SIZE], encryptedPayload, 16);
            pressureChar.writeValue(packet, kPressurePacketSize);
          }
#else
          uint8_t packet[20];
          memcpy(packet, header, PRESSURE_HEADER_SIZE);
          memcpy(&packet[8], payload, 12);
          pressureChar.writeValue(packet, 20);
#endif
          delay(2);
        }
        frameCounter++;
      } else {
        Serial.println("[PRESSURE] ⚠️ No frame (read not available).");
      }

      delay(100); // ~10 Hz synchronized update rate
    }

    pressureStreamingStarted = false;  // so next connection prints "Streaming started" again
    Serial.println("🔌 Disconnected.");
  } else {
    // No BLE device connected: still read pressure and print same stats as when connected
    PressureFrame pressureFrame = readPressure();
    if (pressureFrame.available) {
      uint16_t pMin = 0x0FFF, pMax = 0;
      for (int i = 0; i < NUM_VALUES; i++) {
        uint16_t v = pressureFrame.data[i] & 0x0FFF;
        if (v < pMin) pMin = v;
        if (v > pMax) pMax = v;
      }
      Serial.print("[PRESSURE] frame raw min=");
      Serial.print(pMin);
      Serial.print(" max=");
      Serial.println(pMax);
    }
    delay(100);
  }
}

