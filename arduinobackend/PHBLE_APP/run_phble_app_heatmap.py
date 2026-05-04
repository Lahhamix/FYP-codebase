import argparse
import asyncio
import os
import sys
import threading
import time

import numpy as np

try:
    from scipy.ndimage import binary_closing, binary_fill_holes, binary_opening, gaussian_filter, zoom
except Exception:
    binary_closing = None
    binary_fill_holes = None
    binary_opening = None
    gaussian_filter = None
    zoom = None

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets
except Exception:
    print("pyqtgraph and PyQt are required. Install with:")
    print("pip install pyqtgraph pyqt5")
    sys.exit(1)

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

ADC_MAX = 4095.0
PORT = "COM10"
BAUD = 115200
CONFIG_USE_BLE = True

DEVICE_NAME = "Velo2"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"

MAGIC0 = 0xA5
MAGIC1 = 0x5A
FLAG_FRAME_END = 0x02

BASELINE_FILE = "baseline_phble_app_43x14.npy"

# Display mode: "smooth" for interpolated footprint view, "pix" for raw cell grid.
CONFIG_MODE = "smooth"

# Visual tuning matched to run_phble12bit_heatmap.py.
THRESHOLD_RAW = 1000.0
LOW_RES_SMOOTH_SIGMA = 0.85
DISPLAY_UPSCALE = 36
DISPLAY_SMOOTH_SIGMA = 2.2
GAMMA = 0.65
PERCENTILE_MAX = 97.5
DISPLAY_CUTOFF = 0.035
MASK_CUTOFF = 0.025
GUI_TIMER_MS = 12
STATS_INTERVAL = 1.5

# Display orientation only. This does not change calibration or raw data order.
ROT90_K = 0
FLIP_LR = True
FLIP_UD = False

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
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

    def read_latest_frame(self):
        with self.lock:
            if self.completed is None:
                return None
            if self.completed_id == self.last_returned_id:
                return None
            self.last_returned_id = self.completed_id
            return self.completed.copy()

    def stats(self):
        return {
            "packets": self.packets,
            "bad_magic": self.bad_magic,
            "bad_size": self.bad_size,
            "last_frame_id": self.completed_id,
        }

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
                f"[WARN] Ignoring frame with {got_values} values; expected {NUM_VALUES}. "
                "Upload PHBLE_APP.ino / PHBLE12bit.ino with NUM_ROWS=43 and NUM_COLS=14."
            )
            _last_bad_frame_count = len(parts)
        return None

    try:
        vals = np.array([float(x) for x in parts[1:]], dtype=np.float32)
    except ValueError:
        return None

    return vals.reshape(ROWS, COLS)


def make_pressure_lut():
    colors = np.array(
        [
            [255, 255, 255],  # clean background
            [25, 70, 220],    # deep blue edge
            [0, 170, 255],    # cyan transition
            [0, 220, 95],     # green
            [255, 245, 0],    # yellow
            [255, 150, 0],    # orange
            [225, 0, 0],      # red peak
        ],
        dtype=np.ubyte,
    )
    pos = np.array([0.0, 0.07, 0.18, 0.36, 0.62, 0.82, 1.0])
    return pg.ColorMap(pos, colors).getLookupTable(0.0, 1.0, 256).astype(np.ubyte)


def resolve_baseline_path(path):
    if os.path.isabs(path):
        return path
    local_path = os.path.join(_SCRIPT_DIR, path)
    if os.path.exists(local_path):
        return local_path
    return path


def load_baseline(path, use_calibration):
    if not use_calibration:
        print("Calibration disabled: using zero baseline.")
        return np.zeros((ROWS, COLS), dtype=np.float32)

    resolved = resolve_baseline_path(path)
    if not os.path.exists(resolved):
        print(f"Baseline not found: {resolved}")
        print("Using zero baseline. Run cal_phble_app.py for proper calibration.")
        return np.zeros((ROWS, COLS), dtype=np.float32)

    baseline = np.load(resolved).astype(np.float32)
    if baseline.shape != (ROWS, COLS):
        print(f"Baseline shape mismatch. Expected {(ROWS, COLS)}, got {baseline.shape}")
        print("Using zero baseline. Re-run cal_phble_app.py.")
        return np.zeros((ROWS, COLS), dtype=np.float32)

    print(f"Baseline loaded: {resolved}")
    return baseline


def preprocess(raw, baseline, threshold):
    mat = raw.astype(np.float32) - baseline
    mat[mat < 0] = 0
    mat[mat < threshold] = 0

    if gaussian_filter is not None and LOW_RES_SMOOTH_SIGMA > 0:
        mat = gaussian_filter(mat, sigma=LOW_RES_SMOOTH_SIGMA)
        mat[mat < threshold * 0.22] = 0

    nonzero = mat[mat > 0]
    if nonzero.size == 0:
        return np.zeros_like(mat)

    vmax = float(np.percentile(nonzero, PERCENTILE_MAX))
    if vmax <= 1e-6:
        vmax = ADC_MAX

    mat = np.clip(mat / vmax, 0.0, 1.0)
    active = mat > 0
    mat[active] = np.power(mat[active], GAMMA)
    return mat


