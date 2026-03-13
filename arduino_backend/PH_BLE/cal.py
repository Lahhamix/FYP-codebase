import time
import numpy as np
import argparse
import sys

try:
    import serial
except Exception:
    serial = None

try:
    from ble_transport import BLEReader
except Exception:
    BLEReader = None

# =========================================================
# SETTINGS
# =========================================================
PORT = "COM10"
BAUD = 115200

ROWS = 16
COLS = 48

BASELINE_FRAMES = 100
OUTPUT_FILE = "baseline_48x16.npy"

# =========================================================
# PARSE SERIAL FRAME
# =========================================================
def parse_frame(line: str):
    line = line.strip()

    if not line.startswith("F,"):
        return None

    parts = line.split(",")
    expected = 1 + ROWS * COLS

    if len(parts) != expected:
        return None

    try:
        vals = np.array(list(map(float, parts[1:])), dtype=np.float32)
    except ValueError:
        return None

    return vals.reshape(ROWS, COLS)

# =========================================================
# MAIN
# =========================================================
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ble", action="store_true", help="Use BLE mode (connect to Velo2 BLE peripheral)")
    parser.add_argument("--port", default=PORT, help="Serial port to use when not using BLE")
    args = parser.parse_args()

    use_ble = args.ble

    if use_ble:
        if BLEReader is None:
            print("BLE support not available. Install bleak and ensure ble_transport.py is present.")
            sys.exit(1)
        print("Starting BLE reader...")
        ser = BLEReader()
        time.sleep(1.0)
    else:
        if serial is None:
            print("pyserial not available. Install pyserial to use serial mode.")
            sys.exit(1)
        print(f"Opening serial port {args.port} at {BAUD} baud...")
        ser = serial.Serial(args.port, BAUD, timeout=1)
        time.sleep(2)

    print()
    print("=====================================================")
    print("CALIBRATION STARTING")
    print("Make sure NO FOOT / NO PRESSURE is applied.")
    print("The sensor should be completely unloaded.")
    print("=====================================================")
    print()

    frames = []

    while len(frames) < BASELINE_FRAMES:
        rawline = ser.readline()
        if rawline is None:
            continue

        # support both BLEReader (returns str) and serial.Serial (returns bytes)
        if isinstance(rawline, bytes):
            line = rawline.decode(errors="ignore")
        else:
            line = rawline

        frame = parse_frame(line)

        if frame is not None:
            frames.append(frame)
            print(f"Collecting frame {len(frames)}/{BASELINE_FRAMES}", end="\r")

    print()

    baseline = np.mean(frames, axis=0)
    np.save(OUTPUT_FILE, baseline)

    print(f"Baseline saved successfully to: {OUTPUT_FILE}")
    print(f"Baseline shape: {baseline.shape}")
    print(f"Min baseline value: {baseline.min():.2f}")
    print(f"Max baseline value: {baseline.max():.2f}")
    print(f"Mean baseline value: {baseline.mean():.2f}")

    try:
        ser.close()
    except Exception:
        pass

if __name__ == "__main__":
    main()