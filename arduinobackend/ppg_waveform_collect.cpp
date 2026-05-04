#include "ppg_waveform_collect.h"
#include <string.h>

static int32_t s_buf[PPG_WAVEFORM_COLLECT_SAMPLES];
static uint16_t s_n = 0;
static volatile bool s_ready = false;

void ppg_waveform_collect_reset() {
  s_n = 0;
  s_ready = false;
}

bool ppg_waveform_collect_has_window() {
  return s_ready;
}

uint16_t ppg_waveform_collect_count() {
  return s_n;
}

bool ppg_waveform_collect_push(int32_t sample) {
  if (s_ready) return false;
  if (s_n >= (uint16_t)PPG_WAVEFORM_COLLECT_SAMPLES) {
    s_ready = true;
    return false;
  }
  s_buf[s_n++] = sample;
  if (s_n >= (uint16_t)PPG_WAVEFORM_COLLECT_SAMPLES) {
    s_ready = true;
  }
  return true;
}

bool ppg_waveform_collect_take(int32_t* out625) {
  if (out625 == nullptr || !s_ready) return false;
  memcpy(out625, s_buf, sizeof(s_buf));
  return true;
}