def preprocess_pix(raw, baseline, threshold):
    mat = raw.astype(np.float32) - baseline
    mat[mat < 0] = 0
    mat[mat < threshold] = 0
    mat = mat / ADC_MAX
    return np.clip(mat, 0.0, 1.0)


def apply_orientation(mat, rot90_k=0, flip_lr=False, flip_ud=False):
    out = mat.copy()
    if rot90_k != 0:
        out = np.rot90(out, rot90_k)
    if flip_lr:
        out = np.fliplr(out)
    if flip_ud:
        out = np.flipud(out)
    return out


def cleanup_mask(mask):
    if binary_closing is None or binary_fill_holes is None or binary_opening is None:
        return mask

    cleaned = binary_closing(mask, iterations=3)
    cleaned = binary_fill_holes(cleaned)
    cleaned = binary_opening(cleaned, iterations=1)
    return cleaned


def upscale_display(mat):
    if zoom is not None:
        display = zoom(mat, (DISPLAY_UPSCALE, DISPLAY_UPSCALE), order=3)
    else:
        display = np.repeat(np.repeat(mat, DISPLAY_UPSCALE, axis=0), DISPLAY_UPSCALE, axis=1)

    if gaussian_filter is not None and DISPLAY_SMOOTH_SIGMA > 0:
        display = gaussian_filter(display, sigma=DISPLAY_SMOOTH_SIGMA)

    display = np.clip(display, 0.0, 1.0)
    mask = cleanup_mask(display > MASK_CUTOFF)
    display[~mask] = 0.0
    display[display < DISPLAY_CUTOFF] = 0.0
    return np.clip(display, 0.0, 1.0)


def map_to_rgba(display, lut, background_cutoff=DISPLAY_CUTOFF):
    idx = (np.clip(display, 0.0, 1.0) * 255.0).astype(np.uint8)
    rgb = lut[idx]
    rgba = np.empty((display.shape[0], display.shape[1], 4), dtype=np.ubyte)
    rgba[..., 0:3] = rgb
    rgba[..., 3] = 255
    rgba[display <= background_cutoff] = np.array([255, 255, 255, 255], dtype=np.ubyte)
    return rgba


