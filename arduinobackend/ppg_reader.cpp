/**
 * PPG / MAX30102 — single module: sensor I2C driver, RF HR+SpO2 algorithm, and tick-based reader.
 */

#include "ppg_reader.h"
#include <ArduinoBLE.h>
#include <Wire.h>
#include <math.h>
#include "serial_log.h"

static inline void ble_yield() {
  // Keep the BLE stack responsive during long PPG reads.
  BLE.poll();
  yield();
}

// =============================================================================
// MAX30102 I2C driver (internal)
// =============================================================================

#define MAX30102_I2C_ADDR 0x57

#define REG_INTR_STATUS_1 0x00
#define REG_INTR_STATUS_2 0x01
#define REG_INTR_ENABLE_1 0x02
#define REG_INTR_ENABLE_2 0x03
#define REG_FIFO_WR_PTR 0x04
#define REG_OVF_COUNTER 0x05
#define REG_FIFO_RD_PTR 0x06
#define REG_FIFO_DATA 0x07
#define REG_FIFO_CONFIG 0x08
#define REG_MODE_CONFIG 0x09
#define REG_SPO2_CONFIG 0x0A
#define REG_LED1_PA 0x0C
#define REG_LED2_PA 0x0D
#define REG_PILOT_PA 0x10
#define REG_TEMP_INTR 0x1F
#define REG_TEMP_FRAC 0x20
#define REG_TEMP_CONFIG 0x21
#define REG_REV_ID 0xFE
#define REG_PART_ID 0xFF

#define INTR_STATUS_PPG_RDY 0x40

static inline uint8_t max30102_addr7() { return MAX30102_I2C_ADDR; }

static bool maxim_max30102_write_reg(uint8_t uch_addr, uint8_t uch_data) {
  Wire.beginTransmission(max30102_addr7());
  Wire.write(uch_addr);
  Wire.write(uch_data);
  Wire.endTransmission();
  return true;
}

static bool maxim_max30102_read_reg(uint8_t uch_addr, uint8_t *puch_data) {
  Wire.beginTransmission(max30102_addr7());
  Wire.write(uch_addr);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)max30102_addr7(), (uint8_t)1) != 1) return false;
  *puch_data = Wire.read();
  return true;
}

static bool maxim_max30102_reset() {
  return maxim_max30102_write_reg(REG_MODE_CONFIG, 0x40);
}

static bool maxim_max30102_init() {
  Wire.begin();
  Wire.setClock(400000L);

  maxim_max30102_reset();
  delay(1000);

  uint8_t uch_dummy;
  maxim_max30102_read_reg(REG_INTR_STATUS_1, &uch_dummy);
  maxim_max30102_read_reg(REG_INTR_STATUS_2, &uch_dummy);

  if (!maxim_max30102_write_reg(REG_INTR_ENABLE_1, 0xC0)) return false;
  if (!maxim_max30102_write_reg(REG_INTR_ENABLE_2, 0x00)) return false;
  if (!maxim_max30102_write_reg(REG_FIFO_WR_PTR, 0x00)) return false;
  if (!maxim_max30102_write_reg(REG_OVF_COUNTER, 0x00)) return false;
  if (!maxim_max30102_write_reg(REG_FIFO_RD_PTR, 0x00)) return false;
  /* 0x0F: FIFO sample average = 1 (no decimation). With SPO2 100 SPS -> effective 100 Hz for RF (was 0x4F = 4x avg -> ~25 Hz). */
  if (!maxim_max30102_write_reg(REG_FIFO_CONFIG, 0x0F)) return false;
  if (!maxim_max30102_write_reg(REG_MODE_CONFIG, 0x03)) return false;
  /* 0x27: ADC range 4096 nA, sample rate 100 Hz, pulse width 411 µs (see MAX30102 datasheet). */
  if (!maxim_max30102_write_reg(REG_SPO2_CONFIG, 0x27)) return false;
  if (!maxim_max30102_write_reg(REG_LED1_PA, 0x24)) return false;
  if (!maxim_max30102_write_reg(REG_LED2_PA, 0x24)) return false;
  if (!maxim_max30102_write_reg(REG_PILOT_PA, 0x7F)) return false;

  return true;
}

