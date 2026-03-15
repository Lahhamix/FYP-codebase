Tuning guide for `run_dynamic.py` and `run_pix.py`

Purpose
- Explain how changing key parameters affects the visualization and touch spread.
- Provide quick recommended edits and test steps to obtain pixel-precise highlights.

Files
- run_dynamic.py — dynamic, smoothed, upscaled heatmap (blurry by design).
- run_pix.py — raw pixelated display (nearest-neighbor, precise cells).

How smoothing/upscaling affects touch size
- Smoothing (Gaussian blur): spreads energy from a single sensor to its neighbors. Larger `SMOOTH_SIGMA` produces more spatial spread (bigger highlighted area).
- Display smoothing (`DISPLAY_SMOOTH`): applied after upscaling; further blurs the already-interpolated image.
- Upscaling (`UPSCALE` + `zoom(order)`) + interpolation: increases resolution by interpolating between original cells. High `UPSCALE` with cubic interpolation makes small signals look like large soft blobs.
- `imshow` `interpolation` setting: `bicubic` / `bilinear` smooths the image on display; `nearest` shows blocky pixels.

Key parameters and effects (file: run_dynamic.py)
- `SMOOTH_SIGMA` (float)
  - Where used: `gaussian_filter(mat, sigma=SMOOTH_SIGMA)` in `preprocess()`.
  - Effect: larger values blur raw sensor map, increasing touch area and reducing peak amplitude.
  - Recommended: 0.0 for no smoothing; 0.2–0.6 for minimal smoothing.

- `DISPLAY_SMOOTH` (float)
  - Where used: `gaussian_filter(up, sigma=DISPLAY_SMOOTH)` in `upscale_matrix()`.
  - Effect: blurs the upscaled image; increases apparent touch size.
  - Recommended: 0.0 to keep blocky pixels; 0.5 for light smoothing.

- `UPSCALE` (int)
  - Where used: `zoom(mat, (UPSCALE, UPSCALE), order=...)`.
  - Effect: larger values create visually bigger blobs because more interpolated pixels are generated.
  - Recommended: 4–8 for moderate resolution; 12+ may exaggerate smoothing effects. Use smaller values to reduce perceived area.

- `zoom(..., order=)` interpolation order
  - Where used: `zoom(mat, ..., order=3)` by default (cubic).
  - Effect: `order=3` (cubic) interpolates smoothly; `order=1` is linear; `order=0` is nearest-neighbor (no interpolation — preserves cell boundaries after upscaling).
  - Recommended: use `order=0` for pixel-precise highlights.

- `imshow(..., interpolation=...)`
  - Effect: `nearest` shows blocky cells (precise); `bicubic`/`bilinear` smooths the image further regardless of zoom.
  - Recommended: set `interpolation='nearest'` to match `run_pix.py` behavior.

- `THRESHOLD_RAW` (int) and post-smoothing thresholds
  - Where used: raw thresholding `mat[mat < THRESHOLD_RAW] = 0` (before normalization), and later small thresholding `mat[mat < thr_norm * 0.5] = 0`.
  - Effect: low thresholds keep small spread values visible; higher thresholds remove faint spread signals, tightening the highlighted area.
  - Recommended: if smoothing is needed, increase threshold (e.g., raise `THRESHOLD_RAW` by 25–100) or add relative thresholding after smoothing like `mat[mat < 0.2 * mat.max()] = 0`.

- `GAMMA`
  - Where used: `mat[mask] = np.power(mat[mask], GAMMA)`.
  - Effect: values <1 (e.g., 0.85) compress mid-range values and emphasize or de-emphasize peaks. It won't reduce spatial spread much but changes contrast.
  - Recommended: keep default unless you need contrast adjustments.

- `ADC_MAX`, baseline and orientation
  - `ADC_MAX` affects normalization scale; baseline subtraction adjusts raw offsets. These do not affect spread shape directly but do affect what passes thresholding.

Quick recommended edits to get pixel-precise highlights (try in this order, one change at a time)
1. Set smoothing off:

```python
SMOOTH_SIGMA = 0.0
DISPLAY_SMOOTH = 0.0
```

2. Use nearest-neighbor upscaling and display:

```python
# in upscale_matrix
up = zoom(mat, (UPSCALE, UPSCALE), order=0)

# when creating img
img = ax.imshow(..., interpolation='nearest', ...)
```

3. Reduce `UPSCALE` if image appears overlarge (e.g., 12 → 4).
4. If small halo persists, increase thresholding:

```python
# after smoothing (or even if SMOOTH_SIGMA=0), add:
if mat.max() > 0:
    mat[mat < 0.2 * mat.max()] = 0
```

5. Optionally raise `THRESHOLD_RAW` slightly (e.g., +50) to suppress low-level noise that becomes visible after smoothing.

How to test changes quickly
- Run the dynamic script from the workspace root (Windows):

```bash
python run_dynamic.py
```

- Make one parameter change, save, and re-run. Observe how the highlighted area changes when you press the same point.

Examples: what you will see
- `SMOOTH_SIGMA=1.2`, `DISPLAY_SMOOTH=1.0`, `UPSCALE=12`, `order=3`, `interpolation='bicubic'` → large soft blob (current behavior).
- `SMOOTH_SIGMA=0.0`, `DISPLAY_SMOOTH=0.0`, `UPSCALE=12`, `order=0`, `interpolation='nearest'` → blocky pixels enlarged by upscaling but still precise to the hit cells.
- `SMOOTH+small threshold` → slight softening but cleaned-up halo.

Notes and best practice
- Edit one parameter at a time so you can attribute changes to a specific setting.
- If you want the visual appeal of smoothing but tighter touch areas, use a small `SMOOTH_SIGMA` (0.2–0.5) and then apply a relative post-smoothing threshold (e.g., `mat[mat < 0.2 * mat.max()] = 0`).
- Keep a backup copy of each file before changes (or use version control).

If you want, I can make a small branch or copy of `run_dynamic.py` with a `PRECISION_MODE` section that toggles these settings for you to switch quickly. Would you like that?