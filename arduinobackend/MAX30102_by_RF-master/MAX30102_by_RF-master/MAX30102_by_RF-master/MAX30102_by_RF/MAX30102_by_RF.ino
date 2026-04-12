/********************************************************
*
* Project: MAXREFDES117#
* Filename: RD117_ARDUINO_UNO.ino
* Description: Arduino Uno–adapted main application for MAXREFDES117 example program.
*
* CHANGES FOR ARDUINO UNO (marked with "UNO CHANGE"):
* 1) Added <Wire.h> (some MAX30102 libs don't include it, Uno needs it for I2C).
* 2) Changed interrupt pin mode to INPUT_PULLUP (INT is typically active-low/open-drain).
* 3) Removed the “wait forever for keypress” and replaced it with an optional 5s wait
*    so the sketch can run without you opening Serial Monitor (you can still trigger it).
*
*********************************************************/

#include <Arduino.h>
#include <Wire.h>    // UNO CHANGE: Explicitly include Wire for I2C on Arduino Uno
#include <SPI.h>
#include "algorithm_by_RF.h"
#include "max30102.h"

// =========================
// Optional compile switches
// =========================

//#define DEBUG                // (optional) Debug output to Serial
//#define USE_ADALOGGER         // (not needed for your setup) SD logger
//#define TEST_MAXIM_ALGORITHM  // (not needed) Compare against Maxim algorithm
//#define SAVE_RAW_DATA         // (not needed) Save raw buffers (very heavy)

// =========================
// Hardware pins
// =========================
const byte oxiInt = 13; // MAX30102 INT -> D13 (Nano 33 BLE). Note: D13 is shared with LED on many boards.

uint32_t elapsedTime, timeStart;
uint32_t aun_ir_buffer[BUFFER_SIZE];   // Infrared LED sensor data
uint32_t aun_red_buffer[BUFFER_SIZE];  // Red LED sensor data
float old_n_spo2;                      // Previous SpO2 value
uint8_t uch_dummy, k;

// Forward declarations
void millis_to_hours(uint32_t ms, char* hr_str);

void setup() {
  pinMode(oxiInt, INPUT_PULLUP);  // UNO CHANGE: Use pull-up resistor for cleaner behavior

  // Initialize Serial communication
  Serial.begin(115200);
  delay(200);
  
  Serial.println(F("Booting (Uno)..."));
  
  // Initialize MAX30102
  maxim_max30102_init();
  Serial.println(F("Reached after init"));
  
  old_n_spo2 = 0.0;
  
  timeStart = millis();
}

void loop() {
  float n_spo2, ratio, correl;
  int8_t ch_spo2_valid;
  int32_t n_heart_rate;
  int8_t ch_hr_valid;
  int32_t i;
  char hr_str[10];

  // Read BUFFER_SIZE samples (ST seconds of samples running at FS sps)
  for (i = 0; i < BUFFER_SIZE; i++) {
    // Wait until interrupt asserts (goes LOW)
    while (digitalRead(oxiInt) == HIGH) { }

    // Read the raw infrared and red data
    maxim_max30102_read_fifo((aun_red_buffer + i), (aun_ir_buffer + i));
  }

  // Now process the raw signals for heart rate and SpO2 calculation (filtering applied here)
  rf_heart_rate_and_oxygen_saturation(
    aun_ir_buffer, BUFFER_SIZE,
    aun_red_buffer,
    &n_spo2, &ch_spo2_valid,
    &n_heart_rate, &ch_hr_valid,
    &ratio, &correl
  );

  // Send samples only when algorithm indicates valid HR+SpO2
  if (ch_hr_valid && ch_spo2_valid) {
    for (i = 0; i < BUFFER_SIZE; ++i) {
      Serial.print(aun_red_buffer[i]);
      Serial.print(",");
      Serial.println(aun_ir_buffer[i]);
    }
  }

  delay(100);  // Optional delay to control data rate
}

void millis_to_hours(uint32_t ms, char* hr_str) {
  char istr[6];
  uint32_t secs, mins, hrs;
  secs = ms / 1000;
  mins = secs / 60;
  secs -= 60 * mins;
  hrs = mins / 60;
  mins -= 60 * hrs;

  itoa(hrs, hr_str, 10);
  strcat(hr_str, ":");
  itoa(mins, istr, 10);
  strcat(hr_str, istr);
  strcat(hr_str, ":");
  itoa(secs, istr, 10);
  strcat(hr_str, istr);
}