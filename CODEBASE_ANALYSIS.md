# Complete Codebase Analysis - SoleMate Wearable Health Monitoring System

## Executive Summary

This is a **Final Year Project (FYP)** codebase for a wearable health monitoring system called **"SoleMate"**. The system consists of:

1. **Android Mobile Application** (Kotlin) - User interface and data visualization
2. **Arduino Backend** (C++) - Embedded firmware for sensor data collection and BLE communication

The system monitors multiple health parameters including heart rate, SpO2, gait analysis (via IMU), and edema detection (via flex sensors), with end-to-end encryption for secure data transmission.

---

## 1. System Architecture Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Application                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Welcome  │→ │  Login   │→ │   Scan   │→ │   Main   │  │
│  │ Activity │  │ Activity │  │ Activity │  │ Activity │  │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         BLE Communication Layer                       │  │
│  │  - KeyExchangeManager (ECDH)                         │  │
│  │  - AESCrypto (AES-128-CBC decryption)                 │  │
│  │  - BluetoothGattCallback                             │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │  Readings    │  │    Gait      │  │   About Us   │    │
│  │  Activity    │  │  Analysis    │  │   Activity   │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
└─────────────────────────────────────────────────────────────┘
                          ↕ BLE (Bluetooth Low Energy)
┌─────────────────────────────────────────────────────────────┐
│                  Arduino Nano 33 BLE                         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              BLE Services & Characteristics           │  │
│  │  - Key Exchange Service (ECDH)                        │  │
│  │  - Wearable Sensor Service (Encrypted Data)           │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │   IMU    │  │   PPG    │  │   Flex   │  │Encryption│   │
│  │  Reader  │  │  Reader  │  │  Reader  │  │  Module  │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Hardware Sensors                         │   │
│  │  - BMI270_BMM150 (IMU: Accelerometer + Gyroscope)    │   │
│  │  - MAX30105 (PPG: Heart Rate + SpO2)                 │   │
│  │  - Flex Sensors A0, A1 (Edema Detection)            │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Technology Stack

**Android Application:**
- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Build System: Gradle with Kotlin DSL
- Key Libraries:
  - AndroidX Core KTX
  - Material Design Components
  - MPAndroidChart (v3.1.0) - for data visualization
  - AndroidX Biometric - for fingerprint/face authentication
  - AndroidX LocalBroadcastManager - for inter-activity communication

**Arduino Backend:**
- Platform: Arduino Nano 33 BLE
- Language: C++ (Arduino)
- Key Libraries:
  - ArduinoBLE - BLE communication
  - Arduino_BMI270_BMM150 - IMU sensor
  - MAX30105 - PPG sensor
  - Crypto Library (AES-128-CBC)
  - micro-ecc (uECC) - ECDH key exchange

---

## 2. Android Application Detailed Analysis

### 2.1 Application Structure

The Android app follows a **multi-activity architecture** with the following flow:

```
WelcomeActivity → RegistrationActivity → LoginActivity → ScanActivity → MainActivity
                                                                    ↓
                                                    ┌───────────────┴───────────────┐
                                                    ↓                               ↓
                                            ReadingsActivity              GaitAnalysisActivity
```

### 2.2 Activity Breakdown

#### **WelcomeActivity.kt**
- **Purpose**: First-time user onboarding
- **Functionality**:
  - Checks if app is launched for the first time using SharedPreferences
  - Shows welcome screen on first launch
  - Routes to RegistrationActivity on first launch
  - Routes to LoginActivity on subsequent launches
- **Key Implementation**:
  - Uses SharedPreferences key: `"is_first_launch"`

#### **RegistrationActivity.kt**
- **Purpose**: User registration
- **Functionality**:
  - Collects username, age, and password
  - Stores credentials in SharedPreferences (`"SolematePrefs"`)
  - Validates all fields are filled
  - Navigates to ScanActivity after successful registration
