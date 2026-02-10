#include "ppg_reader.h"

MAX30105 particleSensor;

// Finger detection threshold
const uint32_t FINGER_THRESHOLD = 20000;

// Heart Rate variables
uint32_t lastBeatMs = 0;
float bpmHist[4] = {0, 0, 0, 0};
uint8_t bpmIdx = 0;
int32_t heartRateInt = 0;
int8_t validHeartRate = 0;

// SpO2 calculation variables
uint32_t ir = 0, red = 0;
double avered = 0.0, aveir = 0.0;
double sumredrms = 0.0, sumirrms = 0.0;
double R = 0.0;
double SpO2 = 0.0;
int8_t validSPO2 = 0;

const int SPO2_SAMPLES = 100;
int spo2SampleCounter = 0;

bool ppg_init() {
  Wire.begin();
  
  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("❌ MAX30105 not found. Check wiring/power.");
    return false;
  }
  
  Serial.println("✅ MAX30105 found! Place your finger on the sensor.");
  
  // Cleaner sensor configuration (stable, anti-saturation)
  particleSensor.setup(
    40,     // LED brightness
    4,      // sample average
    2,      // Red + IR
    100,    // sample rate Hz
    215,    // pulse width
    16384   // ADC range
  );

  particleSensor.setPulseAmplitudeIR(0x30);
  particleSensor.setPulseAmplitudeRed(0x10);
  particleSensor.setPulseAmplitudeGreen(0);

  lastBeatMs = millis();
  
  Serial.println("[PPG] Initialized. Waiting for heartbeats...");
  
  return true;
}

void resetSpo2() {
  sumredrms = 0.0;
  sumirrms = 0.0;
  spo2SampleCounter = 0;
}

PPGData readPPG() {
  PPGData data;
  data.heartRateAvailable = false;
  data.spo2Available = false;
  data.validHeartRate = 0;
  data.validSPO2 = 0;
  
  particleSensor.check();
  
  while (particleSensor.available()) {
    red = particleSensor.getFIFORed();
    ir  = particleSensor.getFIFOIR();
    particleSensor.nextSample();
    
    // Finger detection
    if (ir < FINGER_THRESHOLD) {
      validHeartRate = 0;
      validSPO2 = 0;
      resetSpo2();
      Serial.println("[PPG] 🛑 No finger detected");
      continue;
    }
    
    // Heart Rate detection
    if (checkForBeat((long)ir)) {
      uint32_t now = millis();
      uint32_t dt = now - lastBeatMs;
      lastBeatMs = now;
      
      if (dt > 0) {
        float bpm = 60000.0f / dt;
        
        if (bpm > 45 && bpm < 180) {
          bpmHist[bpmIdx++] = bpm;
          bpmIdx %= 4;
          
          float sum = 0;
          for (int i = 0; i < 4; i++) sum += bpmHist[i];
          heartRateInt = (int)(sum / 4.0f + 0.5f);
          validHeartRate = 1;
          
          data.beatsPerMinute = heartRateInt;
          data.heartRateAvailable = true;
          data.validHeartRate = 1;
          
          // Serial print for heart rate
          Serial.print("[PPG] ❤️ BPM: ");
          Serial.print(heartRateInt);
          Serial.println(" ✅");
        }
      }
    }
    
    // SpO2 calculation
    double fred = (double)red;
    double fir  = (double)ir;
    
    avered = avered * 0.9 + fred * 0.1;
    aveir  = aveir  * 0.9 + fir  * 0.1;
    
    sumredrms += (fred - avered) * (fred - avered);
    sumirrms  += (fir  - aveir)  * (fir  - aveir);
    
    spo2SampleCounter++;
    
    if (spo2SampleCounter >= SPO2_SAMPLES) {
      if (avered > 1 && aveir > 1) {
        R = (sqrt(sumredrms) / avered) / (sqrt(sumirrms) / aveir);
        SpO2 = (0.0092 * R + 0.963) * 100.0 + 1.0;
        
        if (SpO2 > 100) SpO2 = 100;
        if (SpO2 < 0)   SpO2 = 0;
        
        validSPO2 = 1;
        data.spO2 = SpO2;
        data.spo2Available = true;
        data.validSPO2 = 1;
        
        // Serial print for SpO2
        Serial.print("[PPG] 🩸 SpO2: ");
        Serial.print(SpO2, 1);
        Serial.println(" % ✅");
      } else {
        validSPO2 = 0;
      }
      resetSpo2();
    }
  }
  
  // Copy final validity flags
  data.validHeartRate = validHeartRate;
  data.validSPO2 = validSPO2;
  
  return data;
}