static bool maxim_max30102_read_fifo(uint32_t *pun_red_led, uint32_t *pun_ir_led) {
  uint32_t un_temp;
  uint8_t uch_temp;

  *pun_ir_led = 0;
  *pun_red_led = 0;

  maxim_max30102_read_reg(REG_INTR_STATUS_1, &uch_temp);
  maxim_max30102_read_reg(REG_INTR_STATUS_2, &uch_temp);

  Wire.beginTransmission(max30102_addr7());
  Wire.write(REG_FIFO_DATA);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)max30102_addr7(), (uint8_t)6) != 6) return false;

  un_temp = Wire.read();
  un_temp <<= 16;
  *pun_red_led += un_temp;
  un_temp = Wire.read();
  un_temp <<= 8;
  *pun_red_led += un_temp;
  un_temp = Wire.read();
  *pun_red_led += un_temp;

  un_temp = Wire.read();
  un_temp <<= 16;
  *pun_ir_led += un_temp;
  un_temp = Wire.read();
  un_temp <<= 8;
  *pun_ir_led += un_temp;
  un_temp = Wire.read();
  *pun_ir_led += un_temp;

  *pun_red_led &= 0x03FFFF;
  *pun_ir_led &= 0x03FFFF;

  return true;
}

// =============================================================================
// RF HR/SpO2 algorithm (internal — Robert Fraczkiewicz / MAX30102_by_RF)
// =============================================================================

#define FS PPG_FS_HZ
#define BUFFER_SIZE ((FS * PPG_RF_ST_NUM) / PPG_RF_ST_DEN)

/** sum_{i=0}^{N-1} (i - (N-1)/2)^2 = N(N^2-1)/12 — must match BUFFER_SIZE */
static const float sum_X2 =
  ((float)BUFFER_SIZE * (float)(BUFFER_SIZE * BUFFER_SIZE - 1)) / 12.0f;

#define MAX_HR 180
#define MIN_HR 40

static const float min_autocorrelation_ratio = 0.5f;
static const float min_pearson_correlation = 0.8f;

#define FS60 (FS * 60)
#define LOWEST_PERIOD (FS60 / MAX_HR)
#define HIGHEST_PERIOD (FS60 / MIN_HR)

static const float mean_X = (float)(BUFFER_SIZE - 1) / 2.0f;

static float rf_linear_regression_beta(float *pn_x, float xmean, float sum_x2);
static float rf_autocorrelation(float *pn_x, int32_t n_size, int32_t n_lag);
static float rf_rms(float *pn_x, int32_t n_size, float *sumsq);
static float rf_Pcorrelation(float *pn_x, float *pn_y, int32_t n_size);
static void rf_initialize_periodicity_search(float *pn_x, int32_t n_size, int32_t *p_last_periodicity, int32_t n_max_distance, float min_aut_ratio, float aut_lag0);
static void rf_signal_periodicity(float *pn_x, int32_t n_size, int32_t *p_last_periodicity, int32_t n_min_distance, int32_t n_max_distance, float min_aut_ratio, float aut_lag0, float *ratio);