- **Stored Data**:
  - `username` (String)
  - `age` (String)
  - `password` (String)
  - `is_registered` (Boolean)

#### **LoginActivity.kt**
- **Purpose**: User authentication
- **Functionality**:
  - Validates username and password against stored credentials
  - Supports biometric authentication (fingerprint/face)
  - Uses AndroidX Biometric API
  - Navigates to ScanActivity on successful login
- **Security Features**:
  - Biometric authentication with `BIOMETRIC_STRONG` requirement
  - Fallback to password authentication

#### **ScanActivity.kt**
- **Purpose**: BLE device discovery and connection initiation
- **Functionality**:
  - Scans for BLE devices advertising as "SoleMate"
  - Displays discovered devices in a RecyclerView
  - Handles BLE permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, LOCATION)
  - Requires location services to be enabled (Android BLE requirement)
  - Passes selected device address to MainActivity
- **Key Features**:
  - Uses `ScanSettings.SCAN_MODE_LOW_LATENCY` for fast discovery
  - Implements 500ms delay after stopping scan to prevent GATT error 133
  - Custom `DeviceListAdapter` for device list display

#### **MainActivity.kt** (Core Component)
- **Purpose**: Main application hub and BLE communication center
- **Key Responsibilities**:
  1. **BLE Connection Management**
     - Establishes GATT connection to Arduino device
     - Handles connection state changes
     - Manages MTU negotiation (requests 128 bytes)
     - Implements reconnection logic

  2. **Key Exchange Protocol**
     - Initiates ECDH key exchange using `KeyExchangeManager`
     - Writes phone's public key to peripheral
     - Reads peripheral's public key
     - Derives shared secret and initializes AES encryption
     - Falls back to legacy keys if key exchange service not found

  3. **Data Reception & Decryption**
     - Enables notifications on 5 characteristics:
       - Accelerometer (UUID: `9a8b0002-...`)
       - Gyroscope (UUID: `9a8b0003-...`)
       - Heart Rate (UUID: `9a8b0004-...`)
       - SpO2 (UUID: `9a8b0005-...`)
       - Edema/Flex (UUID: `9a8b0006-...`)
     - Buffers incoming encrypted data
     - Decrypts using `AESCrypto`
     - Broadcasts decrypted data via LocalBroadcastManager

  4. **Data Logging**
     - Logs timestamped sensor data every 1 second
     - Stores: Heart Rate, SpO2, Edema status
     - Exports data to CSV file in Downloads folder
     - CSV format: `{username}_sensor_data_{date}.csv`

  5. **UI Navigation**
     - Navigation drawer for accessing different features
     - Cards for navigating to ReadingsActivity and GaitAnalysisActivity
     - Status indicator showing connection state

- **Key Implementation Details**:
  - Uses `ConcurrentLinkedQueue` for notification queue processing
  - Implements buffer draining for fragmented BLE packets
  - Payload format: `[1 byte length][encrypted data]`
  - Maximum payload length: 80 bytes

#### **ReadingsActivity.kt**
- **Purpose**: Real-time vital signs visualization
- **Functionality**:
  - Displays heart rate (BPM) and SpO2 (%) in real-time
  - Shows status indicators:
     - Heart Rate: Resting (<60), Normal (60-100), Elevated (100-140), Critical (>140)
     - SpO2: Excellent (≥95%), Good (90-95%), Fair (85-90%), Low (<85%)
  - Real-time line charts using MPAndroidChart
  - Maintains rolling history of 30 data points
  - Receives data via LocalBroadcastManager
- **Chart Configuration**:
  - Heart Rate: Y-axis 40-180 BPM, red line
  - SpO2: Y-axis 80-100%, cyan line
  - Cubic bezier interpolation for smooth curves

