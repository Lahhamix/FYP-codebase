# Blood Pressure model (ExecuTorch)

Place the exported ExecuTorch program here:

- `bp_model.pte`

This app loads it from assets at:

- `models/bp_model.pte`

## How to generate `bp_model.pte`

From the repo root (after placing `best_model.pt` under `testing/`):

```bash
cd testing
python export_bp_to_pte.py --checkpoint best_model.pt --out ../app/src/main/assets/models/bp_model.pte --cpu
```

Training calls `model(ppg, ppg1, ppg2)` with internal `torch.stft`. The `.pte` is the ExecuTorch-friendly twin with **six** float32 inputs: three waveforms `(1,1,625)` plus three spectrograms `(1,20,65)` — same weights; see `testing/model_architecture.py` (`BPEstimationModel` vs `BPEstimationModelForExecuTorch`).

Requires Python packages compatible with app Gradle `executorch-android` (for example ExecuTorch **1.2.x**). Reinstall the app (or bump `versionCode`) so `BpModelRunner` refreshes its extracted copy under internal storage.

