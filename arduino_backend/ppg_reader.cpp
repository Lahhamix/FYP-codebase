#include "ppg_reader.h"

MAX30105 particleSensor;

// Heart rate detection variables
const byte RATE_SIZE = 15;
const byte MIN_SIZE = 60;
const byte HR_SIZE = 1200;
byte rates[RATE_SIZE];
byte minrate[MIN_SIZE];
byte Hrate[HR_SIZE];
byte midSpot = 0;
byte rateSpot = 0;
byte hSpot = 0;
long lastBeat = 0;

float beatsPerMinute;
int beatAvg = 0;
int MinAvg = 0;
int HrAvg = 0;

// SpO2 calculation variables
double avered = 0;
double aveir = 0;
double sumirrms = 0;
double sumredrms = 0;
int i = 0;

int Num = 100;  // Calculate SpO2 by this sampling interval (from working ESP32 code)
float ESpO2 = 0;  // Estimated SpO2 (filtered)
double SpO2 = 0;
double FSpO2 = 0.7;  // Filter factor for SpO2
double frate = 0.95;  // Low-pass filter smoothing

#define FINGER_ON 50000  // IR level indicating finger is present (from working ESP32 code)
#define USEFIFO

bool ppg_init() {
  Wire.begin();
  
  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("❌ MAX30105 not found. Check wiring/power.");
    return false;
  }
  
  Serial.println("✅ MAX30105 found! Place your finger on the sensor.");
  
  // EXACT setup from working ESP32 code
  particleSensor.setup(31, 4, 3, 3200, 411, 4096);  // Default settings that work
  particleSensor.setPulseAmplitudeRed(0xFF);  // Dim red LED
  particleSensor.setPulseAmplitudeGreen(0);   // Turn off green LED
  
  // Initialize rate arrays
  for (byte i = 0; i < RATE_SIZE; i++) rates[i] = 0;
  for (byte i = 0; i < MIN_SIZE; i++) minrate[i] = 0;
  for (int i = 0; i < HR_SIZE; i++) Hrate[i] = 0;
  
  Serial.println("[PPG] Arrays initialized, waiting for heartbeats...");
  
  return true;
}

PPGData readPPG() {
  PPGData data;
  data.heartRateAvailable = false;
  data.spo2Available = false;
  
  //================================================================================//
  // Heart Rate Monitor - EXACT COPY from working ESP32 code
  long irValue = particleSensor.getIR();
  data.irValue = irValue;
  
  // EXACT from ESP32 line 30-35 (MAX30102.ino)
  if (checkForBeat(irValue)) {
    long delta = millis() - lastBeat;
    lastBeat = millis();
    beatsPerMinute = 60 / (delta / 1000.0);
    
    if (beatsPerMinute > 50 && beatsPerMinute < 255) {
      rates[rateSpot++] = (byte)beatsPerMinute;
      rateSpot %= RATE_SIZE;
      
      minrate[midSpot++] = (byte)beatsPerMinute;
      midSpot %= MIN_SIZE;
      
      Hrate[hSpot++] = (byte)beatsPerMinute;
      hSpot %= HR_SIZE;
      
      beatAvg = 0;
      for (byte x = 0; x < RATE_SIZE; x++) {
        beatAvg += rates[x];
      }
      beatAvg /= RATE_SIZE;
      
      MinAvg = 0;
      for (byte y = 0; y < MIN_SIZE; y++) {
        MinAvg += minrate[y];
      }
      MinAvg /= MIN_SIZE;
      
      HrAvg = 0;
      for (byte z = 0; z < HR_SIZE; z++) {
        HrAvg += Hrate[z];
      }
      HrAvg /= HR_SIZE;
      
      data.beatsPerMinute = beatsPerMinute;
      data.beatAvg = beatAvg;
      data.minAvg = MinAvg;
      data.hrAvg = HrAvg;
      data.heartRateAvailable = true;
    }
  }
  
  // Finger detection check (from ESP32 line 71)
  if (irValue < FINGER_ON) {
    Serial.println("  🛑 No finger detected");
  }
  
  //================================================================================//
  // SpO2 Reading - EXACT COPY from working ESP32 code
  
  #ifdef USEFIFO
  particleSensor.check();
  
  while (particleSensor.available()) {
    uint32_t red = particleSensor.getFIFORed();
    uint32_t ir = particleSensor.getFIFOIR();
    
    i++;
    double fred = (double)red;
    double fir = (double)ir;
    
    // Low-pass filter the signal
    avered = avered * frate + fred * (1.0 - frate);
    aveir = aveir * frate + fir * (1.0 - frate);
    
    sumredrms += (fred - avered) * (fred - avered);
    sumirrms += (fir - aveir) * (fir - aveir);
    
    // Compute SpO2 every Num samples
    if ((i % Num) == 0) {
      double R = (sqrt(sumredrms) / avered) / (sqrt(sumirrms) / aveir);
      SpO2 = (0.0092 * R + 0.963) * 100 + 1;  // EXACT formula from working ESP32
      ESpO2 = FSpO2 * ESpO2 + (1.0 - FSpO2) * SpO2;  // Low-pass filter
      sumredrms = 0.0;
      sumirrms = 0.0;
      i = 0;
      
      data.espO2 = ESpO2;
      data.spO2 = SpO2;
      data.spo2Available = true;
      
      Serial.print("[PPG] 💉 SpO2: ");
      Serial.print(ESpO2, 1);
      Serial.println("%");
      
      break;
    }
    
    particleSensor.nextSample();
  }
  #endif
  
  return data;
}