#### **GaitAnalysisActivity.kt**
- **Purpose**: Gait analysis and edema monitoring
- **Functionality**:
  - Displays accelerometer data (x, y, z)
  - Displays gyroscope data (x, y, z)
  - Visual edema indicator with gradient bar
  - Edema classification display:
     - Levels: none, subclinical, mild, moderate, severe, calibrating
     - Deviation values from baseline
  - Receives IMU and flex sensor data via broadcasts
- **Edema Visualization**:
  - Gradient bar from red (severe) to green (none)
  - Indicator position based on edema level
  - Real-time deviation calculation display

#### **AboutUsActivity.kt**
- **Purpose**: Simple information screen
- **Functionality**: Static content display

### 2.3 Core Security Components

#### **KeyExchangeManager.kt**
- **Purpose**: ECDH (Elliptic Curve Diffie-Hellman) key exchange implementation
- **Algorithm**: secp256r1 (P-256)
- **Functionality**:
  1. Generates ephemeral EC key pair on initialization
  2. Provides public key in uncompressed format (65 bytes: `0x04 || X || Y`)
  3. Receives peripheral's public key (65 bytes)
  4. Computes shared secret using `KeyAgreement.doPhase()`
  5. Returns 32-byte shared secret
  6. Self-destructs ephemeral keys after use
- **Security Features**:
  - Uses `SecureRandom` for key generation
  - Implements `AutoCloseable` for resource cleanup
  - Ensures proper byte array length (32 bytes) for coordinates

#### **AESCrypto.kt**
- **Purpose**: AES-128-CBC encryption/decryption
- **Functionality**:
  - Initializes with shared secret from key exchange
  - Derives 5 separate keys using SHA-256 KDF:
     - `HEART_RATE` → Heart Rate key
     - `SPO2` → SpO2 key
     - `ACCEL` → Accelerometer key
     - `GYRO` → Gyroscope key
     - `FLEX` → Flex/Edema key
  - Each data type has unique IV (Initialization Vector):
     - Heart Rate: `0x20-0x2F`
     - SpO2: `0x30-0x3F`
     - Accel: `0x00-0x0F`
     - Gyro: `0x10-0x1F`
     - Flex: `0x40-0x4F`
  - Supports legacy mode with static keys (backward compatibility)
- **Key Derivation Function (KDF)**:
  ```kotlin
  SHA-256(sharedSecret || purpose) → first 16 bytes
  ```
  This matches the Arduino implementation exactly.

#### **SharedSecretStore.kt**
- **Purpose**: Secure storage of shared secret (currently not actively used)
- **Functionality**:
  - Uses Android Keystore for hardware-backed encryption
  - Wraps shared secret with AES-GCM
  - Stores encrypted secret in SharedPreferences
  - Provides save/load/clear methods
- **Note**: This is implemented but not currently called in MainActivity

### 2.4 Data Flow in Android App

```
BLE Notification Received
    ↓
onCharacteristicChanged()
    ↓
Buffer data in ByteArrayOutputStream
    ↓
drainBuffer() - Extract complete payload
    ↓
AESCrypto.decryptXXX() - Decrypt based on UUID
    ↓
Update latestBpm/latestSpo2/latestEdema
    ↓
Broadcast via LocalBroadcastManager
    ↓
ReadingsActivity / GaitAnalysisActivity receive
    ↓
Update UI (TextViews, Charts)
```

### 2.5 Permissions Required

```xml
- BLUETOOTH
- BLUETOOTH_ADMIN
- BLUETOOTH_CONNECT (Android 12+)
- BLUETOOTH_SCAN (Android 12+)
- ACCESS_FINE_LOCATION (required for BLE scanning)
- ACCESS_COARSE_LOCATION
- USE_BIOMETRIC
- WRITE_EXTERNAL_STORAGE (Android 9 and below)
- INTERNET
```

---

## 3. Arduino Backend Detailed Analysis

### 3.1 Main Program: `arduino_backend.ino`

