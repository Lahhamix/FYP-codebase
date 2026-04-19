#include <ArduinoBLE.h>
#include <string.h>
#include "imu_reader.h"
#include "ppg_reader.h"
#include "flex_reader.h"
#include "pressure_reader.h"
#include "encryption.h"
#include "key_exchange.h"
#include "serial_log.h"

// Pressure: ALWAYS encrypted (8 header + 16 ciphertext)
#define PRESSURE_HEADER_SIZE 8
#define PRESSURE_PAYLOAD_SIZE 12
static const int kPressurePacketSize = PRESSURE_HEADER_SIZE + 16;  // 24

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

BLECharacteristic stepsChar(
  "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

BLECharacteristic motionChar(
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
    EncryptedPayload stepsInit, motionInit, heartInit, spo2Init;
    if (encryptSteps("0", stepsInit))
      writeEncryptedValue(stepsChar, stepsInit, "steps (init)");
    if (encryptMotion("0", motionInit))
      writeEncryptedValue(motionChar, motionInit, "motion (init)");
    if (encryptHeartRate("--", heartInit))
      writeEncryptedValue(heartRateChar, heartInit, "heart rate (init)");
    if (encryptSpO2("--", spo2Init))
      writeEncryptedValue(spo2Char, spo2Init, "SpO2 (init)");
    EncryptedPayload edemaInit;
    if (encryptFlex("calibrating,0,0,0", edemaInit))
      writeEncryptedValue(edemaChar, edemaInit, "flex edema (init)");
    // Send initial pressure packet (20 bytes plain or 24 bytes with encrypted payload)
    uint8_t initPressure[kPressurePacketSize] = {0};
    initPressure[0] = 0xA5;
    initPressure[1] = 0x5A;
    // bytes 8..23 are zeros; Android treats all-zero ciphertext as init and returns 12 zeros
    pressureChar.writeValue(initPressure, kPressurePacketSize);
  }
}

