#include "encryption.h"
#include "key_exchange.h"
#include <string.h>

// IVs - MUST match AESCrypto.kt exactly (accel: 0x00..0x0F, gyro: 0x10..0x1F, etc.)
static const uint8_t accelIV[16] = {
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
  0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
};

static const uint8_t gyroIV[16] = {
  0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
  0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
};

static const uint8_t heartRateIV[16] = {
  0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
  0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F
};

static const uint8_t spo2IV[16] = {
  0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
  0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F
};

// Keys from key exchange (populated after ECDH)
static uint8_t accelKey[16];
static uint8_t gyroKey[16];
static uint8_t heartRateKey[16];
static uint8_t spo2Key[16];

static bool encryptionReady = false;

AES128 aes128;
CBC<AES128> cbc;

void encryption_init_from_key_exchange() {
  if (!key_exchange_is_complete()) {
    Serial.println("[ENCRYPTION] Key exchange not complete, cannot init.");
    return;
  }
  key_exchange_get_accel_key(accelKey);
  key_exchange_get_gyro_key(gyroKey);
  key_exchange_get_heart_rate_key(heartRateKey);
  key_exchange_get_spo2_key(spo2Key);
  encryptionReady = true;
  cbc.setKey(accelKey, 16);
  cbc.setIV(accelIV, 16);
}

void encryption_init() {
  // Legacy fallback - not used when key exchange is active
  encryptionReady = false;
}

bool encryption_is_ready() {
  return encryptionReady;
}

size_t addPKCS5Padding(const uint8_t* data, size_t dataLen, uint8_t* paddedData, size_t maxLen) {
  if (dataLen == 0 || dataLen > maxLen - 16) {
    return 0;
  }

  uint8_t padValue = 16 - (dataLen % 16);
  if (padValue == 0) padValue = 16;

  if (dataLen + padValue > maxLen) {
    return 0;
  }

  memcpy(paddedData, data, dataLen);
  for (size_t i = dataLen; i < dataLen + padValue; i++) {
    paddedData[i] = padValue;
  }
  return dataLen + padValue;
}

bool encryptWithKeyIV(const String& plaintext, const uint8_t* key, const uint8_t* iv, EncryptedPayload& payload) {
  if (!encryptionReady) {
    return false;
  }
  if (plaintext.length() == 0) {
    Serial.println("ERROR: Empty plaintext");
    return false;
  }

  size_t plaintextLen = plaintext.length();
  const size_t MAX_PLAINTEXT = 64;
  if (plaintextLen > MAX_PLAINTEXT) {
    Serial.println("ERROR: Plaintext too long");
    return false;
  }

  uint8_t plaintextBytes[MAX_PLAINTEXT];
  for (size_t i = 0; i < plaintextLen; i++) {
    plaintextBytes[i] = (uint8_t)plaintext.charAt(i);
  }

  uint8_t paddedData[MAX_PLAINTEXT + 16];
  size_t actualPaddedLen = addPKCS5Padding(plaintextBytes, plaintextLen, paddedData, MAX_PLAINTEXT + 16);
  if (actualPaddedLen == 0 || actualPaddedLen > sizeof(payload.data)) {
    Serial.println("ERROR: Padding failed or output too large");
    return false;
  }

  cbc.setKey(key, 16);
  cbc.setIV(iv, 16);
  cbc.encrypt(payload.data, paddedData, actualPaddedLen);
  payload.length = actualPaddedLen;
  return true;
}

bool encryptAccel(const String& plaintext, EncryptedPayload& payload) {
  return encryptWithKeyIV(plaintext, accelKey, accelIV, payload);
}

bool encryptGyro(const String& plaintext, EncryptedPayload& payload) {
  return encryptWithKeyIV(plaintext, gyroKey, gyroIV, payload);
}

bool encryptHeartRate(const String& plaintext, EncryptedPayload& payload) {
  return encryptWithKeyIV(plaintext, heartRateKey, heartRateIV, payload);
}

bool encryptSpO2(const String& plaintext, EncryptedPayload& payload) {
  return encryptWithKeyIV(plaintext, spo2Key, spo2IV, payload);
}