#### **Setup Phase:**
1. **Serial Initialization**: 9600 baud (non-blocking)
2. **RNG Seeding**: Uses `analogRead(0) + micros()` for entropy
3. **Sensor Initialization**:
   - IMU (BMI270_BMM150)
   - PPG (MAX30105)
   - Flex Sensors (A0, A1)
4. **BLE Initialization**:
   - Device name: "SoleMate"
   - Advertises two services:
     - Key Exchange Service
     - Wearable Sensor Service
5. **Key Exchange Preparation**:
   - Generates ECDH key pair
   - Prepares peripheral public key (65 bytes)

#### **Main Loop:**
1. **Connection Handling**:
   - Waits for central (Android) connection
   - Polls BLE events
   - Only streams data after key exchange completes

2. **Data Collection & Transmission**:
   - **IMU Data** (10 Hz):
     - Accelerometer: `ax, ay, az` (g)
     - Gyroscope: `gx, gy, gz` (deg/s)
     - Format: `"x.xx,y.yy,z.zz"`
   - **PPG Data** (variable rate):
     - Heart Rate: BPM (integer)
     - SpO2: percentage (1 decimal)
     - Only sent when valid (finger detected)
   - **Flex Data** (5 Hz):
     - Edema classification: `"label,totalDev,dev1,dev2"`
     - Example: `"moderate,15,7,8"`

3. **Encryption**:
   - All data encrypted before transmission
   - Uses separate keys/IVs for each data type
   - Payload format: `[1 byte length][encrypted data]`

### 3.2 Sensor Modules

#### **imu_reader.h / imu_reader.cpp**
- **Hardware**: BMI270_BMM150 (Bosch IMU)
- **Functionality**:
  - Reads 6-axis motion data
  - Accelerometer: ±2g range (default)
  - Gyroscope: ±250 dps range (default)
- **Data Structure**:
  ```cpp
  struct IMUData {
    float ax, ay, az;  // Acceleration (g)
    float gx, gy, gz;  // Angular velocity (deg/s)
    bool available;
  };
  ```
- **Update Rate**: ~10 Hz (100ms delay in main loop)

#### **ppg_reader.h / ppg_reader.cpp**
- **Hardware**: MAX30105 (Maxim Integrated)
- **Functionality**:
  - Heart Rate detection via IR LED
  - SpO2 calculation via Red + IR LEDs
  - Finger detection (threshold: 20000)
- **Heart Rate Algorithm**:
  - Detects peaks in IR signal
  - Calculates BPM from inter-beat intervals
  - Validates range: 45-180 BPM
  - Uses 4-sample moving average
- **SpO2 Algorithm**:
  - Collects 100 samples
  - Calculates R-value: `(RMS_red / avg_red) / (RMS_ir / avg_ir)`
  - Formula: `SpO2 = (0.0092 * R + 0.963) * 100.0 + 1.0`
  - Clamps to 0-100%
- **Data Structure**:
  ```cpp
  struct PPGData {
    int32_t beatsPerMinute;
    double spO2;
    bool heartRateAvailable;
    bool spo2Available;
    int8_t validHeartRate;  // 1 = valid, 0 = invalid
    int8_t validSPO2;
  };
  ```
- **Sensor Configuration**:
  - LED brightness: 40
  - Sample average: 4
  - Sample rate: 100 Hz
  - Pulse width: 215 μs
  - ADC range: 16384

#### **flex_reader.h / flex_reader.cpp**
- **Hardware**: Two flex sensors on analog pins A0 and A1
- **Purpose**: Edema (swelling) detection
- **Calibration Process**:
  1. **Pre-calibration** (1 second):
     - Collects samples to initialize moving average
  2. **Calibration Phase** (60 seconds):
     - Tracks minimum filtered values
     - Only updates baseline when readings are stable
     - Stability requirement: 6 consecutive samples within ±2 ADC units
     - Warm-up period: 5 seconds before baseline tracking
  3. **Normal Operation**:
     - Moving average smoothing (10 samples)
     - Calculates deviation from baseline
     - Classifies edema level

