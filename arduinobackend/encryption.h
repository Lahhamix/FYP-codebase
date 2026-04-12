#ifndef ENCRYPTION_H
#define ENCRYPTION_H

#include <Arduino.h>
#include <Crypto.h>
#include <AES.h>
#include <CBC.h>

struct EncryptedPayload {
  size_t length;
  uint8_t data[80];  // supports up to 64 bytes of ciphertext + padding
};

// Initialize encryption system with keys from key exchange.
// Must be called after key_exchange_process_phone_key() succeeds.
void encryption_init_from_key_exchange();

// Legacy: init with default keys (for backward compat, not used when key exchange is active)
void encryption_init();

// Returns true if encryption is ready (key exchange complete and keys loaded)
bool encryption_is_ready();

// Helper functions
size_t addPKCS5Padding(const uint8_t* data, size_t dataLen, uint8_t* paddedData, size_t maxLen);
bool encryptWithKeyIV(const String& plaintext, const uint8_t* key, const uint8_t* iv, EncryptedPayload& payload);

// Separate encryption functions for each data type (UUIDs 0002/0003: steps + motion, not raw IMU)
bool encryptSteps(const String& plaintext, EncryptedPayload& payload);
bool encryptMotion(const String& plaintext, EncryptedPayload& payload);
bool encryptHeartRate(const String& plaintext, EncryptedPayload& payload);
bool encryptSpO2(const String& plaintext, EncryptedPayload& payload);
bool encryptFlex(const String& plaintext, EncryptedPayload& payload);

// Binary encryption for pressure matrix packets (encrypts 12-byte payload)
bool encryptPressurePayload(const uint8_t* payload, size_t payloadLen, uint8_t* encryptedOut, size_t* encryptedLen);

#endif

