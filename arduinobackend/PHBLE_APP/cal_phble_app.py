import argparse
import asyncio
import os
import sys
import threading
import time

import numpy as np

try:
    import serial
except Exception:
    serial = None

try:
    from bleak import BleakClient, BleakScanner
except Exception:
    BleakClient = None
    BleakScanner = None


ROWS = 43
COLS = 14
NUM_VALUES = ROWS * COLS

PORT = "COM9"
BAUD = 115200
CONFIG_USE_BLE = True
BASELINE_FRAMES = 100

DEVICE_NAME = "Velo2"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"

MAGIC0 = 0xA5
MAGIC1 = 0x5A
FLAG_FRAME_END = 0x02

BLE_CONNECT_TIMEOUT_S = 15
SERIAL_FRAME_TIMEOUT_S = 20

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_FILE = os.path.join(_SCRIPT_DIR, "baseline_phble_app_43x14.npy")
_last_bad_frame_count = None


class BLECompactFrameReader:
    def __init__(self):
        if BleakClient is None or BleakScanner is None:
            raise RuntimeError("BLE support requires bleak. Install with: pip install bleak")

        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

        self.lock = threading.Lock()
        self.client = None
        self.connected = False
        self.working = np.zeros(NUM_VALUES, dtype=np.float32)
        self.completed = None
        self.completed_id = -1
        self.last_returned_id = -1
        self.packets = 0
        self.bad_magic = 0
        self.bad_size = 0

        asyncio.run_coroutine_threadsafe(self._connect(), self.loop)

    async def _connect(self):
        print("[BLE] Scanning for Velo2...")
        device = None
        for found in await BleakScanner.discover():
            if found.name == DEVICE_NAME:
                device = found
                break

        if device is None:
            print("[BLE] Device not found.")
            return

        print(f"[BLE] Found device: {device.name}")
        self.client = BleakClient(device)
        await self.client.connect()
        await self.client.start_notify(CHAR_UUID, self._notification_handler)
        self.connected = True
        print("[BLE] Connected and notifications started.")

    def is_connected(self):
        return self.connected

    def _notification_handler(self, sender, data):
        self.packets += 1

        if len(data) != 20:
            self.bad_size += 1
            return

        if data[0] != MAGIC0 or data[1] != MAGIC1:
            self.bad_magic += 1
            return

        frame_id = data[2] | (data[3] << 8)
        start_index = data[4] | (data[5] << 8)
        sample_count = data[6]
        flags = data[7]

        if start_index >= NUM_VALUES or sample_count <= 0:
            return

        values = self._unpack12(data[8:], sample_count)
        end = min(start_index + sample_count, NUM_VALUES)

        with self.lock:
            self.working[start_index:end] = values[: end - start_index]
            if flags & FLAG_FRAME_END:
                self.completed = self.working.copy().reshape(ROWS, COLS)
                self.completed_id = frame_id

    def _unpack12(self, payload, count):
        values = []
        i = 0
        p = 0
        while i + 1 < count and p + 2 < len(payload):
            b0 = payload[p]
            b1 = payload[p + 1]
            b2 = payload[p + 2]
            values.append(b0 | ((b1 & 0x0F) << 8))
            values.append(((b1 >> 4) & 0x0F) | (b2 << 4))
            p += 3
            i += 2

        if i < count and p + 1 < len(payload):
            b0 = payload[p]
            b1 = payload[p + 1]
            values.append(b0 | ((b1 & 0x0F) << 8))

        return np.array(values, dtype=np.float32)

    def read_next_frame(self, timeout_s=2.0):
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            with self.lock:
                if self.completed is not None and self.completed_id != self.last_returned_id:
                    self.last_returned_id = self.completed_id
                    return self.completed.copy()
            time.sleep(0.01)
        return None

    def close(self):
        if self.client is not None:
            fut = asyncio.run_coroutine_threadsafe(self.client.disconnect(), self.loop)
            try:
                fut.result(timeout=2)
            except Exception:
                pass


def parse_serial_frame(line):
    global _last_bad_frame_count

    line = line.strip()
    if not line.startswith("F,"):
        return None

    parts = line.split(",")
    expected = 1 + NUM_VALUES
    if len(parts) != expected:
        if len(parts) != _last_bad_frame_count:
            got_values = max(0, len(parts) - 1)
            print(
                f"\nIgnoring frame with {got_values} values; expected {NUM_VALUES}. "
                "Upload PHBLE_APP.ino / PHBLE12bit.ino with NUM_ROWS=43 and NUM_COLS=14."
            )
            _last_bad_frame_count = len(parts)
        return None

    try:
        vals = np.array([float(x) for x in parts[1:]], dtype=np.float32)
    except ValueError:
        return None

    return vals.reshape(ROWS, COLS)