- **Edema Classification Thresholds** (12-bit ADC scaled):
  - Subclinical: ≥12
  - Mild: ≥20
  - Moderate: ≥40
  - Severe: ≥80

- **Data Structure**:
  ```cpp
  struct FlexData {
    int raw1, raw2;
    int filtered1, filtered2;
    int baseline1, baseline2;
    int deviation1, deviation2;
    int totalDeviation;
    const char* edemaLabel;  // "none", "subclinical", "mild", "moderate", "severe", "calibrating"
    bool calibrated;
    bool dataAvailable;
  };
  ```

- **Algorithm Details**:
  - Moving average window: 10 samples
  - Sample interval: 200ms (5 Hz)
  - Baseline = minimum filtered value during calibration
  - Deviation = absolute difference from baseline
  - Total deviation = average of both sensors

### 3.3 Security Modules

#### **key_exchange.h / key_exchange.cpp**
- **Purpose**: ECDH key exchange implementation
- **Algorithm**: secp256r1 using micro-ecc library
- **Functionality**:
  1. **Initialization** (`key_exchange_init()`):
     - Generates EC key pair using `uECC_make_key()`
     - Converts to uncompressed format: `0x04 || X || Y`
     - Stores private key securely
  2. **Phone Key Processing** (`key_exchange_process_phone_key()`):
     - Receives 65-byte uncompressed public key from Android
     - Computes shared secret using `uECC_shared_secret()`
     - Derives 5 keys using SHA-256 KDF:
       - `SHA-256(sharedSecret || "ACCEL")` → first 16 bytes
       - `SHA-256(sharedSecret || "GYRO")` → first 16 bytes
       - `SHA-256(sharedSecret || "HEART_RATE")` → first 16 bytes
       - `SHA-256(sharedSecret || "SPO2")` → first 16 bytes
       - `SHA-256(sharedSecret || "FLEX")` → first 16 bytes
  3. **Key Retrieval**: Functions to get derived keys for encryption

- **RNG Function**:
  ```cpp
  static int rng_func(uint8_t* dest, unsigned size) {
    for (unsigned i = 0; i < size; i++) {
      dest[i] = (uint8_t)(random(256));
    }
    return 1;
  }
  ```
  Uses Arduino `random()` seeded with analog noise.

#### **encryption.h / encryption.cpp**
- **Purpose**: AES-128-CBC encryption
- **Library**: Arduino Crypto Library (AES, CBC)
- **Functionality**:
  1. **Initialization** (`encryption_init_from_key_exchange()`):
     - Retrieves 5 derived keys from key exchange module
     - Sets encryption ready flag
  2. **Encryption Process**:
     - Converts String to byte array
     - Adds PKCS5 padding
     - Encrypts using AES-128-CBC
     - Returns `EncryptedPayload` structure
  3. **Data Type-Specific Functions**:
     - `encryptAccel()` - uses accelKey + accelIV
     - `encryptGyro()` - uses gyroKey + gyroIV
     - `encryptHeartRate()` - uses heartRateKey + heartRateIV
     - `encryptSpO2()` - uses spo2Key + spo2IV
     - `encryptFlex()` - uses flexKey + flexIV

- **IV Values** (must match Android):
  - Accel: `0x00-0x0F`
  - Gyro: `0x10-0x1F`
  - Heart Rate: `0x20-0x2F`
  - SpO2: `0x30-0x3F`
  - Flex: `0x40-0x4F`

- **Payload Structure**:
  ```cpp
  struct EncryptedPayload {
    size_t length;        // Encrypted data length
    uint8_t data[80];    // Encrypted ciphertext (max 64 bytes + padding)
  };
  ```

### 3.4 BLE Service Architecture

