#!/usr/bin/env python3
"""
Convert a NumPy .npy baseline file to pressure_baseline.dat for the Android app.

The app expects: 768 floats, 4 bytes each (IEEE 754 float32, little-endian),
row-major order (16 rows x 48 cols).

Usage:
  python npy_to_baseline_dat.py
      (uses baseline_48x16.npy in this folder)
  python npy_to_baseline_dat.py path/to/your_baseline.npy
  python npy_to_baseline_dat.py -o pressure_baseline.dat

Output: pressure_baseline.dat (or path given with -o)
Then push to the device:
  adb push pressure_baseline.dat /sdcard/Download/
  adb shell run-as com.example.ble_viewer cp /sdcard/Download/pressure_baseline.dat files/
  (or use Device File Explorer in Android Studio: copy into app's files dir)
"""

import argparse
import os
import sys
import numpy as np

ROWS = 16
COLS = 48
NUM_VALUES = ROWS * COLS  # 768

# Default paths
DEFAULT_NPY_PATH = r"C:\Users\hadie\Desktop\Desktop\FYP_Codebase\FYP-codebase\arduinobackend\PH_BLE\baseline_48x16.npy"
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUTPUT_PATH = os.path.join(_SCRIPT_DIR, "pressure_baseline.dat")


def main():
    parser = argparse.ArgumentParser(description="Convert .npy baseline to pressure_baseline.dat")
    parser.add_argument(
        "npy_path",
        nargs="?",
        default=DEFAULT_NPY_PATH,
        help=f"Path to the .npy baseline file (default: {DEFAULT_NPY_PATH})",
    )
    parser.add_argument("-o", "--output", default=DEFAULT_OUTPUT_PATH, help="Output .dat path")
    args = parser.parse_args()

    try:
        arr = np.load(args.npy_path)
    except Exception as e:
        print(f"Failed to load {args.npy_path}: {e}", file=sys.stderr)
        sys.exit(1)

    # Flatten and ensure 768 elements, row-major (C order)
    flat = np.asarray(arr).flatten(order="C")
    if flat.size != NUM_VALUES:
        print(f"Expected {NUM_VALUES} values, got {flat.size}. Reshaping to ({ROWS}, {COLS}) if possible.", file=sys.stderr)
        if flat.size < NUM_VALUES:
            print("Not enough values. Padding with zeros.", file=sys.stderr)
            padded = np.zeros(NUM_VALUES, dtype=np.float32)
            padded[: flat.size] = flat
            flat = padded
        else:
            flat = flat[:NUM_VALUES]

    # float32 little-endian (matches Java Float.intBitsToFloat in the app)
    out = np.asarray(flat, dtype=np.float32)
    out.tofile(args.output)
    print(f"Wrote {NUM_VALUES} floats ({out.nbytes} bytes) to {args.output}")
    print("Push to device: adb push", args.output, "/sdcard/Download/")


if __name__ == "__main__":
    main()
