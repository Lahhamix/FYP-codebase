# Pressure pipeline: Python (PH_BLE) vs Android

## Data path (identical by design)

| Step | Python (ble_transport_cont + run_unified_pyqt_cont) | Android (MainActivity + PressureMatrixActivity) |
|------|-----------------------------------------------------|-------------------------------------------------|
| **Source** | Same Arduino: 20-byte BLE packets (0xA5 0x5A, startIndex, sampleCount, 12-byte payload) | Same |
| **Unpack** | 12-bit pairs: `a = b0 \| (b1&0x0F)<<8`, `b = (b1>>4)&0x0F \| b2<<4` | Same formula (Kotlin) |
| **Buffer** | 768 float32, row-major; `buffer[start:end] = values` | 768 float32, row-major; same indexing |
| **Baseline** | (16,48) from baseline_48x16.npy, row-major | 768 float32 from pressure_baseline.dat (from same .npy via npy_to_baseline_dat.py) |
| **Preprocess** | `mat = raw - baseline`, clamp < 0, then `mat[mat < THRESHOLD_RAW]=0`, then `/4095` | Same: `v = frame - base`, clamp, then `v < THRESHOLD_RAW -> 0`, then `/ADC_MAX` |

So the **formulas and packet format are the same**. Values are 0–4095 (12-bit ADC); threshold 1000 is applied to the **delta** (raw − baseline).

## Why 1000 can “work” on Python but not on Android

1. **Baseline file on the device**
   - If the **same** no-pressure baseline is used in both, deltas when you press should be similar (e.g. 1000–3000).
   - If the baseline on the **phone** is different (e.g. recorded with pressure, or an old/wrong file), then on Android `(frame − baseline)` can be ≤ 0 or < 1000 everywhere, so you see no activity even when pressing.
   - **Check**: Re-create baseline with no pressure (`cal.py`), run `npy_to_baseline_dat.py`, push `pressure_baseline.dat` to the app’s files dir and confirm Logcat shows baseline min/max in a similar range as in Python.

2. **When the “frame” is read (rolling buffer)**
   - Arduino sends **96 packets per physical frame** (768/8). With ~2 ms between packets, a full frame takes ~200 ms. So the “frame” is a rolling buffer that gets overwritten by the latest scan over time.
   - **Python**: GUI timer **5 ms** → reads the buffer very often; more likely to catch a snapshot where the pressed region has already been updated.
   - **Android**: Heatmap was updated every **50 ms** → fewer snapshots; more likely to read the buffer before the packets for the pressed region have arrived, so the buffer still looks like “no pressure” or mixed.
   - So even with the same threshold, Android can show “no activity” because the **displayed snapshot** often doesn’t yet contain the high-pressure cells.

## Changes made to align behavior

- **Refresh rate**: Android heatmap update interval reduced from 50 ms to **20 ms** (closer to Python’s 5 ms) so the rolling buffer is sampled more often and is more likely to include the pressed region.
- **Baseline**: Ensure the **same** no-pressure baseline is on the phone (same .npy → same .dat → same file in app files). If Logcat shows “all zeros” or baseline max very high, re-record no-pressure baseline and re-push.

## If you still see no activity on Android

- Confirm in Logcat: after loading baseline, you see `Baseline loaded: ... min=X max=Y` with Y in a reasonable no-pressure range (e.g. &lt; 1500).
- Confirm when you press, `frame raw max` in the periodic stats goes above baseline (e.g. 2000–4000). If frame max stays low, the rolling buffer may not be getting the pressed packets in time; try lowering the heatmap refresh interval further (e.g. 10 ms).
- Optionally lower the threshold on Android (e.g. 300–500) for testing; if activity appears, the issue is baseline or timing, not the formula.
