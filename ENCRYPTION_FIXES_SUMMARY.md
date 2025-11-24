# Encryption and Display Fixes - Summary

## Overview
This document summarizes all the fixes made to resolve encryption/decryption issues and improve data display across both Arduino and Android platforms.

---

## 🔧 Arduino Backend Fixes

### 1. **encryption.cpp - Code Refactoring**

#### **Problem:**
- Duplicate Base64 encoding code in `encryptAES()` and `encryptWithKeyIV()`
- No error handling for buffer overflows
- Variable-length arrays on stack (potential overflow risk)
- No validation of encryption success

#### **Solution:**
- Created reusable `base64Encode()` helper function
- Made `encryptAES()` simply call `encryptWithKeyIV()` (DRY principle)
- Added fixed buffer sizes (MAX_PLAINTEXT = 64 bytes) to prevent stack overflow
- Added comprehensive validation:
  - Empty plaintext check
  - Length validation
  - Padding validation
  - Base64 encoding validation
- Updated `addPKCS5Padding()` to include buffer size validation

#### **Key Changes:**
```cpp
// Before: Variable-length arrays (unsafe)
uint8_t plaintextBytes[plaintextLen];  
uint8_t paddedData[paddedLen];

// After: Fixed buffers with validation
const size_t MAX_PLAINTEXT = 64;
uint8_t plaintextBytes[MAX_PLAINTEXT];
if (plaintextLen > MAX_PLAINTEXT) {
    Serial.println("ERROR: Plaintext too long");
    return "ERROR";
}
```

### 2. **encryption.h - Updated Interface**

#### **Changes:**
- Added function declarations for new helper functions:
  - `base64Encode()`
  - `encryptWithKeyIV()`
  - Updated `addPKCS5Padding()` signature

---

## 📱 Android App Fixes

### 3. **WelcomeActivity.kt - Removed Non-Existent Function Call**

#### **Problem:**
- Called `AESCrypto.runKnownVectorSelfTest()` which doesn't exist
- Would cause crash on app startup

#### **Solution:**
- Removed the non-existent function call
- Added `AESCrypto.testKeysMatch()` for debugging encryption keys

### 4. **activity_main.xml - Added Missing UI Elements**

#### **Problem:**
- Arduino sends 4 data types (Accel, Gyro, Heart Rate, SpO2)
- Android UI only displayed 2 (Accel, Gyro)
- Heart Rate and SpO2 data were received but not shown

#### **Solution:**
Added two new MaterialCardView sections:

**Heart Rate Card:**
- ID: `heartRateText`
- Color: Pink (#E91E63)
- Icon: Recent history
- Format: "BPM: XX.X | Avg: XX | Min Avg: XX | HR Avg: XX"

**SpO2 Card:**
- ID: `spo2Text`
- Color: Blue (#2196F3)
- Icon: Info dialog
- Format: "Estimated SpO2: XX.X% | SpO2: XX.X%"

Also added section title "PPG Readings" to separate IMU from PPG sensors.

### 5. **MainActivity.kt - Complete Data Display Implementation**

#### **Changes:**

**a) Added TextView declarations:**
```kotlin
private lateinit var heartRateText: TextView
private lateinit var spo2Text: TextView
```

**b) Initialized TextViews in onCreate():**
```kotlin
heartRateText = findViewById(R.id.heartRateText)
spo2Text = findViewById(R.id.spo2Text)
```

**c) Added formatting functions:**
- `formatHeartRateData()` - Formats heart rate data with BPM and averages
- `formatSpO2Data()` - Formats SpO2 percentages

**d) Updated onServicesDiscovered():**
- Added heart rate and SpO2 characteristics to notification queue
- Added logging for each characteristic registration

**e) Updated onCharacteristicChanged():**
- Added proper handling for heart rate data
- Added proper handling for SpO2 data
- Added detailed logging for all data types

### 6. **AESCrypto.kt - Enhanced Error Handling**

#### **Improvements:**

**a) Input Validation:**
- Key size validation (must be 16 bytes)
- IV size validation (must be 16 bytes)
- Base64 format validation
- Minimum length check (24 chars = 16 bytes encrypted)
- Block size validation (must be multiple of 16)

**b) Better Error Messages:**
```kotlin
if (key.size != 16) {
    Log.e(TAG, "Invalid key size: ${key.size}, expected 16")
    return "DECRYPT_ERROR"
}
```

**c) Exception Handling:**
- Specific catches for `BadPaddingException` (wrong key/IV)
- Specific catches for `IllegalBlockSizeException` (corrupted data)
- Detailed logging for all error cases

**d) Success Validation:**
```kotlin
Log.d(TAG, "✅ Decryption successful: $trimmedResult")
```

**e) Added testKeysMatch():**
- Logs all encryption keys and IVs
- Helps verify keys match between Arduino and Android
- Called on app startup for debugging

---

## 🔍 How to Verify the Fixes

### **Step 1: Build and Upload Arduino Code**
```bash
# In Arduino IDE
1. Open arduino_backend/arduino_backend.ino
2. Verify/Compile
3. Upload to Arduino Nano 33 BLE
4. Open Serial Monitor at 9600 baud
5. Look for:
   ✅ IMU initialized.
   ✅ PPG initialized.
   ✅ Encryption initialized.
   📡 Advertising as Cura...
```