static void rf_heart_rate_and_oxygen_saturation(
  uint32_t *pun_ir_buffer,
  int32_t n_ir_buffer_length,
  uint32_t *pun_red_buffer,
  float *pn_spo2,
  int8_t *pch_spo2_valid,
  int32_t *pn_heart_rate,
  int8_t *pch_hr_valid,
  float *ratio,
  float *correl
) {
  int32_t k;
  static int32_t n_last_peak_interval = LOWEST_PERIOD;
  float f_ir_mean, f_red_mean, f_ir_sumsq, f_red_sumsq;
  float f_y_ac, f_x_ac, xy_ratio;
  float beta_ir, beta_red, x;
  float an_x[BUFFER_SIZE], *ptr_x;
  float an_y[BUFFER_SIZE], *ptr_y;

  f_ir_mean = 0.0f;
  f_red_mean = 0.0f;
  for (k = 0; k < n_ir_buffer_length; ++k) {
    f_ir_mean += pun_ir_buffer[k];
    f_red_mean += pun_red_buffer[k];
  }
  f_ir_mean /= n_ir_buffer_length;
  f_red_mean /= n_ir_buffer_length;

  for (k = 0, ptr_x = an_x, ptr_y = an_y; k < n_ir_buffer_length; ++k, ++ptr_x, ++ptr_y) {
    *ptr_x = pun_ir_buffer[k] - f_ir_mean;
    *ptr_y = pun_red_buffer[k] - f_red_mean;
  }

  beta_ir = rf_linear_regression_beta(an_x, mean_X, sum_X2);
  beta_red = rf_linear_regression_beta(an_y, mean_X, sum_X2);
  for (k = 0, x = -mean_X, ptr_x = an_x, ptr_y = an_y; k < n_ir_buffer_length; ++k, ++x, ++ptr_x, ++ptr_y) {
    *ptr_x -= beta_ir * x;
    *ptr_y -= beta_red * x;
  }

  f_y_ac = rf_rms(an_y, n_ir_buffer_length, &f_red_sumsq);
  f_x_ac = rf_rms(an_x, n_ir_buffer_length, &f_ir_sumsq);

  {
    const float denom = f_red_sumsq * f_ir_sumsq;
    if (denom < 1e-20f) {
      *correl = 0.0f;
    } else {
      *correl = rf_Pcorrelation(an_x, an_y, n_ir_buffer_length) / sqrtf(denom);
    }
  }

  if (*correl >= min_pearson_correlation) {
    if (LOWEST_PERIOD == n_last_peak_interval) {
      rf_initialize_periodicity_search(an_x, BUFFER_SIZE, &n_last_peak_interval, HIGHEST_PERIOD, min_autocorrelation_ratio, f_ir_sumsq);
    }
    if (n_last_peak_interval != 0) {
      rf_signal_periodicity(an_x, BUFFER_SIZE, &n_last_peak_interval, LOWEST_PERIOD, HIGHEST_PERIOD, min_autocorrelation_ratio, f_ir_sumsq, ratio);
    }
  } else {
    n_last_peak_interval = 0;
  }

  if (n_last_peak_interval != 0) {
    *pn_heart_rate = (int32_t)(FS60 / n_last_peak_interval);
    *pch_hr_valid = 1;
  } else {
    n_last_peak_interval = LOWEST_PERIOD;
    *pn_heart_rate = -999;
    *pch_hr_valid = 0;
    *pn_spo2 = -999;
    *pch_spo2_valid = 0;
    return;
  }

  xy_ratio = (f_y_ac * f_ir_mean) / (f_x_ac * f_red_mean);
  if (xy_ratio > 0.02f && xy_ratio < 1.84f) {
    *pn_spo2 = (-45.060f * xy_ratio + 30.354f) * xy_ratio + 94.845f;
    *pch_spo2_valid = 1;
  } else {
    *pn_spo2 = -999;
    *pch_spo2_valid = 0;
  }
}

static float rf_linear_regression_beta(float *pn_x, float xmean, float sum_x2) {
  float x, beta, *pn_ptr;
  beta = 0.0f;
  for (x = -xmean, pn_ptr = pn_x; x <= xmean; ++x, ++pn_ptr) beta += x * (*pn_ptr);
  return beta / sum_x2;
}

static float rf_autocorrelation(float *pn_x, int32_t n_size, int32_t n_lag) {
  int16_t i, n_temp = n_size - n_lag;
  float sum = 0.0f, *pn_ptr;
  if (n_temp <= 0) return sum;
  for (i = 0, pn_ptr = pn_x; i < n_temp; ++i, ++pn_ptr) {
    sum += (*pn_ptr) * (*(pn_ptr + n_lag));
  }
  return sum / n_temp;
}

