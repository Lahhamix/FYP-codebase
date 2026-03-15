import time
import numpy as np
import argparse
import sys

try:
    import serial
except Exception:
    serial = None

try:
    from ble_transport_cont import BLEFrameReaderCont
except Exception:
    BLEFrameReaderCont = None

# =========================================================
# SETTINGS
# =========================================================
# True = BLE (Velo2, 20-byte packets), False = serial CSV
CONFIG_USE_BLE = True

PORT = "COM10"
BAUD = 115200

ROWS = 16
COLS = 48

BASELINE_FRAMES = 100
OUTPUT_FILE = "baseline_48x16.npy"

# BLE: same as run_unified_pyqt_cont / PH_BLE_12bit.ino (device "Velo2", 20-byte packets)
BLE_CONNECT_TIMEOUT_S = 15
BLE_FRAME_INTERVAL_S = 0.2  # read buffer every 200 ms (rolling buffer gets updated by packets)

# =========================================================
# PARSE SERIAL FRAME (for serial mode: "F," + 768 comma-separated values)
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
    parser.add_argument(
        "--ble",
        action="store_true",
        help="Use BLE mode (connect to Velo2; overrides CONFIG_USE_BLE)",
    )
    parser.add_argument(
        "--serial",
        action="store_true",
        help="Use serial port (overrides CONFIG_USE_BLE)",
    )
    parser.add_argument("--port", default=PORT, help="Serial port to use when not using BLE")
    args = parser.parse_args()

    if args.ble:
        use_ble = True
    elif args.serial:
        use_ble = False
    else:
        use_ble = CONFIG_USE_BLE

    if use_ble:
        if BLEFrameReaderCont is None:
            print("BLE support not available. Install bleak and ensure ble_transport_cont.py is present.")
            sys.exit(1)
        print("Starting BLE reader (device: Velo2, 20-byte pressure packets)...")
        reader = BLEFrameReaderCont()
        # Wait for connection (async connect runs in background)
        deadline = time.monotonic() + BLE_CONNECT_TIMEOUT_S
        while not reader.is_connected() and time.monotonic() < deadline:
            time.sleep(0.2)
        if not reader.is_connected():
            print("BLE connection timeout. Is the device on and advertising as 'Velo2'?")
            sys.exit(1)
        print("BLE connected. Waiting for packets to fill buffer...")
        time.sleep(1.0)
    else:
        if serial is None:
            print("pyserial not available. Install pyserial to use serial mode.")
            sys.exit(1)
        print(f"Opening serial port {args.port} at {BAUD} baud...")
        reader = serial.Serial(args.port, BAUD, timeout=1)
        time.sleep(2)

    print()
    print("=====================================================")
    print("CALIBRATION STARTING")
    print("Make sure NO FOOT / NO PRESSURE is applied.")
    print("The sensor should be completely unloaded.")
    print("=====================================================")
    print()

    frames = []

    if use_ble:
        # BLE: rolling buffer (same as run_unified_pyqt_cont). Sample buffer at intervals.
        while len(frames) < BASELINE_FRAMES:
            frame = reader.read_latest_frame()  # (ROWS, COLS) from 20-byte packets
            frames.append(frame.copy())
            print(f"Collecting frame {len(frames)}/{BASELINE_FRAMES}", end="\r")
            time.sleep(BLE_FRAME_INTERVAL_S)
    else:
        # Serial: "F," + 768 values per line
        while len(frames) < BASELINE_FRAMES:
            rawline = reader.readline()
            if rawline is None:
                continue

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

    if use_ble:
        try:
            reader.close()
        except Exception:
            pass
    else:
        try:
            reader.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()