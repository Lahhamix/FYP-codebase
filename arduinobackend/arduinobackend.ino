#include <ArduinoBLE.h>
#include <string.h>
#include "imu_reader.h"
#include "ppg_reader.h"
#include "flex_reader.h"
#include "pressure_reader.h"
#include "ppg_waveform_collect.h"
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

// PPG waveform window (625 processed samples captured in plot mode, sent in normal mode)
BLECharacteristic ppgWaveChar(
  "9a8b0008-6d5e-4c10-b6d9-1f25c09d9e00",
  BLERead | BLENotify,
  kCharacteristicSize
);

// Called when Android writes its public key - derive keys and init encryption
void onPhoneKeyWritten(BLEDevice central, BLECharacteristic characteristic) {
  const uint8_t* data = characteristic.value();
  size_t len = characteristic.valueLength();
  if (key_exchange_process_phone_key(data, len)) {
    encryption_init_from_key_exchange();
    // Write initial values so Android gets them when it enables notifications (only for enabled sensors)
    EncryptedPayload stepsInit, motionInit, heartInit, spo2Init;
#if SENSOR_IMU_ENABLED
    if (encryptSteps("0", stepsInit))
      writeEncryptedValue(stepsChar, stepsInit, "steps (init)");
    if (encryptMotion("0", motionInit))
      writeEncryptedValue(motionChar, motionInit, "motion (init)");
#endif
#if SENSOR_PPG_ENABLED
    if (encryptHeartRate("--", heartInit))
      writeEncryptedValue(heartRateChar, heartInit, "heart rate (init)");
    if (encryptSpO2("--", spo2Init))
      writeEncryptedValue(spo2Char, spo2Init, "SpO2 (init)");
#endif
#if SENSOR_FLEX_ENABLED
    EncryptedPayload edemaInit;
    if (encryptFlex("calibrating,0,0,0", edemaInit))
      writeEncryptedValue(edemaChar, edemaInit, "flex edema (init)");
#endif
#if SENSOR_PRESSURE_ENABLED
    uint8_t initPressure[kPressurePacketSize] = {0};
    initPressure[0] = 0xA5;
    initPressure[1] = 0x5A;
    pressureChar.writeValue(initPressure, kPressurePacketSize);
#endif
  }
}

// -----------------------------------------------------------------------------
// SETUP
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(2000000);
  // Don't block on Serial - Arduino must advertise even when not connected to USB
  delay(500);  // Brief delay for Serial to init (optional, for debug)
  LOG_SYSTEM(Serial.println("🔬 Initializing wearable sensors..."));
  analogReadResolution(12);

  // Seed RNG for ECDH key generation (use analog pin + time for entropy)
  randomSeed(analogRead(0) + micros());

