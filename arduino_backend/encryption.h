#ifndef ENCRYPTION_H
#define ENCRYPTION_H

#include <Arduino.h>
#include <Crypto.h>
#include <AES.h>
#include <CBC.h>

// Initialize encryption system
void encryption_init();

// Helper functions
size_t addPKCS5Padding(const uint8_t* data, size_t dataLen, uint8_t* paddedData, size_t maxLen);
String base64Encode(const uint8_t* data, size_t length);
String encryptWithKeyIV(const String& plaintext, const uint8_t* key, const uint8_t* iv);

// Default encryption (backward compatibility)
String encryptAES(const String& plaintext);

// Separate encryption functions for each data type
String encryptAccel(const String& plaintext);
String encryptGyro(const String& plaintext);
String encryptHeartRate(const String& plaintext);
String encryptSpO2(const String& plaintext);

#endif