static void rf_initialize_periodicity_search(float *pn_x, int32_t n_size, int32_t *p_last_periodicity, int32_t n_max_distance, float min_aut_ratio, float aut_lag0) {
  int32_t n_lag;
  float aut, aut_right;

  n_lag = *p_last_periodicity;
  aut_right = aut = rf_autocorrelation(pn_x, n_size, n_lag);

  if (aut / aut_lag0 >= min_aut_ratio) {
    do {
      aut = aut_right;
      n_lag += 2;
      aut_right = rf_autocorrelation(pn_x, n_size, n_lag);
    } while (aut_right / aut_lag0 >= min_aut_ratio && aut_right < aut && n_lag <= n_max_distance);
    if (n_lag > n_max_distance) {
      *p_last_periodicity = 0;
      return;
    }
    aut = aut_right;
  }

  do {
    aut = aut_right;
    n_lag += 2;
    aut_right = rf_autocorrelation(pn_x, n_size, n_lag);
  } while (aut_right / aut_lag0 < min_aut_ratio && n_lag <= n_max_distance);

  if (n_lag > n_max_distance) *p_last_periodicity = 0;
  else *p_last_periodicity = n_lag;
}

static void rf_signal_periodicity(float *pn_x, int32_t n_size, int32_t *p_last_periodicity, int32_t n_min_distance, int32_t n_max_distance, float min_aut_ratio, float aut_lag0, float *ratio) {
  int32_t n_lag;
  float aut, aut_left, aut_right, aut_save;
  bool left_limit_reached = false;

  n_lag = *p_last_periodicity;
  aut_save = aut = rf_autocorrelation(pn_x, n_size, n_lag);

  aut_left = aut;
  do {
    aut = aut_left;
    n_lag--;
    aut_left = rf_autocorrelation(pn_x, n_size, n_lag);
  } while (aut_left > aut && n_lag >= n_min_distance);

  if (n_lag < n_min_distance) {
    left_limit_reached = true;
    n_lag = *p_last_periodicity;
    aut = aut_save;
  } else {
    n_lag++;
  }

  if (n_lag == *p_last_periodicity) {
    aut_right = aut;
    do {
      aut = aut_right;
      n_lag++;
      aut_right = rf_autocorrelation(pn_x, n_size, n_lag);
    } while (aut_right > aut && n_lag <= n_max_distance);

    if (n_lag > n_max_distance) n_lag = 0;
    else n_lag--;
    if (n_lag == *p_last_periodicity && left_limit_reached) n_lag = 0;
  }

  *ratio = aut / aut_lag0;
  if (*ratio < min_aut_ratio) n_lag = 0;
  *p_last_periodicity = n_lag;
}

static float rf_rms(float *pn_x, int32_t n_size, float *sumsq) {
  int16_t i;
  float r, *pn_ptr;
  (*sumsq) = 0.0f;
  for (i = 0, pn_ptr = pn_x; i < n_size; ++i, ++pn_ptr) {
    r = (*pn_ptr);
    (*sumsq) += r * r;
  }
  (*sumsq) /= n_size;
  return sqrtf(*sumsq);
}

static float rf_Pcorrelation(float *pn_x, float *pn_y, int32_t n_size) {
  int16_t i;
  float r, *x_ptr, *y_ptr;
  r = 0.0f;
  for (i = 0, x_ptr = pn_x, y_ptr = pn_y; i < n_size; ++i, ++x_ptr, ++y_ptr) {
    r += (*x_ptr) * (*y_ptr);
  }
  r /= n_size;
  return r;
}

// =============================================================================
// PPG reader (tick-based)
// =============================================================================

static uint32_t irBuffer[BUFFER_SIZE];
static uint32_t redBuffer[BUFFER_SIZE];

static int collectIdx = 0;
static uint32_t lastNoFingerPrintMs = 0;

#ifndef PPG_METRICS_COMPUTE_MS
#define PPG_METRICS_COMPUTE_MS 0u
#endif
static uint32_t s_lastMetricsComputeMs = 0;
static bool s_metricsEnabled = true;

void ppg_set_metrics_enabled(bool enabled) {
  s_metricsEnabled = enabled;
}

