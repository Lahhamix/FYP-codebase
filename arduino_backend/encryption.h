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

// Initialize encryption system
void encryption_init();

// Helper functions
size_t addPKCS5Padding(const uint8_t* data, size_t dataLen, uint8_t* paddedData, size_t maxLen);
bool encryptWithKeyIV(const String& plaintext, const uint8_t* key, const uint8_t* iv, EncryptedPayload& payload);

// Separate encryption functions for each data type
bool encryptAccel(const String& plaintext, EncryptedPayload& payload);
bool encryptGyro(const String& plaintext, EncryptedPayload& payload);
bool encryptHeartRate(const String& plaintext, EncryptedPayload& payload);
bool encryptSpO2(const String& plaintext, EncryptedPayload& payload);

#endif

