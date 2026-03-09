#include "flex_reader.h"

void setup() {
  Serial.begin(115200);
  delay(500);

  Serial.println("=== FLEX MODULE TEST ===");

  if (!flex_init()) {
    Serial.println("flex_init failed");
    while (1) {}
  }
}

void loop() {
  FlexData d = readFlex();

  // During calibration, dataAvailable will usually be false in the exact-behavior version.
  // After calibration, one classified sample is returned each call.
  if (d.dataAvailable) {
    Serial.print("calibrated=");
    Serial.print(d.calibrated ? 1 : 0);
    Serial.print(", raw1=");
    Serial.print(d.raw1);
    Serial.print(", raw2=");
    Serial.print(d.raw2);
    Serial.print(", filt1=");
    Serial.print(d.filtered1);
    Serial.print(", filt2=");
    Serial.print(d.filtered2);
    Serial.print(", base1=");
    Serial.print(d.baseline1);
    Serial.print(", base2=");
    Serial.print(d.baseline2);
    Serial.print(", dev1=");
    Serial.print(d.deviation1);
    Serial.print(", dev2=");
    Serial.print(d.deviation2);
    Serial.print(", total=");
    Serial.print(d.totalDeviation);
    Serial.print(", label=");
    Serial.println(d.edemaLabel);
  }
}