#if PPG_WAVEFORM_STREAM_ENABLED
void ppg_set_plot_mode(bool enabled) {
  // Switch the MAX30102 sample rate to a high rate so that "print latest at 125 Hz" yields fresh values.
  // Keep pulse width at 411us (LED_PW=0b11) like current code; per datasheet this still allows 400 sps.
  // REG_SPO2_CONFIG format: [7:5]=ADC_RGE, [4:2]=SPO2_SR, [1:0]=LED_PW
  // Default init uses 0x27 (ADC_RGE=01, SR=001 (100sps), PW=11).
  // Plot mode uses SR=011 (400sps) -> 0x2F.
  const uint8_t spo2Cfg = enabled ? 0x2F : 0x27;
  (void)maxim_max30102_write_reg(REG_SPO2_CONFIG, spo2Cfg);
  // FIFO averaging must remain 1 (0x0F) to avoid effective decimation.
  (void)maxim_max30102_write_reg(REG_FIFO_CONFIG, 0x0F);
}
#endif

#if PPG_WAVEFORM_STREAM_ENABLED
// Ring buffer of raw IR samples used for stable waveform streaming.
// We keep it independent from HR/SpO2 processing so pressure/BLE can't jitter the output.
static uint32_t s_waveIrRing[PPG_WAVEFORM_RING_SAMPLES];
static volatile uint16_t s_waveWr = 0;
static volatile uint16_t s_waveRd = 0;
static inline uint16_t wave_next(uint16_t v) { return (uint16_t)((v + 1u) % (uint16_t)PPG_WAVEFORM_RING_SAMPLES); }

static void wave_push(uint32_t ir) {
  const uint16_t nextWr = wave_next(s_waveWr);
  if (nextWr == s_waveRd) {
    // Overflow: drop oldest (advance read) to keep newest samples.
    s_waveRd = wave_next(s_waveRd);
  }
  s_waveIrRing[s_waveWr] = ir;
  s_waveWr = nextWr;
}

static bool wave_pop(uint32_t* out) {
  if (s_waveRd == s_waveWr) return false;
  *out = s_waveIrRing[s_waveRd];
  s_waveRd = wave_next(s_waveRd);
  return true;
}

bool ppg_waveform_take_latest(uint32_t* outIr) {
  if (outIr == nullptr) return false;
  uint32_t v = 0;
  bool any = false;
  // Drain whatever accumulated and keep only the newest sample (matches ppg_waveform.ino which
  // prints the latest FIFO value at each output tick).
  while (wave_pop(&v)) {
    any = true;
  }
  if (any) {
    *outIr = v;
  }
  return any;
}

bool ppg_waveform_acquire_latest(uint32_t* outIr) {
  if (outIr == nullptr) return false;
  // Non-blocking check: if FIFO has samples, drain them and return the latest IR value.
  uint8_t wr = 0, rd = 0;
  if (!maxim_max30102_read_reg(REG_FIFO_WR_PTR, &wr)) return false;
  if (!maxim_max30102_read_reg(REG_FIFO_RD_PTR, &rd)) return false;
  if ((wr & 0x1Fu) == (rd & 0x1Fu)) {
    return false; // no samples
  }

  uint32_t red = 0;
  uint32_t ir = 0;
  // Drain up to FIFO depth (32). Keep only the newest value.
  uint8_t guard = 0;
  while (guard < 32) {
    if (!maxim_max30102_read_reg(REG_FIFO_WR_PTR, &wr)) break;
    if (!maxim_max30102_read_reg(REG_FIFO_RD_PTR, &rd)) break;
    if ((wr & 0x1Fu) == (rd & 0x1Fu)) break;
    if (!maxim_max30102_read_fifo(&red, &ir)) break;
#if PPG_WAVEFORM_STREAM_ENABLED
    wave_push(ir); // keep ring populated too, in case you want to switch back to ring-based reads
#endif
    guard++;
  }
  *outIr = ir;
  return true;
}

bool ppg_waveform_next(int32_t* outSample) {
  if (outSample == nullptr) return false;
  uint32_t v = 0;
  if (!wave_pop(&v)) return false;
  // Output as signed int for plotter. Keep "raw-ish": no filtering here.
  *outSample = (int32_t)v;
  return true;
}
#endif