class AppHeatmap:
    def __init__(self, use_ble, port, baseline, threshold, mode, rot90_k, flip_lr, flip_ud):
        self.use_ble = use_ble
        self.port = port
        self.baseline = baseline
        self.threshold = threshold
        self.mode = mode
        self.rot90_k = rot90_k
        self.flip_lr = flip_lr
        self.flip_ud = flip_ud
        self.lut = make_pressure_lut()
        self.last_raw = None
        self.last_processed = None
        self.frames = 0
        self.reader = None
        self.grid_items = []

        self.app = QtWidgets.QApplication.instance() or QtWidgets.QApplication(sys.argv)
        self.win = QtWidgets.QMainWindow()
        self.win.setWindowTitle(f"PHBLE_APP Pressure Heatmap - 43x14 - {'BLE' if use_ble else 'Serial'} - {mode}")
        self.win.resize(620, 1200)

        self.plot = pg.PlotWidget()
        self.plot.setBackground("w")
        self.plot.hideAxis("left")
        self.plot.hideAxis("bottom")
        self.plot.setMenuEnabled(False)
        self.plot.setMouseEnabled(x=False, y=False)
        self.plot.setAspectLocked(True)
        self.plot.invertY(False)

        self.image_item = pg.ImageItem(axisOrder="row-major")
        self.plot.addItem(self.image_item)
        self.win.setCentralWidget(self.plot)

        self._setup_reader()
        self._setup_blank()

        self.timer = QtCore.QTimer()
        self.timer.timeout.connect(self.update_frame)
        self.timer.start(GUI_TIMER_MS)

        self.stats_timer = QtCore.QTimer()
        self.stats_timer.timeout.connect(self.print_stats)
        self.stats_timer.start(int(STATS_INTERVAL * 1000))

        self.win.show()

    def _setup_reader(self):
        if self.use_ble:
            self.reader = BLECompactFrameReader()
            print("Waiting for BLE connection...")
            while not self.reader.is_connected():
                QtWidgets.QApplication.processEvents()
                time.sleep(0.2)
            print("BLE connected.")
        else:
            if serial is None:
                print("pyserial is required. Install with: pip install pyserial")
                sys.exit(1)
            print(f"Opening serial port {self.port} @ {BAUD}")
            self.reader = serial.Serial(self.port, BAUD, timeout=0.01)
            time.sleep(2)
            self.reader.reset_input_buffer()

    def _clear_grid(self):
        for item in self.grid_items:
            try:
                self.plot.removeItem(item)
            except Exception:
                pass
        self.grid_items = []

    def _draw_pix_grid(self, h, w):
        self._clear_grid()
        pen = pg.mkPen(color=(185, 185, 185), width=1)

        for x in range(w + 1):
            line = pg.PlotDataItem([x, x], [0, h], pen=pen)
            self.plot.addItem(line)
            self.grid_items.append(line)

        for y in range(h + 1):
            line = pg.PlotDataItem([0, w], [y, y], pen=pen)
            self.plot.addItem(line)
            self.grid_items.append(line)

    def _setup_blank(self):
        blank_low_res = apply_orientation(
            np.zeros((ROWS, COLS), dtype=np.float32),
            self.rot90_k,
            self.flip_lr,
            self.flip_ud,
        )

        if self.mode == "pix":
            blank = blank_low_res
            background_cutoff = 0.0
        else:
            blank = np.zeros(
                (blank_low_res.shape[0] * DISPLAY_UPSCALE, blank_low_res.shape[1] * DISPLAY_UPSCALE),
                dtype=np.float32,
            )
            background_cutoff = DISPLAY_CUTOFF

        rgba = map_to_rgba(blank, self.lut, background_cutoff)
        self.image_item.setImage(rgba, autoLevels=False)
        self.image_item.setRect(QtCore.QRectF(0, 0, blank.shape[1], blank.shape[0]))
        self.plot.setXRange(0, blank.shape[1], padding=0)
        self.plot.setYRange(0, blank.shape[0], padding=0)

        if self.mode == "pix":
            self._draw_pix_grid(blank.shape[0], blank.shape[1])
        else:
            self._clear_grid()

    def read_frame(self):
        if self.use_ble:
            return self.reader.read_latest_frame()

        rawline = self.reader.readline()
        if not rawline:
            return None
        line = rawline.decode(errors="ignore") if isinstance(rawline, bytes) else rawline
        return parse_serial_frame(line)

    def update_frame(self):
        frame = self.read_frame()
        if frame is None:
            return

        self.last_raw = frame
        if self.mode == "pix":
            self.last_processed = preprocess_pix(frame, self.baseline, self.threshold)
        else:
            self.last_processed = preprocess(frame, self.baseline, self.threshold)

        oriented = apply_orientation(self.last_processed, self.rot90_k, self.flip_lr, self.flip_ud)
        if self.mode == "pix":
            display = oriented
            background_cutoff = 0.0
        else:
            display = upscale_display(oriented)
            background_cutoff = DISPLAY_CUTOFF

        rgba = map_to_rgba(display, self.lut, background_cutoff)

        self.image_item.setImage(rgba, autoLevels=False)
        self.image_item.setRect(QtCore.QRectF(0, 0, display.shape[1], display.shape[0]))
        self.plot.setXRange(0, display.shape[1], padding=0)
        self.plot.setYRange(0, display.shape[0], padding=0)
        self.frames += 1

    def print_stats(self):
        if self.use_ble and self.reader is not None:
            print("[BLE]", self.reader.stats())

        if self.last_raw is None or self.last_processed is None:
            print("[STATS] waiting for valid frames...")
            return

        active = int(np.count_nonzero(self.last_processed > 0))
        diff = self.last_raw - self.baseline
        print(
            "[STATS]",
            f"frames={self.frames}",
            f"raw min={self.last_raw.min():.0f}",
            f"raw max={self.last_raw.max():.0f}",
            f"diff max={diff.max():.0f}",
            f"active={active}/{NUM_VALUES}",
        )

    def run(self):
        try:
            return self.app.exec_()
        finally:
            if self.reader is not None:
                try:
                    self.reader.close()
                except Exception:
                    pass


def main():
    parser = argparse.ArgumentParser(description="Smooth PHBLE_APP compact 43x14 heatmap")
    parser.add_argument("--ble", action="store_true", help="Use BLE mode")
    parser.add_argument("--serial", action="store_true", help="Use serial CSV mode")
    parser.add_argument("--port", default=PORT, help="Serial port for serial mode")
    parser.add_argument("--baseline", default=BASELINE_FILE, help="43x14 baseline .npy file")
    parser.add_argument("--mode", choices=("smooth", "pix"), default=CONFIG_MODE, help="Display mode")
    parser.add_argument("--threshold", type=float, default=THRESHOLD_RAW, help="Raw delta threshold")
    parser.add_argument("--no-calibration", action="store_true", help="Use zero baseline")
    parser.add_argument("--rot90", type=int, default=ROT90_K, help="Rotate display by K*90 degrees")
    parser.add_argument("--flip-lr", action="store_true", default=FLIP_LR, help="Flip display left-right")
    parser.add_argument("--flip-ud", action="store_true", default=FLIP_UD, help="Flip display up-down")
    args = parser.parse_args()

    use_ble = CONFIG_USE_BLE
    if args.ble:
        use_ble = True
    elif args.serial:
        use_ble = False

    baseline = load_baseline(args.baseline, use_calibration=not args.no_calibration)
    rot90_k = args.rot90 % 4
    print(
        f"Running PHBLE_APP heatmap: {ROWS} x {COLS}, {'BLE' if use_ble else 'Serial'}, "
        f"mode={args.mode}, threshold={args.threshold}, "
        f"rot90={rot90_k}, flip_lr={args.flip_lr}, flip_ud={args.flip_ud}"
    )
    app = AppHeatmap(use_ble, args.port, baseline, args.threshold, args.mode, rot90_k, args.flip_lr, args.flip_ud)
    sys.exit(app.run())


if __name__ == "__main__":
    main()