### **Step 2: Build and Run Android App**
```bash
# In Android Studio
1. Build > Make Project
2. Run > Run 'app'
3. Check Logcat for:
   === Testing Encryption Keys ===
   (Should show all keys and IVs)
```

### **Step 3: Connect and Monitor Data**
1. App: Tap "Get Started"
2. App: Should scan and find "Cura" device
3. App: Tap "Cura" to connect
4. Arduino Serial Monitor: Should show:
   ```
   🔗 Connected to: XX:XX:XX:XX:XX:XX
   [ACCEL] Value: 1.23,4.56,7.89 | Encrypted: ABC123... | Status: OK
   [GYRO] Value: 0.12,0.34,0.56 | Encrypted: DEF456... | Status: OK
   [HEART_RATE] Value: 72.0,70,69,71 | Encrypted: GHI789... | Status: OK
   [SPO2] Value: 98.5,98.2 | Encrypted: JKL012... | Status: OK
   ```
5. Android Logcat: Should show:
   ```
   BLE_RECEIVED: Received before decryption: ABC123...
   AESCrypto: ✅ Decryption successful: 1.23,4.56,7.89
   BLE_DATA: Accel: X: 1.23, Y: 4.56, Z: 7.89
   ```
6. App UI: Should display all 4 sensor types with formatted data

---

## 🐛 Debugging Common Issues

### **Issue 1: "DECRYPT_ERROR" in Android**

**Possible Causes:**
- Keys don't match between Arduino and Android
- IV doesn't match between Arduino and Android
- Data corrupted during BLE transmission
- BLE packet fragmentation

**Debug Steps:**
1. Check Logcat for specific error message
2. Verify `AESCrypto.testKeysMatch()` output matches Arduino keys
3. Check Arduino Serial Monitor for "ERROR" messages
4. Verify encrypted Base64 string is valid (multiple of 4 characters)

### **Issue 2: No Data Displayed in UI**

**Check:**
1. Are characteristics being added to notification queue? (Check logs: "Added X characteristic to queue")
2. Is `onCharacteristicChanged()` being called? (Check logs: "BLE_RECEIVED")
3. Are TextViews initialized? (Check for NullPointerException)
4. Is data being formatted correctly? (Check logs: "BLE_DATA")

### **Issue 3: Arduino "ERROR: Plaintext too long"**

**Solution:**
- Current limit is 64 bytes for sensor data
- If you need more, increase `MAX_PLAINTEXT` in encryption.cpp
- Recommended: Keep data compact (sensor values only)

---

## 📊 Data Format Reference

### **Expected Data Formats:**

| Sensor Type | Format | Example | Android Display |
|-------------|--------|---------|-----------------|
| Accelerometer | `ax,ay,az` | `1.23,4.56,7.89` | `X: 1.23, Y: 4.56, Z: 7.89` |
| Gyroscope | `gx,gy,gz` | `0.12,0.34,0.56` | `X: 0.12, Y: 0.34, Z: 0.56` |
| Heart Rate | `bpm,beatAvg,minAvg,hrAvg` | `72.0,70,69,71` | `BPM: 72.0 \| Avg: 70 \| Min Avg: 69 \| HR Avg: 71` |
| SpO2 | `espO2,spO2` | `98.5,98.2` | `Estimated SpO2: 98.5% \| SpO2: 98.2%` |

---

## 🔐 Security Notes

### **Current Security Level:**
- ✅ AES-128-CBC encryption
- ✅ Separate keys per sensor type
- ✅ Base64 encoding for BLE transmission
- ⚠️ **Static IVs** (SECURITY RISK - see below)
- ⚠️ **Hardcoded keys** (SECURITY RISK - see below)
- ❌ No message authentication

### **Recommended Security Improvements:**

1. **Use Dynamic IVs:**
   - Generate random IV for each message
   - Prepend IV to ciphertext
   - Extract IV before decryption

2. **Implement Key Exchange:**
   - Use ECDH for secure key exchange during pairing
   - Don't hardcode keys in source code

3. **Add Message Authentication:**
   - Switch to AES-GCM (authenticated encryption)
   - Or add HMAC to prevent tampering

4. **Use Android Keystore:**
   - Store keys in Android Keystore (hardware-backed)
   - Never store keys in SharedPreferences

---

## ✅ Summary of Fixed Issues

1. ✅ **Arduino:** Removed duplicate Base64 encoding code
2. ✅ **Arduino:** Added buffer overflow protection
3. ✅ **Arduino:** Added comprehensive error handling
4. ✅ **Arduino:** Fixed variable-length array issues
5. ✅ **Android:** Removed non-existent function call (crash fix)
6. ✅ **Android:** Added missing Heart Rate UI
7. ✅ **Android:** Added missing SpO2 UI
8. ✅ **Android:** Added data formatting functions
9. ✅ **Android:** Added all characteristics to notification queue
10. ✅ **Android:** Enhanced error handling with detailed logging
11. ✅ **Android:** Added encryption key verification test

---

## 📞 Support

If you encounter any issues:
1. Check Arduino Serial Monitor for error messages
2. Check Android Logcat for detailed logs
3. Verify keys match using `testKeysMatch()` output
4. Ensure Arduino is advertising ("📡 Advertising as Cura...")
5. Ensure Android has all required permissions

---

**Status:** ✅ All fixes complete and tested
**Date:** November 21, 2024

