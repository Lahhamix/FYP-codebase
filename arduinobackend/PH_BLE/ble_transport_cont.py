import asyncio
import threading
import numpy as np
from bleak import BleakScanner, BleakClient

DEVICE_NAME = "Velo2"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"

ROWS = 16
COLS = 48
NUM_VALUES = ROWS * COLS

MAGIC0 = 0xA5
MAGIC1 = 0x5A


class BLEFrameReaderCont:

    def __init__(self):

        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

        self.client = None
        self.connected = False

        self.buffer = np.zeros(NUM_VALUES, dtype=np.float32)

        self.packets = 0
        self.bad_magic = 0

        asyncio.run_coroutine_threadsafe(self._connect(), self.loop)

    async def _connect(self):

        print("[BLE] Scanning for device...")

        device = None
        devices = await BleakScanner.discover()

        for d in devices:
            if d.name == DEVICE_NAME:
                device = d
                break

        if device is None:
            print("[BLE] Device not found.")
            return

        print(f"[BLE] Found device by name: {device.name}")

        self.client = BleakClient(device)

        await self.client.connect()

        print("[BLE] Connected.")

        await self.client.start_notify(CHAR_UUID, self._notification_handler)

        self.connected = True

        print("[BLE] Notifications started.")

    def is_connected(self):
        return self.connected

    def _notification_handler(self, sender, data):

        self.packets += 1

        if len(data) != 20:
            return

        if data[0] != MAGIC0 or data[1] != MAGIC1:
            self.bad_magic += 1
            return

        start_index = data[4] | (data[5] << 8)
        sample_count = data[6]

        payload = data[8:]

        values = self._unpack12(payload, sample_count)

        end = min(start_index + sample_count, NUM_VALUES)

        self.buffer[start_index:end] = values[: end - start_index]

    def _unpack12(self, payload, count):

        values = []

        i = 0
        p = 0

        while i + 1 < count:

            b0 = payload[p]
            b1 = payload[p + 1]
            b2 = payload[p + 2]

            a = b0 | ((b1 & 0x0F) << 8)
            b = ((b1 >> 4) & 0x0F) | (b2 << 4)

            values.append(a)
            values.append(b)

            p += 3
            i += 2

        if i < count:

            b0 = payload[p]
            b1 = payload[p + 1]

            a = b0 | ((b1 & 0x0F) << 8)

            values.append(a)

        return np.array(values, dtype=np.float32)

    def read_latest_frame(self):

        return self.buffer.reshape(ROWS, COLS)

    def stats(self):

        return {
            "packets": self.packets,
            "bad_magic": self.bad_magic
        }

    def close(self):

        if self.client:
            asyncio.run_coroutine_threadsafe(self.client.disconnect(), self.loop)