/** Wait for PPG_RDY / non-empty FIFO, then read one sample (used by software mode and INT fallback). */
static bool readNextSampleFromFifo(uint32_t *outRed, uint32_t *outIr, uint32_t timeoutMs) {
  const uint32_t t0 = millis();
  for (;;) {
    if ((millis() - t0) >= timeoutMs) return false;

    uint8_t st = 0;
    if (!maxim_max30102_read_reg(REG_INTR_STATUS_1, &st)) return false;
    if (st & INTR_STATUS_PPG_RDY) break;

    uint8_t wr = 0, rd = 0;
    if (!maxim_max30102_read_reg(REG_FIFO_WR_PTR, &wr)) return false;
    if (!maxim_max30102_read_reg(REG_FIFO_RD_PTR, &rd)) return false;
    if ((wr & 0x1Fu) != (rd & 0x1Fu)) break;

    ble_yield();
    delay(1);
  }
  return maxim_max30102_read_fifo(outRed, outIr);
}

#if PPG_USE_INTERRUPT_PIN
/** Nano 33 BLE D13 shares the LED — INT may never look right; don't spin forever. */
#ifndef PPG_INT_WAIT_MS
#define PPG_INT_WAIT_MS 120
#endif
#endif

#ifndef PPG_MAX_SAMPLES_PER_LOOP
#define PPG_MAX_SAMPLES_PER_LOOP 28
#endif

/** One sample: INT path + FIFO, or software PPG_RDY/FIFO poll. */
static bool ppg_read_one_sample(uint32_t *outRed, uint32_t *outIr) {
#if PPG_USE_INTERRUPT_PIN
  {
    const uint32_t tWait = millis();
    while (digitalRead(PPG_INTERRUPT_PIN) == HIGH) {
      ble_yield();
      if ((millis() - tWait) >= PPG_INT_WAIT_MS) {
        break;
      }
    }
  }
  if (maxim_max30102_read_fifo(outRed, outIr)) return true;
  return readNextSampleFromFifo(outRed, outIr, 80);
#else
  return readNextSampleFromFifo(outRed, outIr, 80);
#endif
}

static PPGData makeInvalidPpg() {
  PPGData d{};
  d.beatsPerMinute = 0;
  d.spO2 = 0.0;
  d.heartRateAvailable = false;
  d.spo2Available = false;
  d.validHeartRate = 0;
  d.validSPO2 = 0;
  d.ratio = 0.f;
  d.correl = 0.f;
  d.fingerDetected = false;
  d.newWindow = false;
  return d;
}

static PPGData latestPublished = makeInvalidPpg();

bool ppg_init() {
#if PPG_USE_INTERRUPT_PIN
  pinMode(PPG_INTERRUPT_PIN, INPUT_PULLUP);
#endif
  if (!maxim_max30102_init()) return false;

  uint8_t partId = 0, revId = 0;
  maxim_max30102_read_reg(REG_PART_ID, &partId);
  maxim_max30102_read_reg(REG_REV_ID, &revId);
  LOG_SYSTEM(Serial.print(F("[PPG] MAX30102 PART_ID=0x")));
  LOG_SYSTEM(Serial.print(partId, HEX));
  LOG_SYSTEM(Serial.print(F(" REV_ID=0x")));
  LOG_SYSTEM(Serial.print(revId, HEX));
  LOG_SYSTEM(Serial.println(F(" (expect PART_ID 0x15 for MAX30102/MAX30105)")));

  collectIdx = 0;
  latestPublished = makeInvalidPpg();
  return true;
}