// -----------------------------------------------------------------------------
// SETUP
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(9600);
  // Don't block on Serial - Arduino must advertise even when not connected to USB
  delay(500);  // Brief delay for Serial to init (optional, for debug)
  LOG_SYSTEM(Serial.println("🔬 Initializing wearable sensors..."));
  analogReadResolution(12);

  // Seed RNG for ECDH key generation (use analog pin + time for entropy)
  randomSeed(analogRead(0) + micros());

  // Initialize IMU
  if (!imu_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize IMU!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ IMU initialized."));

  // Initialize PPG
  if (!ppg_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize PPG!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ PPG initialized."));

  // Initialize Flex sensors
  if (!flex_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize Flex sensors!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ Flex sensors initialized."));

  // Initialize Pressure matrix
  if (!pressure_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize Pressure matrix!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ Pressure matrix initialized."));

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
  wearableService.addCharacteristic(stepsChar);
  wearableService.addCharacteristic(motionChar);
  wearableService.addCharacteristic(heartRateChar);
  wearableService.addCharacteristic(spo2Char);
  wearableService.addCharacteristic(edemaChar);
  wearableService.addCharacteristic(pressureChar);
  BLE.addService(wearableService);

  BLE.advertise();
  LOG_SYSTEM(Serial.println("📡 Advertising as SoleMate..."));
  LOG_SYSTEM(Serial.println("🔐 ECDH key exchange + AES-128-CBC encryption"));
}

// -----------------------------------------------------------------------------
// LOOP - Synchronized data streaming
// -----------------------------------------------------------------------------

void writeEncryptedValue(BLECharacteristic& characteristic, const EncryptedPayload& payload, const char* label) {
  if (payload.length == 0 || payload.length > sizeof(payload.data)) {
    LOG_ENCRYPT(Serial.print("[ENCRYPTION] Invalid payload length for "));
    LOG_ENCRYPT(Serial.println(label));
    return;
  }

  uint8_t framedPayload[sizeof(payload.data) + 1];
  framedPayload[0] = (uint8_t)payload.length;
  memcpy(&framedPayload[1], payload.data, payload.length);
  characteristic.writeValue(framedPayload, payload.length + 1);
}

/** Called every few columns during pressure matrix scan — must BLE.poll() or GATT/service discovery can stall. */
static void bleAndImuYield() {
  BLE.poll();
  imu_pump_steps();
}

void loop() {
  BLE.poll();  // Process BLE events (connection, writes, etc.)
  BLEDevice central = BLE.central();

  if (central) {
    LOG_SYSTEM(Serial.print("🔗 Connected to: "));
    LOG_SYSTEM(Serial.println(central.address()));

    static bool pressureStreamingStarted = false;  // reset when disconnected (see below)

    while (central.connected()) {
      BLE.poll();  // Must poll to receive Android's key exchange write

      if (!encryption_is_ready()) {
        // Chunk delay so BLE.stack keeps getting BLE.poll() while waiting for phone key write
        for (uint8_t i = 0; i < 5; i++) {
          BLE.poll();
          delay(10);
        }
        continue;
      }

      // IMU → flex → PPG tick before pressure scan/TX so PPG samples are not delayed by a full matrix read + BLE burst
      IMUData imuData = readIMU();
      FlexData flexData = readFlex();
      PPGData ppgData = ppg_tick();
      PressureFrame pressureFrame = readPressureWithYield(bleAndImuYield);

      // Steps + motion: stream every loop tick (do NOT gate on imuData.available).
      // HR/SpO2 use PPG; IMU can be quiet for stretches — step count still updates via accel drain inside readIMU().
      {
        String stepsData = String(imuData.stepCount);
        String motionData = imuData.isMoving ? "1" : "0";
        EncryptedPayload stepsEncrypted;
        EncryptedPayload motionEncrypted;

        if (encryptSteps(stepsData, stepsEncrypted)) {
          writeEncryptedValue(stepsChar, stepsEncrypted, "steps");
        } else {
          LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt steps data"));
        }

        if (encryptMotion(motionData, motionEncrypted)) {
          writeEncryptedValue(motionChar, motionEncrypted, "motion");
        } else {
          LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt motion data"));
        }
      }

      // Serial IMU logs (optional; only when we have a fresh IMU frame)
      if (imuData.available) {
        static uint32_t lastImuLogMs = 0;
        static bool lastMoving = false;
        const uint32_t now = millis();
        const bool timeToLog = (now - lastImuLogMs) >= IMU_LOG_PERIOD_MS;
        if (timeToLog) {
          lastMoving = imuData.isMoving;
          lastImuLogMs = now;
          LOG_IMU(Serial.print(F("[IMU] motion=")));
          LOG_IMU(Serial.print(lastMoving ? F("1") : F("0")));
          LOG_IMU(Serial.print(F(" aDyn=")));
          LOG_IMU(Serial.print(imuData.aDyn, 3));
          LOG_IMU(Serial.print(F("g aMag=")));
          LOG_IMU(Serial.print(imuData.aMag, 3));
          LOG_IMU(Serial.print(F("g gMag=")));
          LOG_IMU(Serial.print(imuData.gMag, 1));
          LOG_IMU(Serial.print(F("dps steps=")));
          LOG_IMU(Serial.println(imuData.stepCount));
        }
      }

      // Match MAX30102_by_RF reference behavior: only publish when BOTH HR and SpO2 are valid.
      if (ppgData.heartRateAvailable && ppgData.spo2Available && ppgData.validHeartRate && ppgData.validSPO2) {
        {
          String hrData = String(ppgData.beatsPerMinute);
          EncryptedPayload hrEncrypted;
          if (encryptHeartRate(hrData, hrEncrypted)) {
            writeEncryptedValue(heartRateChar, hrEncrypted, "heart rate");
          } else {
            LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt heart rate data"));
          }
        }

        {
          String spo2Data = String(ppgData.spO2, 1);
          EncryptedPayload spo2Encrypted;
          if (encryptSpO2(spo2Data, spo2Encrypted)) {
            writeEncryptedValue(spo2Char, spo2Encrypted, "SpO2");
          } else {
            LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt SpO2 data"));
          }
        }
      }

      // Flex edema
      if (flexData.dataAvailable) {
        String edemaData = String(flexData.edemaLabel) + "," +
                           String(flexData.totalDeviation) + "," +
                           String(flexData.deviation1) + "," +
                           String(flexData.deviation2);
        EncryptedPayload edemaEncrypted;
        if (encryptFlex(edemaData, edemaEncrypted)) {
          writeEncryptedValue(edemaChar, edemaEncrypted, "flex edema");
        } else {
          LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt flex edema data"));
        }
      }

      // Pressure matrix (Python-style packets, rolling buffer on phone)
      static uint16_t frameCounter = 0;
      if (pressureFrame.available) {
        if (!pressureStreamingStarted) {
          LOG_PRESSURE(Serial.println(F("[PRESSURE] Streaming started (encrypted packets, Python-style headers).")));
          pressureStreamingStarted = true;
        }
        // Min/max of raw frame (same style as Android Logcat stats)
        uint16_t pMin = 0x0FFF, pMax = 0;
        for (int i = 0; i < NUM_VALUES; i++) {
          uint16_t v = pressureFrame.data[i] & 0x0FFF;
          if (v < pMin) pMin = v;
          if (v > pMax) pMax = v;
        }
        {
          static uint32_t lastPressureLogMs = 0;
          const uint32_t nowPlog = millis();
          if ((uint32_t)(nowPlog - lastPressureLogMs) >= PRESSURE_LOG_PERIOD_MS) {
            lastPressureLogMs = nowPlog;
            LOG_PRESSURE(Serial.print(F("[PRESSURE] frame raw min=")));
            LOG_PRESSURE(Serial.print(pMin));
            LOG_PRESSURE(Serial.print(F(" max=")));
            LOG_PRESSURE(Serial.println(pMax));
          }
        }

        for (uint16_t startIndex = 0; startIndex < NUM_VALUES; startIndex += SAMPLES_PER_PACKET) {
          BLE.poll();
          // Do NOT call imu_pump_steps() here — ~96 calls/frame was killing loop time. IMU is pumped during
          // readPressureWithYield + readIMU() at the top of the loop.
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

          uint8_t encryptedPayload[16];
          size_t encryptedLen = 0;
          if (encryptPressurePayload(payload, 12, encryptedPayload, &encryptedLen) && encryptedLen == 16) {
            uint8_t packet[kPressurePacketSize];
            memcpy(packet, header, PRESSURE_HEADER_SIZE);
            memcpy(&packet[PRESSURE_HEADER_SIZE], encryptedPayload, 16);
            pressureChar.writeValue(packet, kPressurePacketSize);
          }
          if (PRESSURE_PACKET_DELAY_MS > 0) {
            delay(PRESSURE_PACKET_DELAY_MS);
          }
        }
        frameCounter++;
      } else {
        LOG_PRESSURE(Serial.println("[PRESSURE] ⚠️ No frame (read not available)."));
      }

      // Serial: PPG window dump + optional combined snapshot (after all sensors this iteration)
      ppg_print_serial_on_new_window(ppgData);
#if LOG_SENSOR_SNAPSHOT_ENABLED
      LOG_SNAPSHOT(Serial.print(F("[SENSORS] flex=")));
      LOG_SNAPSHOT(Serial.print(flexData.edemaLabel));
      LOG_SNAPSHOT(Serial.print(F(" totalDev=")));
      LOG_SNAPSHOT(Serial.print(flexData.totalDeviation));
      if (pressureFrame.available) {
        uint16_t pMin = 0x0FFF, pMax = 0;
        for (int i = 0; i < NUM_VALUES; i++) {
          uint16_t v = pressureFrame.data[i] & 0x0FFF;
          if (v < pMin) pMin = v;
          if (v > pMax) pMax = v;
        }
        LOG_SNAPSHOT(Serial.print(F(" | pressure raw min=")));
        LOG_SNAPSHOT(Serial.print(pMin));
        LOG_SNAPSHOT(Serial.print(F(" max=")));
        LOG_SNAPSHOT(Serial.println(pMax));
      } else {
        LOG_SNAPSHOT(Serial.println(F(" | pressure=no_frame")));
      }
#endif

      delay(MAIN_LOOP_DELAY_MS); // tune in serial_log.h; PPG drains FIFO (PPG_MAX_SAMPLES_PER_LOOP) per tick
    }

    pressureStreamingStarted = false;  // so next connection prints "Streaming started" again
    LOG_SYSTEM(Serial.println("🔌 Disconnected."));
    // Ensure peripheral resumes advertising after central disconnects (stack-dependent).
    BLE.advertise();
  } else {
    // No BLE: do not scan pressure or print pressure (same as other sensors — only active when connected).
    delay(MAIN_LOOP_DELAY_MS);
  }
}