#### **Key Exchange Service** (UUID: `9a8b1001-6d5e-4c10-b6d9-1f25c09d9e00`)
- **Characteristics**:
  1. **Phone Public Key** (UUID: `9a8b1002-...`)
     - Properties: `BLEWrite`
     - Size: 65 bytes
     - Handler: `onPhoneKeyWritten()` - triggers key derivation
  2. **Peripheral Public Key** (UUID: `9a8b1003-...`)
     - Properties: `BLERead`
     - Size: 65 bytes
     - Pre-populated with peripheral's public key

#### **Wearable Sensor Service** (UUID: `9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00`)
- **Characteristics** (all with `BLERead | BLENotify`, size 81 bytes):
  1. **Accelerometer** (`9a8b0002-...`)
  2. **Gyroscope** (`9a8b0003-...`)
  3. **Heart Rate** (`9a8b0004-...`)
  4. **SpO2** (`9a8b0005-...`)
  5. **Edema/Flex** (`9a8b0006-...`)

- **Payload Format**:
  ```
  [1 byte: length][encrypted data: 16-80 bytes]
  ```

### 3.5 Data Flow in Arduino

```
Sensor Hardware
    ↓
Sensor Reader Module (readIMU/readPPG/readFlex)
    ↓
Format as String
    ↓
Encryption Module (encryptXXX)
    ↓
PKCS5 Padding + AES-128-CBC
    ↓
Format as [length][ciphertext]
    ↓
BLE Characteristic.writeValue()
    ↓
Transmitted to Android
```

---

## 4. Security Architecture

### 4.1 Key Exchange Protocol (ECDH)

**Flow:**
```
1. Arduino: Generate key pair (private_key_A, public_key_A)
2. Android: Generate key pair (private_key_B, public_key_B)
3. Android → Arduino: Write public_key_B (65 bytes)
4. Arduino: Compute shared_secret = ECDH(private_key_A, public_key_B)
5. Arduino → Android: Read public_key_A (65 bytes)
6. Android: Compute shared_secret = ECDH(private_key_B, public_key_A)
7. Both: Derive 5 keys using SHA-256 KDF
8. Both: Initialize AES encryption with derived keys
```

**Security Properties:**
- Forward secrecy (ephemeral keys)
- Perfect forward secrecy (keys destroyed after use)
- 256-bit security level (secp256r1)
- Shared secret never transmitted over BLE

### 4.2 Encryption Scheme

**Algorithm**: AES-128-CBC with PKCS5 padding

**Key Derivation**:
```
For each data type (ACCEL, GYRO, HEART_RATE, SPO2, FLEX):
  key = SHA-256(sharedSecret || purpose)[0:16]
```

**IV Strategy**:
- Static IVs per data type (not secret, just unique)
- Ensures same plaintext produces different ciphertext across types
- IVs are 16 bytes, sequential values

**Security Considerations**:
- ✅ Separate keys per data type (key isolation)
- ✅ CBC mode (provides confidentiality)
- ⚠️ Static IVs (acceptable for this use case, but not ideal for high-security)
- ✅ PKCS5 padding (standard, secure)
- ✅ 128-bit keys (adequate for IoT)

### 4.3 Legacy Mode

If key exchange service is not found, the system falls back to static keys:
- Heart Rate: `"HeartRateKey1234"`
- SpO2: `"SpO2Key123456789"`
- Accel: `"AccelKey12345678"`
- Gyro: `"GyroKey123456789"`
- Flex: `"FlexKey123456789"`

**Note**: Legacy mode is less secure but provides backward compatibility.

---

## 5. Data Formats

### 5.1 Sensor Data Formats

**Accelerometer**:
```
Format: "x.xx,y.yy,z.zz"
Example: "0.12,-0.98,9.81"
Units: g (gravity)
```

**Gyroscope**:
```
Format: "x.xx,y.yy,z.zz"
Example: "1.23,-2.45,0.67"
Units: degrees/second
```

