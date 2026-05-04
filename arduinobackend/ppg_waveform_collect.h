#ifndef PPG_WAVEFORM_COLLECT_H
#define PPG_WAVEFORM_COLLECT_H

#include <Arduino.h>

#ifndef PPG_WAVEFORM_COLLECT_SAMPLES
// Matches plot_ppg_model.py default capture length (625 samples).
#define PPG_WAVEFORM_COLLECT_SAMPLES 625u
#endif

/** Reset capture state (clears any pending ready window). */
void ppg_waveform_collect_reset();

/** True if a full 625-sample window is ready (captured in plot mode). */
bool ppg_waveform_collect_has_window();

/**
 * Push one already-processed waveform sample (the same value you print to Serial in plot mode).
 * Returns true if the sample was accepted. When 625 samples are collected, the window becomes ready
 * and subsequent pushes return false until reset/consumed.
 */
bool ppg_waveform_collect_push(int32_t sample);

/**
 * Copy the ready window into out625 without clearing it.
 * The caller should clear it only after the saved window has finished transmitting.
 * Returns true if a window was available.
 */
bool ppg_waveform_collect_take(int32_t* out625);

/** Get how many samples have been collected in the current (not-yet-ready) window. */
uint16_t ppg_waveform_collect_count();

#endif

