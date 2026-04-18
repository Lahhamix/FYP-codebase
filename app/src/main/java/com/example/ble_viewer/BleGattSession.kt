package com.example.ble_viewer

import android.bluetooth.BluetoothGatt

/**
 * Holds the active GATT connection so it survives [MainActivity] recreation
 * (e.g. configuration change) and is not tied to Activity-only [android.content.Context].
 */
object BleGattSession {
    @Volatile
    var gatt: BluetoothGatt? = null
}
