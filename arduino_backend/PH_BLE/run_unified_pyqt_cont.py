import time
import os
import argparse
import sys

import numpy as np

try:
    from scipy.ndimage import gaussian_filter, zoom
except Exception:
    gaussian_filter = None
    zoom = None

# Continuous BLE reader
try:
    from ble_transport_cont import BLEFrameReaderCont
except Exception:
    BLEFrameReaderCont = None

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets
except Exception:
    print("pyqtgraph and PyQt are required. Install with:")
    print("pip install pyqtgraph pyqt5")
    sys.exit(1)


ROWS = 16
COLS = 48
NUM_VALUES = ROWS * COLS

ADC_MAX = 4095.0
BASELINE_FILE = "baseline_48x16.npy"

# Default parameters
THRESHOLD_RAW = 1000
SMOOTH_SIGMA = 0.2
DISPLAY_SMOOTH = 0.0
UPSCALE_DYNAMIC = 40
UPSCALE_FIXED = 40
GAMMA = 2.0

# Dynamic scaling for pix mode
PIX_DYNAMIC_SCALING = True

ROT90_K = 1
FLIP_LR = True
FLIP_UD = True

VMIN_FIXED = 0.0
VMAX_FIXED = 1.0

STATS_INTERVAL = 2.0

# === File-level configuration (edit here) ===
# Choose mode: 'pix', 'dynamic', or 'fixed'
CONFIG_MODE = "dynamic"

# Choose transport: True = BLE continuous mode, False = serial CSV mode
CONFIG_USE_BLE = False

# Serial port used when CONFIG_USE_BLE == False
CONFIG_PORT = "COM10"

# Update interval for GUI refresh (ms)
GUI_TIMER_MS = 5


def make_pressure_lut():
    colors = np.array([
        [255, 255, 255],  # white
        [0,   51, 255],   # blue
        [0,  230, 255],   # cyan
        [0,  242,  51],   # green
        [255, 255,   0],  # yellow
        [255, 153,   0],  # orange
        [255,   0,   0],  # red
    ], dtype=np.ubyte)

    pos = np.linspace(0.0, 1.0, len(colors))
    cmap = pg.ColorMap(pos, colors)
    lut = cmap.getLookupTable(0.0, 1.0, 256).astype(np.ubyte)
    return lut


def apply_orientation(mat):
    out = mat.copy()
    if ROT90_K != 0:
        out = np.rot90(out, ROT90_K)
    if FLIP_LR:
        out = np.fliplr(out)
    if FLIP_UD:
        out = np.flipud(out)
    return out


def parse_csv_frame(line: str):
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


def load_baseline(filepath):
    if not os.path.exists(filepath):
        raise FileNotFoundError(f"Baseline file '{filepath}' not found. Run calibrate_baseline.py first.")
    baseline = np.load(filepath)
    if baseline.shape != (ROWS, COLS):
        raise ValueError(f"Baseline shape mismatch. Expected {(ROWS, COLS)}, got {baseline.shape}")
    return baseline.astype(np.float32)


def preprocess_pix(raw, baseline):
    mat = raw - baseline
    mat[mat < 0] = 0
    mat[mat < THRESHOLD_RAW] = 0
    mat = mat / ADC_MAX
    mat = np.clip(mat, 0, 1)
    return mat


def preprocess_dynamic(raw, baseline):
    if gaussian_filter is None:
        raise RuntimeError("scipy required for dynamic mode (gaussian_filter)")
    mat = raw - baseline
    mat[mat < 0] = 0
    mat[mat < THRESHOLD_RAW] = 0
    mat = mat / ADC_MAX
    mat = np.clip(mat, 0, 1)
    mat = gaussian_filter(mat, sigma=SMOOTH_SIGMA)
    if mat.max() > 0:
        mat[mat < 0.2 * mat.max()] = 0
    thr_norm = THRESHOLD_RAW / ADC_MAX
    mat[mat < thr_norm * 0.5] = 0
    mask = mat > 0
    mat[mask] = np.power(mat[mask], GAMMA)
    return mat


def preprocess_fixed(raw, baseline):
    if gaussian_filter is None:
        raise RuntimeError("scipy required for fixed mode (gaussian_filter)")
    mat = raw - baseline
    mat[mat < 0] = 0
    mat[mat < THRESHOLD_RAW] = 0
    mat = mat / ADC_MAX
    mat = np.clip(mat, 0, 1)
    mat = gaussian_filter(mat, sigma=SMOOTH_SIGMA)
    thr_norm = THRESHOLD_RAW / ADC_MAX
    mat[mat < thr_norm * 0.5] = 0
    mask = mat > 0
    mat[mask] = np.power(mat[mask], GAMMA)
    return mat