#if SENSOR_IMU_ENABLED
  if (!imu_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize IMU!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ IMU initialized."));
#else
  LOG_SYSTEM(Serial.println("⏭ IMU disabled (SENSOR_IMU_ENABLED=0)."));
#endif

#if SENSOR_PPG_ENABLED
  if (!ppg_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize PPG!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ PPG initialized."));
#else
  LOG_SYSTEM(Serial.println("⏭ PPG disabled (SENSOR_PPG_ENABLED=0)."));
#endif

#if SENSOR_FLEX_ENABLED
  if (!flex_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize Flex sensors!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ Flex sensors initialized."));
#else
  LOG_SYSTEM(Serial.println("⏭ Flex disabled (SENSOR_FLEX_ENABLED=0)."));
#endif

#if SENSOR_PRESSURE_ENABLED
  if (!pressure_init()) {
    LOG_SYSTEM(Serial.println("❌ Failed to initialize Pressure matrix!"));
    while (1);
  }
  LOG_SYSTEM(Serial.println("✅ Pressure matrix initialized."));
#else
  LOG_SYSTEM(Serial.println("⏭ Pressure matrix disabled (SENSOR_PRESSURE_ENABLED=0)."));
#endif

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
  wearableService.addCharacteristic(ppgWaveChar);
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
#if SENSOR_IMU_ENABLED
  imu_pump_steps();
#endif
}

static bool g_plotMode = false;
static uint32_t g_nextModeSwitchMs = 0;

static void setPlotMode(bool enable) {
  if (g_plotMode == enable) return;
  g_plotMode = enable;
  if (g_plotMode) {
    ppg_set_metrics_enabled(false);
    ppg_set_plot_mode(true);
    g_nextModeSwitchMs = (uint32_t)millis() + (uint32_t)PLOT_MODE_DURATION_MS;
  } else {
    ppg_set_plot_mode(false);
    ppg_set_metrics_enabled(true);
    g_nextModeSwitchMs = (uint32_t)millis() + (uint32_t)PLOT_MODE_EVERY_MS;
  }
}

static void tickAutoPlotSchedule() {
  const uint32_t nowMs = (uint32_t)millis();
  if (g_nextModeSwitchMs == 0u) {
    // Boot: start in normal mode, schedule first entry into plot mode.
    g_nextModeSwitchMs = nowMs + (uint32_t)PLOT_MODE_EVERY_MS;
    return;
  }
  if ((uint32_t)(nowMs - g_nextModeSwitchMs) < 0x80000000u) {
    // nowMs >= g_nextModeSwitchMs (wrap-safe)
    setPlotMode(!g_plotMode);
  }
}

void loop() {
  BLE.poll();  // Process BLE events (connection, writes, etc.)
  BLEDevice central = BLE.central();

  if (central) {
    LOG_SYSTEM(Serial.print("🔗 Connected to: "));
    LOG_SYSTEM(Serial.println(central.address()));

    static bool pressureStreamingStarted = false;  // reset when disconnected (see below)
    static uint8_t ppgWaveFrameId = 0;

    while (central.connected()) {
      tickAutoPlotSchedule();
      // In plot mode, behave like ppg_waveform.ino: do not continuously service BLE.
      // (Key exchange may still complete while in normal mode; plot mode is for waveform fidelity.)
      if (!g_plotMode) {
        BLE.poll();  // Must poll to receive Android's key exchange write
      }

#if SENSOR_PPG_ENABLED && PPG_WAVEFORM_STREAM_ENABLED
      // Stable 125 Hz serial waveform stream, processed like `ppg_waveform/ppg_waveform.ino`:
      // - takes the most recent IR sample available
      // - EMA smoothing
      // - optional inversion
      static uint32_t s_waveLastUs = 0;
      static float s_waveEma = 0.0f;
      static bool s_waveEmaInit = false;
      static uint32_t s_waveLastIr = 0;
#if PPG_WAVEFORM_TEST_MODE
      static uint32_t s_waveLastReportUs = 0;
      static uint32_t s_waveOutputCount = 0;
#endif
      const uint32_t nowUs = micros();
      const uint32_t periodUs = (uint32_t)(1000000UL / (uint32_t)PPG_WAVEFORM_HZ);
      if (g_plotMode) {
        if (s_waveLastUs == 0u) {
          s_waveLastUs = nowUs;
        }
        // Catch up if loop stalled; cap catch-up work per iteration.
        uint8_t catchup = 0;
        while ((uint32_t)(nowUs - s_waveLastUs) >= periodUs && catchup < 8u) {
          s_waveLastUs += periodUs;
          catchup++;
        uint32_t irLatest = 0;
        // Fast non-blocking sample acquisition (no delay loops).
        if (ppg_waveform_acquire_latest(&irLatest) || ppg_waveform_take_latest(&irLatest)) {
          s_waveLastIr = irLatest;
        } else {
          irLatest = s_waveLastIr;
        }
        const float x = (float)irLatest;
        if (!s_waveEmaInit) {
          s_waveEma = x;
          s_waveEmaInit = true;
        } else {
          s_waveEma = PPG_WAVEFORM_EMA_ALPHA * x + (1.0f - PPG_WAVEFORM_EMA_ALPHA) * s_waveEma;
        }
        long y = (long)s_waveEma;
#if PPG_WAVEFORM_INVERT
        y = -y;
#endif
#if PPG_WAVEFORM_TEST_MODE
        s_waveOutputCount++;
#else
        Serial.println(y);
#endif

        // Collect a full 625-sample window during plot mode for later BLE TX in normal mode.
        // (Window is the exact processed signal you see in plot mode: EMA + optional invert.)
        (void)ppg_waveform_collect_push((int32_t)y);
        }
      }

#if PPG_WAVEFORM_TEST_MODE
      // Report once per second (like ppg_waveform.ino TEST_MODE).
      if (g_plotMode) {
        if (s_waveLastReportUs == 0u) {
          s_waveLastReportUs = nowUs;
        }
        const uint32_t elapsedUs = (uint32_t)(nowUs - s_waveLastReportUs);
        if (elapsedUs >= 1000000u) {
          const uint32_t sps = (uint32_t)((s_waveOutputCount * 1000000ull) / (uint64_t)elapsedUs);
          s_waveLastReportUs = nowUs;
          Serial.print(F("Samples/sec: "));
          Serial.println((unsigned long)sps);
          s_waveOutputCount = 0;
        }
      }
#endif
#endif

      // In plot mode, disable everything except PPG acquisition + waveform stream.
      if (g_plotMode) {
        // Nothing else. We already pulled samples during the waveform tick above.
        // Yield just enough for scheduler/USB without delaying.
        yield();
        continue;
      }

      // Normal mode: require key exchange/encryption ready before doing any encrypted BLE TX.
      if (!encryption_is_ready()) {
        // Chunk delay so BLE.stack keeps getting BLE.poll() while waiting for phone key write
        for (uint8_t i = 0; i < 5; i++) {
          BLE.poll();
          delay(10);
        }
        continue;
      }

#if SENSOR_PPG_ENABLED
      // PPG waveform window BLE TX (time-sliced):
      // - captured during plot mode (625 int32 samples)
      // - sent in normal mode in small encrypted chunks so it doesn't disturb loop timing
      static bool ppgWaveTxActive = false;
      static int32_t ppgWaveTxBuf[PPG_WAVEFORM_COLLECT_SAMPLES];
      static uint16_t ppgWaveTxByteOffset = 0;  // 0..(625*4)
      static uint8_t ppgWaveTxTotalChunks = 0;
      static uint8_t ppgWaveTxChunkId = 0;
      static uint8_t ppgWaveTxCyclesLeft = 0;

      if (!ppgWaveTxActive && ppg_waveform_collect_has_window()) {
        if (ppg_waveform_collect_take(ppgWaveTxBuf)) {
          ppgWaveTxActive = true;
          ppgWaveTxByteOffset = 0;
          ppgWaveTxChunkId = 0;
          ppgWaveTxCyclesLeft = (uint8_t)PPG_WAVE_REPEAT_CYCLES;
          // Plaintext format (max 64 bytes):
          // [0]='P' [1]='W' [2]=frameId [3]=chunkId [4]=totalChunks [5]=dataLen [6]=0 [7]=0 [8..]=data
          const uint16_t totalBytes = (uint16_t)(PPG_WAVEFORM_COLLECT_SAMPLES * 4u); // 2500
          const uint8_t dataMax = (uint8_t)((uint16_t)PPG_WAVE_SAMPLES_PER_CHUNK * 4u); // e.g. 5 samples -> 20 bytes
          ppgWaveTxTotalChunks = (uint8_t)((totalBytes + (dataMax - 1u)) / dataMax);
          ppgWaveFrameId++;
        }
      }

      if (ppgWaveTxActive) {
        uint8_t sent = 0;
        const uint16_t totalBytes = (uint16_t)(PPG_WAVEFORM_COLLECT_SAMPLES * 4u);
        const uint8_t dataMax = (uint8_t)((uint16_t)PPG_WAVE_SAMPLES_PER_CHUNK * 4u);
        const uint8_t* bytes = (const uint8_t*)ppgWaveTxBuf;

        while (ppgWaveTxByteOffset < totalBytes && sent < (uint8_t)PPG_WAVE_BLE_PACKETS_PER_STEP) {
          const uint16_t remaining = (uint16_t)(totalBytes - ppgWaveTxByteOffset);
          const uint8_t dataLen = (remaining >= dataMax) ? dataMax : (uint8_t)remaining;

          uint8_t plain[64] = {0};
          plain[0] = (uint8_t)'P';
          plain[1] = (uint8_t)'W';
          plain[2] = ppgWaveFrameId;
          plain[3] = ppgWaveTxChunkId;
          plain[4] = ppgWaveTxTotalChunks;
          plain[5] = dataLen;
          memcpy(&plain[8], &bytes[ppgWaveTxByteOffset], dataLen);

          EncryptedPayload enc = {};
          if (encryptPpgWaveChunk(plain, (size_t)(8u + dataLen), enc)) {
            writeEncryptedValue(ppgWaveChar, enc, "ppg_wave");
          }

          ppgWaveTxByteOffset = (uint16_t)(ppgWaveTxByteOffset + (uint16_t)dataLen);
          ppgWaveTxChunkId++;
          sent++;
        }

        if (ppgWaveTxByteOffset >= totalBytes) {
          // One full cycle finished. Repeat the SAME frame again to fill in any missed chunks on Android.
          if (ppgWaveTxCyclesLeft > 1u) {
            ppgWaveTxCyclesLeft--;
            ppgWaveTxByteOffset = 0;
            ppgWaveTxChunkId = 0;
          } else {
            ppgWaveTxActive = false;
          }
        }
      }
#endif

#if SENSOR_IMU_ENABLED
      static uint32_t s_lastStepsTxMs = 0;
      static uint32_t s_lastMotionTxMs = 0;
#endif
#if SENSOR_PPG_ENABLED
      static uint32_t s_lastHrTxMs = 0;
      static uint32_t s_lastSpo2TxMs = 0;
#endif
#if SENSOR_FLEX_ENABLED
      static uint32_t s_lastFlexTxMs = 0;
#endif

#if LOG_ENABLED && LOG_LOOP_TIMING_ENABLED
      const uint32_t loop_iter_t0 = micros();
#endif

      // PPG first: MAX30102 FIFO is ~32 samples @ 100 Hz; long IMU drains + crypto delayed ppg_tick and caused -999.
      // Flex → IMU after PPG. Pressure scan/TX still comes after (matrix + BLE burst).
#if SENSOR_PPG_ENABLED
      PPGData ppgData = ppg_tick();
#else
      PPGData ppgData = {};
#endif

#if SENSOR_FLEX_ENABLED
      // Flex read is relatively expensive (ADC + filtering). Gate it after calibration to keep
      // loop time available for pressure + waveform streaming. During calibration, keep reading
      // at the module's internal rate so it can converge.
      static uint32_t s_lastFlexReadMs = 0;
      static FlexData s_lastFlex = {};
      FlexData flexData = {};
      const uint32_t nowFlexMs = (uint32_t)millis();
      const bool flexNeedsCal = !flex_isCalibrated();
      if (flexNeedsCal || (uint32_t)(nowFlexMs - s_lastFlexReadMs) >= (uint32_t)SENSOR_FLEX_READ_MS) {
        s_lastFlexReadMs = nowFlexMs;
        s_lastFlex = readFlex();
      }
      flexData = s_lastFlex;
#else
      FlexData flexData = {};
#endif

#if SENSOR_IMU_ENABLED
      IMUData imuData = readIMU();
#else
      IMUData imuData = {};
#endif

#if SENSOR_PRESSURE_ENABLED
      // Non-blocking pressure scan: do a few columns per loop.
      static bool pressureScanStarted = false;
      if (!pressureScanStarted) {
        pressure_scan_begin();
        pressureScanStarted = true;
      }
      pressure_scan_step(PRESSURE_SCAN_COLS_PER_STEP, bleAndImuYield);

      PressureFrame pressureFrame = {};
      pressureFrame.available = pressure_take_frame(&pressureFrame);
#else
      PressureFrame pressureFrame = {};
      pressureFrame.available = false;
#endif

#if SENSOR_IMU_ENABLED
      // Steps + motion: stream every loop tick (do NOT gate on imuData.available).
      {
        const uint32_t nowMs = millis();
        if (BLE_PUBLISH_STEPS_MS == 0u || (uint32_t)(nowMs - s_lastStepsTxMs) >= (uint32_t)BLE_PUBLISH_STEPS_MS) {
          s_lastStepsTxMs = nowMs;
          String stepsData = String(imuData.stepCount);
          EncryptedPayload stepsEncrypted;
          if (encryptSteps(stepsData, stepsEncrypted)) {
            writeEncryptedValue(stepsChar, stepsEncrypted, "steps");
          } else {
            LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt steps data"));
          }
        }

        if (BLE_PUBLISH_MOTION_MS == 0u || (uint32_t)(nowMs - s_lastMotionTxMs) >= (uint32_t)BLE_PUBLISH_MOTION_MS) {
          s_lastMotionTxMs = nowMs;
          String motionData = imuData.isMoving ? "1" : "0";
          EncryptedPayload motionEncrypted;
          if (encryptMotion(motionData, motionEncrypted)) {
            writeEncryptedValue(motionChar, motionEncrypted, "motion");
          } else {
            LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt motion data"));
          }
        }
      }
#endif

      // Serial IMU logs (optional; only when we have a fresh IMU frame)
#if SENSOR_IMU_ENABLED
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
#endif

#if SENSOR_PPG_ENABLED
      // Match MAX30102_by_RF reference behavior: only publish when BOTH HR and SpO2 are valid.
      if (ppgData.heartRateAvailable && ppgData.spo2Available && ppgData.validHeartRate && ppgData.validSPO2) {
        const uint32_t nowMs = millis();
        {
          if (BLE_PUBLISH_HR_MS == 0u || (uint32_t)(nowMs - s_lastHrTxMs) >= (uint32_t)BLE_PUBLISH_HR_MS) {
            s_lastHrTxMs = nowMs;
            String hrData = String(ppgData.beatsPerMinute);
            EncryptedPayload hrEncrypted;
            if (encryptHeartRate(hrData, hrEncrypted)) {
              writeEncryptedValue(heartRateChar, hrEncrypted, "heart rate");
            } else {
              LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt heart rate data"));
            }
          }
        }

        {
          if (BLE_PUBLISH_SPO2_MS == 0u || (uint32_t)(nowMs - s_lastSpo2TxMs) >= (uint32_t)BLE_PUBLISH_SPO2_MS) {
            s_lastSpo2TxMs = nowMs;
            String spo2Data = String(ppgData.spO2, 1);
            EncryptedPayload spo2Encrypted;
            if (encryptSpO2(spo2Data, spo2Encrypted)) {
              writeEncryptedValue(spo2Char, spo2Encrypted, "SpO2");
            } else {
              LOG_ENCRYPT(Serial.println("[ENCRYPTION] Failed to encrypt SpO2 data"));
            }
          }
        }
      }
#endif

#if SENSOR_FLEX_ENABLED
      // Flex edema
      if (flexData.dataAvailable) {
        const uint32_t nowMs = millis();
        if (BLE_PUBLISH_FLEX_MS == 0u || (uint32_t)(nowMs - s_lastFlexTxMs) >= (uint32_t)BLE_PUBLISH_FLEX_MS) {
          s_lastFlexTxMs = nowMs;
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
      }
#endif

#if SENSOR_PRESSURE_ENABLED
      // Pressure matrix (Python-style packets, rolling buffer on phone)
      static uint16_t frameCounter = 0;
      // TX state: send a few packets per loop iteration (non-blocking).
      static bool txActive = false;
      static PressureFrame txFrame;
      static uint16_t txStartIndex = 0;

      if (!txActive && pressureFrame.available) {
        txFrame = pressureFrame;
        txStartIndex = 0;
        txActive = true;
      }

      if (txActive) {
        if (!pressureStreamingStarted) {
          LOG_PRESSURE(Serial.println(F("[PRESSURE] Streaming started (encrypted packets, Python-style headers).")));
          pressureStreamingStarted = true;
        }
        // Min/max of raw frame (same style as Android Logcat stats)
        uint16_t pMin = 0x0FFF, pMax = 0;
        for (int i = 0; i < NUM_VALUES; i++) {
          uint16_t v = txFrame.data[i] & 0x0FFF;
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

        uint8_t packetsSent = 0;
        while (txStartIndex < NUM_VALUES && packetsSent < (uint8_t)PRESSURE_TX_PACKETS_PER_STEP) {
          BLE.poll();
          int remaining = NUM_VALUES - txStartIndex;
          uint8_t sampleCount = (remaining >= SAMPLES_PER_PACKET) ? SAMPLES_PER_PACKET : remaining;

          uint8_t payload[12];
          packSamples12(&txFrame.data[txStartIndex], sampleCount, payload);

          // 8-byte header (always plain)
          uint8_t header[PRESSURE_HEADER_SIZE];
          header[0] = 0xA5;
          header[1] = 0x5A;
          header[2] = (uint8_t)(frameCounter & 0xFF);
          header[3] = (uint8_t)((frameCounter >> 8) & 0xFF);
          header[4] = (uint8_t)(txStartIndex & 0xFF);
          header[5] = (uint8_t)((txStartIndex >> 8) & 0xFF);
          header[6] = sampleCount;
          header[7] = (txStartIndex == 0) ? 0x01 : ((txStartIndex + sampleCount >= NUM_VALUES) ? 0x02 : 0x00);

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
          txStartIndex += SAMPLES_PER_PACKET;
          packetsSent++;
        }

        if (txStartIndex >= NUM_VALUES) {
          txActive = false;
          frameCounter++;
        }
      }
#endif

#if SENSOR_PPG_ENABLED
      ppg_print_serial_on_new_window(ppgData);
#endif
#if LOG_SENSOR_SNAPSHOT_ENABLED
      LOG_SNAPSHOT(Serial.print(F("[SENSORS] flex=")));
#if SENSOR_FLEX_ENABLED
      LOG_SNAPSHOT(Serial.print(flexData.edemaLabel));
      LOG_SNAPSHOT(Serial.print(F(" totalDev=")));
      LOG_SNAPSHOT(Serial.print(flexData.totalDeviation));
#else
      LOG_SNAPSHOT(Serial.print(F("(off)")));
#endif
#if SENSOR_PRESSURE_ENABLED
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
#else
      LOG_SNAPSHOT(Serial.println(F(" | pressure=off")));
#endif
#endif

#if LOG_ENABLED && LOG_LOOP_TIMING_ENABLED
      LOG_LOOP_TIMING({
        static uint32_t loop_timing_count = 0;
        loop_timing_count++;
        if ((loop_timing_count % LOOP_TIMING_LOG_EVERY_N) == 0u) {
          const uint32_t work_us = micros() - loop_iter_t0;
          const uint32_t total_us = work_us + (uint32_t)MAIN_LOOP_DELAY_MS * 1000u;
          Serial.print(F("[LOOP] n="));
          Serial.print(loop_timing_count);
          Serial.print(F(" work_us="));
          Serial.print(work_us);
          Serial.print(F(" total_us="));
          Serial.print(total_us);
          Serial.print(F(" (delay_ms="));
          Serial.print((uint32_t)MAIN_LOOP_DELAY_MS);
          Serial.println(F(")"));
        }
      });
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