**Heart Rate**:
```
Format: "123"
Example: "72"
Units: BPM (beats per minute)
Range: 45-180 (valid)
```

**SpO2**:
```
Format: "98.5"
Example: "97.2"
Units: percentage
Range: 0-100%
```

**Edema/Flex**:
```
Format: "label,totalDev,dev1,dev2"
Example: "moderate,15,7,8"
Labels: "none", "subclinical", "mild", "moderate", "severe", "calibrating"
```

### 5.2 BLE Packet Format

```
[1 byte: payload_length][encrypted_data: 16-80 bytes]
```

**Example**:
- Plaintext: `"72"` (2 bytes)
- After PKCS5 padding: 16 bytes
- Encrypted: 16 bytes
- BLE packet: `[0x10][16 encrypted bytes]`

### 5.3 CSV Export Format

```csv
Timestamp,Heart Rate,SpO2,Edema
2024-01-15 10:30:45,72,98.5,moderate
2024-01-15 10:30:46,73,98.6,moderate
...
```

---

## 6. Communication Protocol

### 6.1 BLE Connection Sequence

```
1. Android: Scan for "SoleMate" device
2. Android: Connect to device (GATT)
3. Android: Request MTU (128 bytes)
4. Android: Discover services
5. Android: Check for key exchange service
   ├─ If found: Proceed with ECDH
   └─ If not found: Use legacy keys
6. Android: Write phone public key
7. Arduino: Process key, derive keys, init encryption
8. Android: Read peripheral public key
9. Android: Derive keys, init encryption
10. Android: Enable notifications on all characteristics
11. Arduino: Start streaming encrypted data
12. Android: Receive, decrypt, display data
```

### 6.2 Error Handling

**Connection Errors**:
- GATT error 133: Handled with 500ms delay after scan stop
- MTU negotiation failure: Falls back to default MTU
- Service discovery failure: Falls back to legacy mode

**Data Errors**:
- Empty payload: Logged, ignored
- Decryption failure: Returns "DECRYPT_ERROR"
- Invalid data format: Parsed with null checks

**Sensor Errors**:
- IMU init failure: Arduino halts (while(1))
- PPG init failure: Arduino halts
- Flex init failure: Arduino halts
- Finger not detected: PPG returns invalid flags

### 6.3 Update Rates

- **IMU**: ~10 Hz (100ms delay)
- **PPG**: Variable (depends on heart rate, ~1-2 Hz for HR, ~0.1 Hz for SpO2)
- **Flex**: ~5 Hz (200ms sample interval)
- **BLE Notifications**: As data becomes available

---

## 7. Key Functionalities

### 7.1 Real-Time Monitoring
- Continuous sensor data streaming
- Live updates in UI
- Connection status indicators
- Data validation and error handling

### 7.2 Data Visualization
- **ReadingsActivity**:
  - Real-time line charts (30-point history)
  - Color-coded status indicators
  - Smooth bezier curves
- **GaitAnalysisActivity**:
  - Textual IMU data display
  - Visual edema indicator with gradient bar
  - Real-time deviation values

### 7.3 Data Export
- CSV file generation
- Timestamped entries
- Saved to Downloads folder
- Filename: `{username}_sensor_data_{date}.csv`

### 7.4 User Authentication
- Password-based login
- Biometric authentication (fingerprint/face)
- Secure credential storage (SharedPreferences)

### 7.5 Connection Management
- Automatic reconnection on disconnect
- Connection state monitoring
- Disconnect dialogs with reconnection options
- Last device address caching

---

## 8. File Structure Summary

### Android App
```
app/src/main/java/com/example/ble_viewer/
├── WelcomeActivity.kt          # First launch screen
├── RegistrationActivity.kt    # User registration
├── LoginActivity.kt            # User authentication
├── ScanActivity.kt             # BLE device scanning
├── MainActivity.kt             # Main hub & BLE communication
├── ReadingsActivity.kt         # Vital signs display
├── GaitAnalysisActivity.kt     # Gait & edema analysis
├── AboutUsActivity.kt          # Info screen
├── AESCrypto.kt                # AES decryption
├── KeyExchangeManager.kt       # ECDH key exchange
├── SharedSecretStore.kt        # Secure storage (unused)
└── DeviceListAdapter.kt        # BLE device list adapter
```