def upscale(mat, factor, order=1):
    if zoom is None:
        raise RuntimeError("scipy required for upscaling (zoom)")
    up = zoom(mat, (factor, factor), order=order)
    if gaussian_filter is not None and DISPLAY_SMOOTH > 0:
        up = gaussian_filter(up, sigma=DISPLAY_SMOOTH)
    return up


def map_to_rgba(display, lut, vmin, vmax):
    h, w = display.shape
    rgba = np.empty((h, w, 4), dtype=np.ubyte)

    if vmax <= vmin:
        vmax = vmin + 1e-6

    norm = (display - vmin) / (vmax - vmin)
    norm = np.clip(norm, 0.0, 1.0)

    idx = (norm * 255.0).astype(np.uint8)
    rgb = lut[idx]

    rgba[..., 0:3] = rgb
    rgba[..., 3] = 255

    zero_mask = display <= 0
    rgba[zero_mask, 0] = 255
    rgba[zero_mask, 1] = 255
    rgba[zero_mask, 2] = 255
    rgba[zero_mask, 3] = 255

    return rgba


class HeatmapAppCont:
    def __init__(self, mode, use_ble, port, baseline):
        self.mode = mode
        self.use_ble = use_ble
        self.port = port
        self.baseline = baseline
        self.lut = make_pressure_lut()

        self.ser = None
        self.ble = None
        self.grid_items = []

        self.app = QtWidgets.QApplication.instance()
        if self.app is None:
            self.app = QtWidgets.QApplication(sys.argv)

        self.win = QtWidgets.QMainWindow()
        self.win.setWindowTitle(f"Velostat Continuous Heatmap - mode={mode} - {'BLE' if use_ble else 'Serial'}")
        self.win.resize(500, 1200)

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
        self._setup_initial_display()

        self.timer = QtCore.QTimer()
        self.timer.timeout.connect(self.update_frame)
        self.timer.start(GUI_TIMER_MS)

        self.stats_timer = QtCore.QTimer()
        self.stats_timer.timeout.connect(self.print_stats)
        self.stats_timer.start(int(STATS_INTERVAL * 1000))

        self.win.show()

    def _setup_reader(self):
        if self.use_ble:
            if BLEFrameReaderCont is None:
                print("BLEFrameReaderCont not available (bleak or ble_transport_cont missing).")
                sys.exit(1)

            self.ble = BLEFrameReaderCont()
            print("Waiting for BLE connection...")
            while not self.ble.is_connected():
                QtWidgets.QApplication.processEvents()
                time.sleep(0.2)
            print("BLE connected.")
        else:
            try:
                import serial
            except Exception:
                print("pyserial is required for serial mode. Install with: pip install pyserial")
                sys.exit(1)

            print(f"Opening serial port {self.port} @115200")
            self.ser = serial.Serial(self.port, 115200, timeout=0.01)
            time.sleep(2)

    def _clear_grid(self):
        for item in self.grid_items:
            try:
                self.plot.removeItem(item)
            except Exception:
                pass
        self.grid_items = []

    def _draw_pix_grid(self, h, w):
        self._clear_grid()
        pen = pg.mkPen(color=(200, 200, 200), width=1)

        for x in range(w + 1):
            line = pg.PlotDataItem([x, x], [0, h], pen=pen)
            self.plot.addItem(line)
            self.grid_items.append(line)

        for y in range(h + 1):
            line = pg.PlotDataItem([0, w], [y, y], pen=pen)
            self.plot.addItem(line)
            self.grid_items.append(line)

    def _setup_initial_display(self):
        dummy = np.zeros((ROWS, COLS), dtype=np.float32)
        dummy = apply_orientation(dummy)

        if self.mode == "pix":
            display = dummy
            self.display_shape = display.shape
            rgba = map_to_rgba(display, self.lut, 0.0, 1.0)
            self.image_item.setImage(rgba, autoLevels=False)
            self.image_item.setRect(QtCore.QRectF(0, 0, display.shape[1], display.shape[0]))
            self.plot.setXRange(0, display.shape[1], padding=0)
            self.plot.setYRange(0, display.shape[0], padding=0)
            self._draw_pix_grid(display.shape[0], display.shape[1])

        elif self.mode == "dynamic":
            display = upscale(dummy, UPSCALE_DYNAMIC)
            self.display_shape = display.shape
            rgba = map_to_rgba(display, self.lut, 0.0, 1.0)
            self.image_item.setImage(rgba, autoLevels=False)
            self.image_item.setRect(QtCore.QRectF(0, 0, display.shape[1], display.shape[0]))
            self.plot.setXRange(0, display.shape[1], padding=0)
            self.plot.setYRange(0, display.shape[0], padding=0)
            self._clear_grid()

        else:  # fixed
            display = upscale(dummy, UPSCALE_FIXED)
            self.display_shape = display.shape
            rgba = map_to_rgba(display, self.lut, VMIN_FIXED, VMAX_FIXED)
            self.image_item.setImage(rgba, autoLevels=False)
            self.image_item.setRect(QtCore.QRectF(0, 0, display.shape[1], display.shape[0]))
            self.plot.setXRange(0, display.shape[1], padding=0)
            self.plot.setYRange(0, display.shape[0], padding=0)
            self._clear_grid()

    def read_one_frame(self):
        frame = None

        if self.use_ble:
            frame = self.ble.read_latest_frame()
        else:
            rawline = self.ser.readline()
            if isinstance(rawline, bytes):
                rawline = rawline.decode(errors="ignore")
            if rawline:
                frame = parse_csv_frame(rawline)

        return frame

    def update_frame(self):
        frame = self.read_one_frame()
        if frame is None:
            return

        if self.mode == "pix":
            mat = preprocess_pix(frame, self.baseline)
            mat = apply_orientation(mat)
            display = mat

            if PIX_DYNAMIC_SCALING:
                nonzero = display[display > 0]
                if nonzero.size > 0:
                    vmax_frame = np.percentile(nonzero, 95)
                    if vmax_frame <= 1e-6:
                        vmax_frame = 1.0
                else:
                    vmax_frame = 1.0
            else:
                vmax_frame = 1.0

            rgba = map_to_rgba(display, self.lut, 0.0, vmax_frame)

        elif self.mode == "dynamic":
            mat = preprocess_dynamic(frame, self.baseline)
            mat = apply_orientation(mat)
            display = upscale(mat, UPSCALE_DYNAMIC)

            nonzero = display[display > 0]
            if nonzero.size > 0:
                vmax_frame = np.percentile(nonzero, 95)
                if vmax_frame <= 1e-6:
                    vmax_frame = 1.0
            else:
                vmax_frame = 1.0

            rgba = map_to_rgba(display, self.lut, 0.0, vmax_frame)

        else:  # fixed
            mat = preprocess_fixed(frame, self.baseline)
            mat = apply_orientation(mat)
            display = upscale(mat, UPSCALE_FIXED)

            rgba = map_to_rgba(display, self.lut, VMIN_FIXED, VMAX_FIXED)

        self.image_item.setImage(rgba, autoLevels=False)
        self.image_item.setRect(QtCore.QRectF(0, 0, display.shape[1], display.shape[0]))
        self.plot.setXRange(0, display.shape[1], padding=0)
        self.plot.setYRange(0, display.shape[0], padding=0)

    def print_stats(self):
        if self.use_ble and self.ble is not None:
            print("[STATS]", self.ble.stats())

    def run(self):
        exit_code = 0
        try:
            exit_code = self.app.exec_()
        finally:
            self.close()
        return exit_code

    def close(self):
        if self.ser is not None:
            try:
                self.ser.close()
            except Exception:
                pass

        if self.ble is not None:
            try:
                self.ble.close()
            except Exception:
                pass


def main():
    parser = argparse.ArgumentParser(description="Continuous PyQtGraph runner for Velostat heatmaps")
    parser.add_argument("--port", default=CONFIG_PORT, help="Serial port for serial mode")
    parser.add_argument("--baseline", default=BASELINE_FILE)
    args = parser.parse_args()

    mode = CONFIG_MODE
    use_ble = CONFIG_USE_BLE

    if mode not in ("pix", "dynamic", "fixed"):
        raise ValueError("CONFIG_MODE must be 'pix', 'dynamic', or 'fixed'")

    baseline = load_baseline(args.baseline)
    print(f"Baseline loaded: {args.baseline}")
    print(f"Running continuous PyQtGraph mode={mode} (BLE={use_ble}). Close window or Ctrl+C to stop.")

    app = HeatmapAppCont(
        mode=mode,
        use_ble=use_ble,
        port=args.port,
        baseline=baseline,
    )

    sys.exit(app.run())


if __name__ == "__main__":
    main()