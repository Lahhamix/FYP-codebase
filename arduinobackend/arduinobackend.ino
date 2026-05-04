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

// Pressure: PHBLE-compatible plain BLE packet (8 header + 12 packed 12-bit samples)
#define PRESSURE_HEADER_SIZE 8
#define PRESSURE_PAYLOAD_SIZE 12
static const int kPressurePacketSize = PRESSURE_HEADER_SIZE + PRESSURE_PAYLOAD_SIZE;  // 20

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

// Pressure: 20-byte PHBLE-compatible plain BLE packets
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
    if (encryptFlex("calibrating", edemaInit))
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
  Serial.begin(SERIAL_BAUD);
  // Don't block on Serial - Arduino must advertise even when not connected to USB
  delay(500);  // Brief delay for Serial to init (optional, for debug)
  LOG_SYSTEM(Serial.println("Initializing wearable sensors..."));
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

static void writePressurePacket(uint16_t frameCounter, uint16_t startIndex, const uint16_t* samples, uint8_t sampleCount, uint8_t flags) {
  uint8_t payload[PRESSURE_PAYLOAD_SIZE];
  packSamples12(samples, sampleCount, payload);

  uint8_t packet[kPressurePacketSize];
  packet[0] = 0xA5;
  packet[1] = 0x5A;
  packet[2] = (uint8_t)(frameCounter & 0xFF);
  packet[3] = (uint8_t)((frameCounter >> 8) & 0xFF);
  packet[4] = (uint8_t)(startIndex & 0xFF);
  packet[5] = (uint8_t)((startIndex >> 8) & 0xFF);
  packet[6] = sampleCount;
  packet[7] = flags;
  memcpy(&packet[PRESSURE_HEADER_SIZE], payload, PRESSURE_PAYLOAD_SIZE);
  pressureChar.writeValue(packet, kPressurePacketSize);
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

#if SENSOR_PPG_ENABLED
// PPG waveform BLE chunk transmitter state (file-scope so we can reset on central disconnect).
static bool s_ppgWaveBleTxActive = false;
static int32_t s_ppgWaveTxBuf[PPG_WAVEFORM_COLLECT_SAMPLES];
static uint16_t s_ppgWaveTxByteOffset = 0;
static uint8_t s_ppgWaveTxTotalChunks = 0;
static uint8_t s_ppgWaveTxChunkId = 0;
static uint8_t s_ppgWaveTxCyclesLeft = 0;
static uint32_t s_ppgWaveLastPacketMs = 0;

static void reset_ppg_wave_ble_tx_state() {
  s_ppgWaveBleTxActive = false;
  s_ppgWaveTxByteOffset = 0;
  s_ppgWaveTxTotalChunks = 0;
  s_ppgWaveTxChunkId = 0;
  s_ppgWaveTxCyclesLeft = 0;
  s_ppgWaveLastPacketMs = 0;
  memset(s_ppgWaveTxBuf, 0, sizeof(s_ppgWaveTxBuf));
}
#endif

static void setPlotMode(bool enable) {
  if (g_plotMode == enable) return;
  g_plotMode = enable;
  if (g_plotMode) {
    // Start every plot-mode window fresh so BLE later sends the first 625 samples from this activation.
    ppg_waveform_collect_reset();
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

static void resetPlotScheduleForConnection() {
#if SENSOR_PPG_ENABLED && PPG_WAVEFORM_STREAM_ENABLED
  if (g_plotMode) {
    ppg_set_plot_mode(false);
  }
  ppg_set_metrics_enabled(true);
  ppg_waveform_collect_reset();
#endif
  g_plotMode = false;
  g_nextModeSwitchMs = (uint32_t)millis() + (uint32_t)PLOT_MODE_FIRST_DELAY_MS;
}

void loop() {
  BLE.poll();  // Process BLE events (connection, writes, etc.)
  BLEDevice central = BLE.central();

  if (central) {
    LOG_SYSTEM(Serial.print("🔗 Connected to: "));
    LOG_SYSTEM(Serial.println(central.address()));

    static bool pressureStreamingStarted = false;  // reset when disconnected (see below)
    static uint8_t ppgWaveFrameId = 0;
    resetPlotScheduleForConnection();

    while (central.connected()) {
      tickAutoPlotSchedule();
      // Keep key exchange, CCCD writes, MTU negotiation, and notifications alive in both modes.
      BLE.poll();

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
      if (!s_ppgWaveBleTxActive && ppg_waveform_collect_has_window()) {
        if (ppg_waveform_collect_take(s_ppgWaveTxBuf)) {
          s_ppgWaveBleTxActive = true;
          s_ppgWaveTxByteOffset = 0;
          s_ppgWaveTxChunkId = 0;
          s_ppgWaveTxCyclesLeft = (uint8_t)PPG_WAVE_REPEAT_CYCLES;
          s_ppgWaveLastPacketMs = 0;
          // Plaintext format (max 64 bytes):
          // [0]='P' [1]='W' [2]=frameId [3]=chunkId [4]=totalChunks [5]=dataLen [6]=0 [7]=0 [8..]=data
          const uint16_t totalBytes = (uint16_t)(PPG_WAVEFORM_COLLECT_SAMPLES * 4u); // 2500
          const uint8_t dataMax = (uint8_t)((uint16_t)PPG_WAVE_SAMPLES_PER_CHUNK * 4u);
          s_ppgWaveTxTotalChunks = (uint8_t)((totalBytes + (dataMax - 1u)) / dataMax);
          ppgWaveFrameId++;
        }
      }

      if (s_ppgWaveBleTxActive) {
        uint8_t sent = 0;
        const uint16_t totalBytes = (uint16_t)(PPG_WAVEFORM_COLLECT_SAMPLES * 4u);
        const uint8_t dataMax = (uint8_t)((uint16_t)PPG_WAVE_SAMPLES_PER_CHUNK * 4u);
        const uint8_t* bytes = (const uint8_t*)s_ppgWaveTxBuf;

        while (s_ppgWaveTxByteOffset < totalBytes && sent < (uint8_t)PPG_WAVE_BLE_PACKETS_PER_STEP) {
          const uint32_t nowTxMs = (uint32_t)millis();
          if (PPG_WAVE_PACKET_SPACING_MS > 0u && s_ppgWaveLastPacketMs != 0u &&
              (uint32_t)(nowTxMs - s_ppgWaveLastPacketMs) < (uint32_t)PPG_WAVE_PACKET_SPACING_MS) {
            break;
          }

          const uint16_t remaining = (uint16_t)(totalBytes - s_ppgWaveTxByteOffset);
          const uint8_t dataLen = (remaining >= dataMax) ? dataMax : (uint8_t)remaining;

          uint8_t plain[64] = {0};
          plain[0] = (uint8_t)'P';
          plain[1] = (uint8_t)'W';
          plain[2] = ppgWaveFrameId;
          plain[3] = s_ppgWaveTxChunkId;
          plain[4] = s_ppgWaveTxTotalChunks;
          plain[5] = dataLen;
          memcpy(&plain[8], &bytes[s_ppgWaveTxByteOffset], dataLen);

          EncryptedPayload enc = {};
          if (encryptPpgWaveChunk(plain, (size_t)(8u + dataLen), enc)) {
            writeEncryptedValue(ppgWaveChar, enc, "ppg_wave");
            s_ppgWaveLastPacketMs = (uint32_t)millis();
            BLE.poll();  // Keep ATT/notify pipeline serviced during bursty waveform TX
          }

          s_ppgWaveTxByteOffset = (uint16_t)(s_ppgWaveTxByteOffset + (uint16_t)dataLen);
          s_ppgWaveTxChunkId++;
          sent++;
        }

        if (s_ppgWaveTxByteOffset >= totalBytes) {
          // One full cycle finished. Repeat the SAME frame again to fill in any missed chunks on Android.
          if (s_ppgWaveTxCyclesLeft > 1u) {
            s_ppgWaveTxCyclesLeft--;
            s_ppgWaveTxByteOffset = 0;
            s_ppgWaveTxChunkId = 0;
            s_ppgWaveLastPacketMs = 0;
          } else {
            reset_ppg_wave_ble_tx_state();
            ppg_waveform_collect_reset();
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
      // Flex is active only while BLE is connected, like the other live sensors.
      // readFlex() returns immediately unless its configured sample interval elapsed.
      static FlexData s_lastFlex = {};
      FlexData flexData = {};
      FlexData newFlex = readFlex();
      if (newFlex.dataAvailable) {
        s_lastFlex = newFlex;
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

      PressureFrame pressureFrame = {};
      pressureFrame.available = false;

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
          // Send ONLY the label to the app (requested).
          String edemaData = String(flexData.edemaLabel);
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
      // Pressure matrix rolling stream: scan each PHBLE row and send it immediately.
      static uint16_t frameCounter = 0;
      static bool pressureScanStarted = false;
      if (!pressureScanStarted) {
        pressure_scan_begin();
        pressureScanStarted = true;
      }

      if (!pressureStreamingStarted) {
        LOG_PRESSURE(Serial.println(F("[PRESSURE] Streaming started (rolling PHBLE row packets).")));
        pressureStreamingStarted = true;
      }

      const uint8_t packetsPerRow = (uint8_t)((NUM_COLS + SAMPLES_PER_PACKET - 1) / SAMPLES_PER_PACKET);
      uint8_t maxPacketsThisLoop = (uint8_t)PRESSURE_TX_PACKETS_PER_STEP;
      if (maxPacketsThisLoop < packetsPerRow) {
        maxPacketsThisLoop = packetsPerRow;
      }
#if SENSOR_PPG_ENABLED
      if (s_ppgWaveBleTxActive && maxPacketsThisLoop > packetsPerRow) {
        maxPacketsThisLoop = packetsPerRow;
      }
#endif

      uint8_t rowsScanned = 0;
      uint8_t packetsSent = 0;
      while (rowsScanned < (uint8_t)PRESSURE_SCAN_COLS_PER_STEP &&
             (uint8_t)(packetsSent + packetsPerRow) <= maxPacketsThisLoop) {
        PressureRow row = {};
        if (!pressure_scan_next_row(&row, bleAndImuYield) || !row.available) {
          break;
        }

        for (uint8_t offset = 0; offset < NUM_COLS; offset = (uint8_t)(offset + SAMPLES_PER_PACKET)) {
          BLE.poll();
          const uint8_t remaining = (uint8_t)(NUM_COLS - offset);
          const uint8_t sampleCount = (remaining >= SAMPLES_PER_PACKET) ? SAMPLES_PER_PACKET : remaining;
          const uint16_t startIndex = (uint16_t)(row.rowIndex * NUM_COLS + offset);
          uint8_t flags = 0;
          if (row.frameStart && offset == 0) {
            flags |= 0x01;
          }
          if (row.frameEnd && (uint8_t)(offset + sampleCount) >= NUM_COLS) {
            flags |= 0x02;
          }

          writePressurePacket(frameCounter, startIndex, &row.data[offset], sampleCount, flags);
          if (PRESSURE_PACKET_DELAY_MS > 0) {
            delay(PRESSURE_PACKET_DELAY_MS);
          }
          packetsSent++;
        }

        if (row.frameEnd) {
          frameCounter++;
        }
        rowsScanned++;
      }

      pressureFrame.available = pressure_take_frame(&pressureFrame);
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
#if SENSOR_PPG_ENABLED
    reset_ppg_wave_ble_tx_state();
#endif
#if SENSOR_PPG_ENABLED && PPG_WAVEFORM_STREAM_ENABLED
    ppg_waveform_collect_reset();
#endif
    LOG_SYSTEM(Serial.println("🔌 Disconnected."));
    // Ensure peripheral resumes advertising after central disconnects (stack-dependent).
    BLE.advertise();
  } else {
    // No BLE: do not scan sensors or advance flex calibration.
    delay(MAIN_LOOP_DELAY_MS);
  }
}
