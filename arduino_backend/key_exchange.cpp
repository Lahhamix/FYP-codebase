/**
 * Key Exchange Module - ECDH secp256r1 + KDF
 * Matches Android: KeyExchangeManager.kt + AESCrypto.kt deriveKey()
 *
 * REQUIRED: Install "micro-ecc" library via Arduino Library Manager
 * REQUIRED: Arduino Cryptography Library (Crypto) - usually bundled with AES
 */

#include "key_exchange.h"
#include <string.h>

// uECC for ECDH - built into Arduino Nano 33 BLE mbed core (secp256r1)
#include <uECC.h>

// SHA256 for KDF - from Arduino Cryptography Library (same as AES/CBC)
#include <SHA256.h>

// Purpose strings - MUST match AESCrypto.kt exactly
static const char PURPOSE_HEART_RATE[] = "HEART_RATE";
static const char PURPOSE_SPO2[] = "SPO2";
static const char PURPOSE_ACCEL[] = "ACCEL";
static const char PURPOSE_GYRO[] = "GYRO";
static const char PURPOSE_FLEX[] = "FLEX";

static uint8_t s_sharedSecret[SHARED_SECRET_SIZE];
static uint8_t s_accelKey[DERIVED_KEY_SIZE];
static uint8_t s_gyroKey[DERIVED_KEY_SIZE];
static uint8_t s_heartRateKey[DERIVED_KEY_SIZE];
static uint8_t s_spo2Key[DERIVED_KEY_SIZE];
static uint8_t s_flexKey[DERIVED_KEY_SIZE];

static uint8_t s_peripheralPublicKey[PUBLIC_KEY_UNCOMPRESSED_SIZE];
static uint8_t s_privateKey[32];
static bool s_keyExchangeComplete = false;

// RNG for micro-ecc - uses Arduino random(), seed with analogRead for entropy
static int rng_func(uint8_t* dest, unsigned size) {
  for (unsigned i = 0; i < size; i++) {
    dest[i] = (uint8_t)(random(256));
  }
  return 1;
}

// KDF: SHA256(sharedSecret || purpose) -> first 16 bytes (matches Android AESCrypto.deriveKey)
static void deriveKey(const uint8_t* sharedSecret, size_t secretLen,
                     const char* purpose, uint8_t* outKey) {
  SHA256 sha256;
  sha256.reset();
  sha256.update(sharedSecret, secretLen);
  sha256.update((const uint8_t*)purpose, strlen(purpose));
  uint8_t hash[32];
  sha256.finalize(hash, 32);
  memcpy(outKey, hash, DERIVED_KEY_SIZE);
}

void key_exchange_init() {
  s_keyExchangeComplete = false;
  memset(s_sharedSecret, 0, sizeof(s_sharedSecret));
  memset(s_peripheralPublicKey, 0, sizeof(s_peripheralPublicKey));

  uECC_set_rng(rng_func);

  uint8_t publicKeyRaw[PUBLIC_KEY_RAW_SIZE];

  if (uECC_make_key(publicKeyRaw, s_privateKey) != 1) {
    Serial.println("[KEY_EXCHANGE] Failed to generate keypair");
    return;
  }

  // Convert to uncompressed format (04 || x || y) for Android
  s_peripheralPublicKey[0] = 0x04;
  memcpy(&s_peripheralPublicKey[1], publicKeyRaw, PUBLIC_KEY_RAW_SIZE);
}

bool key_exchange_process_phone_key(const uint8_t* phonePublicKey, size_t len) {
  if (len != PUBLIC_KEY_UNCOMPRESSED_SIZE) {
    Serial.print("[KEY_EXCHANGE] Invalid phone key length: ");
    Serial.println(len);
    return false;
  }

  // Android sends uncompressed: 04 || x || y; uECC expects raw 64 bytes (x || y)
  const uint8_t* phoneKeyRaw = &phonePublicKey[1];

  if (uECC_shared_secret(phoneKeyRaw, s_privateKey, s_sharedSecret) != 1) {
    Serial.println("[KEY_EXCHANGE] Failed to compute shared secret");
    return false;
  }

  // Derive keys using same KDF as Android
  deriveKey(s_sharedSecret, SHARED_SECRET_SIZE, PURPOSE_ACCEL, s_accelKey);
  deriveKey(s_sharedSecret, SHARED_SECRET_SIZE, PURPOSE_GYRO, s_gyroKey);
  deriveKey(s_sharedSecret, SHARED_SECRET_SIZE, PURPOSE_HEART_RATE, s_heartRateKey);
  deriveKey(s_sharedSecret, SHARED_SECRET_SIZE, PURPOSE_SPO2, s_spo2Key);
  deriveKey(s_sharedSecret, SHARED_SECRET_SIZE, PURPOSE_FLEX, s_flexKey);

  s_keyExchangeComplete = true;
  Serial.println("[KEY_EXCHANGE] Key derivation complete.");
  return true;
}

const uint8_t* key_exchange_get_peripheral_public_key() {
  return s_peripheralPublicKey;
}

bool key_exchange_is_complete() {
  return s_keyExchangeComplete;
}

void key_exchange_get_accel_key(uint8_t* outKey) {
  memcpy(outKey, s_accelKey, DERIVED_KEY_SIZE);
}

void key_exchange_get_gyro_key(uint8_t* outKey) {
  memcpy(outKey, s_gyroKey, DERIVED_KEY_SIZE);
}

void key_exchange_get_heart_rate_key(uint8_t* outKey) {
  memcpy(outKey, s_heartRateKey, DERIVED_KEY_SIZE);
}

void key_exchange_get_spo2_key(uint8_t* outKey) {
  memcpy(outKey, s_spo2Key, DERIVED_KEY_SIZE);
}

void key_exchange_get_flex_key(uint8_t* outKey) {
  memcpy(outKey, s_flexKey, DERIVED_KEY_SIZE);
}
