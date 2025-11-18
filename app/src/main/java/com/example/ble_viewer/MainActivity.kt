
package com.example.ble_viewer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

import com.example.ble_viewer.AESCrypto
import android.util.Log


@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var statusText: TextView
    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // UUID for the CCCD (Client Characteristic Configuration Descriptor)
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val imuServiceUuid = UUID.fromString("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00")
    private val accelCharUuid = UUID.fromString("9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00")
    private val gyroCharUuid  = UUID.fromString("9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00")

    // Queue for ensuring BLE operations are sequential
    private val notificationQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var isProcessingQueue = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanButton = findViewById(R.id.scanButton)
        statusText = findViewById(R.id.statusText)
        accelText = findViewById(R.id.accelText)
        gyroText = findViewById(R.id.gyroText)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        scanButton.setOnClickListener {
            requestPermissionsAndStartScan()
        }
    }

    private fun requestPermissionsAndStartScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            statusText.text = getString(R.string.bluetooth_not_enabled)
            // You could add an intent here to ask the user to enable Bluetooth
            return
        }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                statusText.text = getString(R.string.permissions_not_granted)
            }
        }
    }

    private fun startScan() {
        statusText.text = getString(R.string.scanning)
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (device.name == "NanoBLE_IMU") {
                runOnUiThread { statusText.text = getString(R.string.connecting, device.name) }
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(device)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { statusText.text = getString(R.string.connected_discovering) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { statusText.text = getString(R.string.disconnected) }
                bluetoothGatt?.close()
                isProcessingQueue = false
                notificationQueue.clear()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val imuService = gatt.getService(imuServiceUuid)
            val accelChar = imuService?.getCharacteristic(accelCharUuid)
            val gyroChar = imuService?.getCharacteristic(gyroCharUuid)

            // Add characteristics to the queue to be processed sequentially
            if (accelChar != null) notificationQueue.add(accelChar)
            if (gyroChar != null) notificationQueue.add(gyroChar)

            // Start processing the queue
            processNotificationQueue(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // This is the success callback for a descriptor write.
            // We can now process the next item in the queue.
            isProcessingQueue = false
            processNotificationQueue(gatt)
        }

        // Use the modern, two-parameter version of onCharacteristicChanged
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            val encryptedData = String(bytes)

            // ✅ Try to decrypt using AES
            val decryptedData = AESCrypto.decryptBase64AES(encryptedData)

            // 🧠 Debug logs (optional but recommended)
            Log.d("BLE_ENCRYPTED", "Encrypted: $encryptedData")
            Log.d("BLE_DECRYPTED", "Decrypted: $decryptedData")

            // ✅ Update the UI with decrypted text
            runOnUiThread {
                when (characteristic.uuid) {
                    accelCharUuid -> accelText.text = getString(R.string.accel_data, decryptedData)
                    gyroCharUuid -> gyroText.text = getString(R.string.gyro_data, decryptedData)
                }
            }
        }


        private fun processNotificationQueue(gatt: BluetoothGatt) {
            if (isProcessingQueue || notificationQueue.isEmpty()) {
                if (!isProcessingQueue) {
                    // Queue is empty and we are done, update UI
                    runOnUiThread { statusText.text = getString(R.string.receiving_data) }
                }
                return
            }

            isProcessingQueue = true
            val characteristic = notificationQueue.poll() ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return

            // Set the correct value on the descriptor
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            // Write the descriptor to the device
            // Use the correct writeDescriptor method based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                gatt.writeDescriptor(descriptor)
            }
        }
    }
}