### Arduino Backend
```
arduino_backend/
├── arduino_backend.ino         # Main program
├── imu_reader.h/.cpp           # IMU sensor module
├── ppg_reader.h/.cpp           # PPG sensor module
├── flex_reader.h/.cpp          # Flex sensor module
├── encryption.h/.cpp           # AES encryption
└── key_exchange.h/.cpp         # ECDH key exchange
```

---

## 9. Dependencies & Libraries

### Android
- `androidx.core:core-ktx:1.9.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `androidx.biometric:biometric:1.1.0`
- `androidx.localbroadcastmanager:localbroadcastmanager:1.1.0`
- `com.github.PhilJay:MPAndroidChart:v3.1.0`

### Arduino
- ArduinoBLE (built-in for Nano 33 BLE)
- Arduino_BMI270_BMM150
- MAX30105 (custom library)
- Crypto (AES, CBC, SHA256)
- micro-ecc (uECC for ECDH)

---

## 10. Known Limitations & Considerations

### 10.1 Security
- Static IVs (acceptable but not ideal)
- Legacy mode uses weak static keys
- Shared secret not persisted securely (SharedSecretStore exists but unused)
- No certificate pinning or device authentication

### 10.2 Performance
- BLE MTU limited to 128 bytes (Android request)
- Data rate limited by BLE bandwidth
- No data compression
- CSV export happens in main thread (could block UI)

### 10.3 Reliability
- No automatic reconnection on connection loss
- Sensor initialization failures cause Arduino to halt
- No data validation on Arduino side before encryption
- Finger detection threshold may need calibration

### 10.4 User Experience
- 60-second calibration period for flex sensors (no progress indicator)
- No offline data storage (data lost on disconnect)
- CSV export only available from MainActivity
- No data filtering or smoothing in Android app

---

## 11. Potential Improvements

### 11.1 Security Enhancements
- Implement counter-based IVs for better security
- Add device authentication/pairing
- Use SharedSecretStore for key persistence
- Implement key rotation mechanism

### 11.2 Performance Optimizations
- Implement data compression
- Use background threads for CSV export
- Add data buffering for offline storage
- Optimize BLE packet size

### 11.3 Feature Additions
- Add data filtering/smoothing algorithms
- Implement gait analysis algorithms (step detection, stride length)
- Add historical data viewing
- Implement alerts for abnormal readings
- Add calibration progress indicator
- Implement data export in other formats (JSON, Excel)

### 11.4 Code Quality
- Add unit tests
- Implement proper error handling with retry logic
- Add logging framework
- Document API interfaces
- Add code comments for complex algorithms

---

## 12. Conclusion

This codebase represents a **complete wearable health monitoring system** with:

✅ **Secure Communication**: ECDH key exchange + AES-128-CBC encryption  
✅ **Multi-Sensor Support**: IMU, PPG, Flex sensors  
✅ **Real-Time Visualization**: Charts and status indicators  
✅ **User Authentication**: Password + biometric  
✅ **Data Export**: CSV file generation  
✅ **Robust Error Handling**: Connection management, fallback modes  

The architecture is well-structured with clear separation of concerns between Android app and Arduino firmware. The security implementation follows industry standards (ECDH + AES), though there are opportunities for enhancement.

The system is production-ready for a prototype/demonstration, with room for improvements in security, performance, and user experience for commercial deployment.

---

**Analysis Date**: 2024  
**Codebase Version**: 1.0  
**Total Files Analyzed**: 20+ source files  
**Lines of Code**: ~3000+ (estimated)