def print_matrix(mat):
    header = "      " + " ".join(f"C{col:02d}" for col in range(COLS))
    print(header)
    print("      " + " ".join("------" for _ in range(COLS)))
    for row_idx, row in enumerate(mat):
        values = " ".join(f"{value:6.1f}" for value in row)
        print(f"R{row_idx:02d}: {values}")


def print_saved_baseline(path):
    if not os.path.exists(path):
        print(f"Baseline file not found: {path}")
        sys.exit(1)

    baseline = np.load(path).astype(np.float32)
    if baseline.shape != (ROWS, COLS):
        print(f"Baseline shape mismatch. Expected {(ROWS, COLS)}, got {baseline.shape}")
        sys.exit(1)

    print(f"Baseline matrix from: {path}")
    print(f"Shape: {baseline.shape}")
    print(f"Min: {baseline.min():.2f} | Max: {baseline.max():.2f} | Mean: {baseline.mean():.2f}")
    print()
    print_matrix(baseline)


def collect_ble_frames(count):
    reader = BLECompactFrameReader()
    try:
        deadline = time.monotonic() + BLE_CONNECT_TIMEOUT_S
        while not reader.is_connected() and time.monotonic() < deadline:
            time.sleep(0.2)

        if not reader.is_connected():
            print("BLE connection timeout. Is the board on and advertising as Velo2?")
            sys.exit(1)

        frames = []
        while len(frames) < count:
            frame = reader.read_next_frame(timeout_s=3.0)
            if frame is None:
                print("\nWaiting for complete BLE frames...")
                continue
            frames.append(frame)
            print(f"Collecting frame {len(frames)}/{count}", end="\r")
        return frames
    finally:
        reader.close()


def collect_serial_frames(port, count):
    if serial is None:
        print("pyserial is required. Install with: pip install pyserial")
        sys.exit(1)

    print(f"Opening serial port {port} at {BAUD} baud...")
    ser = serial.Serial(port, BAUD, timeout=1)
    try:
        time.sleep(2)
        ser.reset_input_buffer()
        frames = []
        deadline = time.monotonic() + SERIAL_FRAME_TIMEOUT_S

        while len(frames) < count:
            rawline = ser.readline()
            if not rawline:
                if time.monotonic() >= deadline:
                    print()
                    print("Timed out waiting for valid serial frames.")
                    print("For serial calibration, upload a sketch with USE_BLE=0.")
                    sys.exit(1)
                continue

            line = rawline.decode(errors="ignore") if isinstance(rawline, bytes) else rawline
            frame = parse_serial_frame(line)

            if frame is not None:
                frames.append(frame)
                deadline = time.monotonic() + SERIAL_FRAME_TIMEOUT_S
                print(f"Collecting frame {len(frames)}/{count}", end="\r")

        return frames
    finally:
        ser.close()


def main():
    parser = argparse.ArgumentParser(description="Calibrate PHBLE_APP compact 43x14 baseline")
    parser.add_argument("--ble", action="store_true", help="Use BLE mode")
    parser.add_argument("--serial", action="store_true", help="Use serial CSV mode")
    parser.add_argument("--port", default=PORT, help="Serial port for serial mode")
    parser.add_argument("--frames", type=int, default=BASELINE_FRAMES, help="Frames to average")
    parser.add_argument("--output", default=OUTPUT_FILE, help="Output .npy baseline path")
    parser.add_argument("--print-baseline", action="store_true", help="Print saved baseline and exit")
    args = parser.parse_args()

    if args.print_baseline:
        print_saved_baseline(args.output)
        return

    use_ble = CONFIG_USE_BLE
    if args.ble:
        use_ble = True
    elif args.serial:
        use_ble = False

    print()
    print("=====================================================")
    print("PHBLE_APP BASELINE CALIBRATION")
    print("Make sure NO FOOT / NO PRESSURE is applied.")
    print(f"Expected frame size: {ROWS} x {COLS} = {NUM_VALUES} values")
    print(f"Transport: {'BLE' if use_ble else 'Serial'}")
    print("=====================================================")
    print()

    frames = collect_ble_frames(args.frames) if use_ble else collect_serial_frames(args.port, args.frames)
    print()

    baseline = np.mean(frames, axis=0).astype(np.float32)
    np.save(args.output, baseline)

    print(f"Baseline saved successfully to: {args.output}")
    print(f"Baseline shape: {baseline.shape}")
    print(f"Min baseline value: {baseline.min():.2f}")
    print(f"Max baseline value: {baseline.max():.2f}")
    print(f"Mean baseline value: {baseline.mean():.2f}")


if __name__ == "__main__":
    main()