PPGData ppg_tick() {
  /* Drain multiple FIFO samples when the main loop was slow — keeps ~FS Hz samples in the buffer
   * without needing one full (slow) iteration per sample. */
  if (collectIdx < BUFFER_SIZE) {
    unsigned gained = 0;
    while (collectIdx < BUFFER_SIZE && gained < (unsigned)PPG_MAX_SAMPLES_PER_LOOP) {
      uint32_t red = 0;
      uint32_t ir = 0;
      if (!ppg_read_one_sample(&red, &ir)) {
        break;
      }
      redBuffer[collectIdx] = red;
      irBuffer[collectIdx] = ir;
#if PPG_WAVEFORM_STREAM_ENABLED
      wave_push(ir);
#endif
      collectIdx++;
      gained++;
      ble_yield();
    }

    if (gained == 0) {
      const uint32_t now = millis();
      if (now - lastNoFingerPrintMs > 750) {
        lastNoFingerPrintMs = now;
        LOG_PPG(Serial.println("[PPG] 🛑 No finger detected"));
      }
      if (collectIdx == 0) {
        PPGData stale = latestPublished;
        stale.newWindow = false;
        return stale;
      }
      PPGData stale = latestPublished;
      stale.newWindow = false;
      return stale;
    }
  }

  if (collectIdx < BUFFER_SIZE) {
    PPGData stale = latestPublished;
    stale.newWindow = false;
    return stale;
  }

  // Full RF window collected. Always reset the buffer, but only run expensive HR/SpO2 compute when due.
  if (!s_metricsEnabled) {
    collectIdx = 0;
    PPGData stale = latestPublished;
    stale.newWindow = false;
    return stale;
  }

  if (PPG_METRICS_COMPUTE_MS > 0u) {
    const uint32_t nowMs = (uint32_t)millis();
    if ((uint32_t)(nowMs - s_lastMetricsComputeMs) < (uint32_t)PPG_METRICS_COMPUTE_MS) {
      collectIdx = 0;
      PPGData stale = latestPublished;
      stale.newWindow = false;
      return stale;
    }
    s_lastMetricsComputeMs = nowMs;
  }

  float spo2 = -999.0f;
  int8_t spo2Valid = 0;
  int32_t hr = -999;
  int8_t hrValid = 0;
  float ratio = 0.0f;
  float correl = 0.0f;

  rf_heart_rate_and_oxygen_saturation(
    irBuffer, BUFFER_SIZE, redBuffer,
    &spo2, &spo2Valid,
    &hr, &hrValid,
    &ratio, &correl
  );

  latestPublished.ratio = ratio;
  latestPublished.correl = correl;
  latestPublished.validHeartRate = hrValid;
  latestPublished.validSPO2 = spo2Valid;
  // Match the reference sketch behavior: treat a window as usable only when BOTH HR and SpO2 are valid.
  const bool bothValid = (hrValid == 1) && (spo2Valid == 1);
  latestPublished.heartRateAvailable = bothValid;
  latestPublished.spo2Available = bothValid;
  latestPublished.beatsPerMinute = hr;
  latestPublished.spO2 = spo2;
  latestPublished.fingerDetected = bothValid;
  latestPublished.newWindow = true;

  collectIdx = 0;
  return latestPublished;
}

void ppg_print_serial_on_new_window(const PPGData& d) {
  if (!d.newWindow) return;

  LOG_PPG(Serial.print(F("[PPG] HR valid=")));
  LOG_PPG(Serial.print((int)d.validHeartRate));
  LOG_PPG(Serial.print(F(" SpO2 valid=")));
  LOG_PPG(Serial.print((int)d.validSPO2));
  LOG_PPG(Serial.print(F(" correl=")));
  LOG_PPG(Serial.print(d.correl, 3));
  LOG_PPG(Serial.print(F(" ratio=")));
  LOG_PPG(Serial.print(d.ratio, 3));
  LOG_PPG(Serial.print(F(" HR=")));
  LOG_PPG(Serial.print(d.beatsPerMinute));
  LOG_PPG(Serial.print(F(" SpO2=")));
  LOG_PPG(Serial.println(d.spO2, 1));

  if (d.validHeartRate && d.validSPO2) {
    const int nLines = (PPG_IR_CSV_LINES < BUFFER_SIZE) ? PPG_IR_CSV_LINES : BUFFER_SIZE;
    for (int li = 0; li < nLines; li++) {
      LOG_PPG(Serial.println(irBuffer[li]));
    }
  }
}
