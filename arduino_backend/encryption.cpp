#include "encryption.h"
#include <string.h>

// Separate keys for each data type
const uint8_t accelKey[16] = {
  0x41, 0x63, 0x63, 0x65, 0x6C, 0x4B, 0x65, 0x79,
  0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38
};

const uint8_t gyroKey[16] = {
  0x47, 0x79, 0x72, 0x6F, 0x4B, 0x65, 0x79, 0x31,
  0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39
};

const uint8_t heartRateKey[16] = {
  0x48, 0x65, 0x61, 0x72, 0x74, 0x52, 0x61, 0x74,
  0x65, 0x4B, 0x65, 0x79, 0x31, 0x32, 0x33, 0x34
};

const uint8_t spo2Key[16] = {
  0x53, 0x70, 0x4F, 0x32, 0x4B, 0x65, 0x79, 0x31,
  0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39
};

// Separate IVs for each data type
const uint8_t accelIV[16] = {
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
  0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
};

const uint8_t gyroIV[16] = {
  0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
  0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
};

const uint8_t heartRateIV[16] = {
  0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
  0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F
};

const uint8_t spo2IV[16] = {
  0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
  0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F
};

// Default key/IV for backward compatibility
const uint8_t aesKey[16] = {
  0x53, 0x69, 0x6D, 0x70, 0x6C, 0x65, 0x4B, 0x65,
  0x79, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37
};

const uint8_t aesIV[16] = {
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
  0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
};

AES128 aes128;
CBC<AES128> cbc;

void encryption_init() {
  cbc.setKey(aesKey, 16);
  cbc.setIV(aesIV, 16);
}

size_t addPKCS5Padding(const uint8_t* data, size_t dataLen, uint8_t* paddedData, size_t maxLen) {
  // Validate input
  if (dataLen == 0 || dataLen > maxLen - 16) {
    return 0; // Error: invalid length
  }
  
  uint8_t padValue = 16 - (dataLen % 16);
  if (padValue == 0) padValue = 16;
  
  // Check if we have enough space
  if (dataLen + padValue > maxLen) {
    return 0; // Error: not enough space
  }
  
  memcpy(paddedData, data, dataLen);
  for (size_t i = dataLen; i < dataLen + padValue; i++) {
    paddedData[i] = padValue;
  }
  return dataLen + padValue;
}

// Base64 encoding function
String base64Encode(const uint8_t* data, size_t length) {
  const char base64_chars[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  size_t base64Len = ((length + 2) / 3) * 4;
  char base64Output[base64Len + 1];
  
  int i = 0, j = 0;
  uint8_t char_array_3[3];
  uint8_t char_array_4[4];
  
  // Process all complete 3-byte groups
  for (size_t idx = 0; idx < length; idx++) {
    char_array_3[i++] = data[idx];
    if (i == 3) {
      char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
      char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
      char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
      char_array_4[3] = char_array_3[2] & 0x3f;
      
      for (i = 0; i < 4; i++) {
        base64Output[j++] = base64_chars[char_array_4[i]];
      }
      i = 0;
    }
  }
  
  // Handle remaining bytes (1 or 2 bytes)
  if (i > 0) {
    // Zero out remaining bytes
    for (int k = i; k < 3; k++) {
      char_array_3[k] = 0;
    }
    
    // Encode the remaining bytes
    char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
    char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
    char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
    char_array_4[3] = char_array_3[2] & 0x3f;
    
    // Add encoded bytes
    for (int k = 0; k < i + 1; k++) {
      base64Output[j++] = base64_chars[char_array_4[k]];
    }
    
    // Add padding
    while (i++ < 3) {
      base64Output[j++] = '=';
    }
  }
  base64Output[j] = '\0';
  
  return String(base64Output);
}

String encryptAES(const String& plaintext) {
  return encryptWithKeyIV(plaintext, aesKey, aesIV);
}

// Helper function to encrypt with specific key and IV
String encryptWithKeyIV(const String& plaintext, const uint8_t* key, const uint8_t* iv) {
  // Validate input
  if (plaintext.length() == 0) {
    Serial.println("ERROR: Empty plaintext");
    return "ERROR";
  }
  
  size_t plaintextLen = plaintext.length();
  
  // Use fixed buffer to prevent stack overflow
  const size_t MAX_PLAINTEXT = 64;  // Reasonable limit for sensor data
  if (plaintextLen > MAX_PLAINTEXT) {
    Serial.println("ERROR: Plaintext too long");
    return "ERROR";
  }
  
  // Convert String to bytes (without null terminator)
  uint8_t plaintextBytes[MAX_PLAINTEXT];
  for (size_t i = 0; i < plaintextLen; i++) {
    plaintextBytes[i] = (uint8_t)plaintext.charAt(i);
  }
  
  // Calculate padded length (must be multiple of 16 for AES-128-CBC)
  size_t paddedLen = ((plaintextLen / 16) + 1) * 16;
  uint8_t paddedData[MAX_PLAINTEXT + 16];  // Extra space for padding
  
  // Add PKCS5 padding
  size_t actualPaddedLen = addPKCS5Padding(plaintextBytes, plaintextLen, paddedData, MAX_PLAINTEXT + 16);
  if (actualPaddedLen == 0) {
    Serial.println("ERROR: Padding failed");
    return "ERROR";
  }
  
  // Encrypt the padded data
  uint8_t encryptedData[MAX_PLAINTEXT + 16];
  cbc.setKey(key, 16);
  cbc.setIV(iv, 16);
  cbc.encrypt(encryptedData, paddedData, actualPaddedLen);
  
  // Base64 encode the encrypted data
  String result = base64Encode(encryptedData, actualPaddedLen);
  
  // Validate result
  if (result.length() == 0) {
    Serial.println("ERROR: Base64 encoding failed");
    return "ERROR";
  }
  
  return result;
}

// Separate encryption functions for each data type
String encryptAccel(const String& plaintext) {
  return encryptWithKeyIV(plaintext, accelKey, accelIV);
}

String encryptGyro(const String& plaintext) {
  return encryptWithKeyIV(plaintext, gyroKey, gyroIV);
}

String encryptHeartRate(const String& plaintext) {
  return encryptWithKeyIV(plaintext, heartRateKey, heartRateIV);
}

String encryptSpO2(const String& plaintext) {
  return encryptWithKeyIV(plaintext, spo2Key, spo2IV);
}

