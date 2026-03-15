#ifndef KEY_EXCHANGE_H
#define KEY_EXCHANGE_H

#include <Arduino.h>

// Key exchange service UUIDs - MUST match Android MainActivity.kt
#define KEY_EXCHANGE_SERVICE_UUID "9a8b1001-6d5e-4c10-b6d9-1f25c09d9e00"
#define PHONE_PUBLIC_KEY_CHAR_UUID "9a8b1002-6d5e-4c10-b6d9-1f25c09d9e00"
#define PERIPHERAL_PUBLIC_KEY_CHAR_UUID "9a8b1003-6d5e-4c10-b6d9-1f25c09d9e00"

// Key sizes
#define SHARED_SECRET_SIZE 32
#define DERIVED_KEY_SIZE 16
#define PUBLIC_KEY_UNCOMPRESSED_SIZE 65  // 04 + x(32) + y(32)
#define PUBLIC_KEY_RAW_SIZE 64           // x + y (micro-ecc format)

// Initialize key exchange: generate keypair, prepare peripheral public key
void key_exchange_init();

// Process phone's public key (65 bytes uncompressed), derive keys, return true on success
bool key_exchange_process_phone_key(const uint8_t* phonePublicKey, size_t len);

// Get peripheral public key (65 bytes uncompressed) for the read characteristic
const uint8_t* key_exchange_get_peripheral_public_key();

// Check if key exchange has completed and keys are ready
bool key_exchange_is_complete();

// Get derived keys (16 bytes each) - only valid after key_exchange_is_complete()
void key_exchange_get_accel_key(uint8_t* outKey);
void key_exchange_get_gyro_key(uint8_t* outKey);
void key_exchange_get_heart_rate_key(uint8_t* outKey);
void key_exchange_get_spo2_key(uint8_t* outKey);
void key_exchange_get_flex_key(uint8_t* outKey);
void key_exchange_get_pressure_key(uint8_t* outKey);

#endif
