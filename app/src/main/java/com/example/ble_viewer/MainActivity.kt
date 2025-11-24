package com.example.ble_viewer

import android.annotation.SuppressLint
import android.bluetooth.*
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private lateinit var statusText: TextView
    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView
    private lateinit var heartRateText: TextView
    private lateinit var spo2Text: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val imuServiceUuid = UUID.fromString("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00")
    private val accelCharUuid = UUID.fromString("9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00")
    private val gyroCharUuid = UUID.fromString("9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00")
    private val heartRateCharUuid = UUID.fromString("9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00")
    private val spo2CharUuid = UUID.fromString("9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00")

    private val notificationQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var isProcessingQueue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)

        navigationView.setNavigationItemSelectedListener(this)

        // Initialize UI elements
        statusText = findViewById(R.id.statusText)
        accelText = findViewById(R.id.accelText)
        gyroText = findViewById(R.id.gyroText)
        heartRateText = findViewById(R.id.heartRateText)
        spo2Text = findViewById(R.id.spo2Text)
        
        // Set default values
        accelText.text = "Waiting for data..."
        gyroText.text = "Waiting for data..."
        heartRateText.text = "Waiting for sensor data..."
        spo2Text.text = "Waiting for sensor data..."

        // Initialize Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Get device address from intent and connect
        val deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        if (deviceAddress != null && bluetoothAdapter != null) {
            statusText.text = getString(R.string.connecting, deviceAddress)
            val device = bluetoothAdapter!!.getRemoteDevice(deviceAddress)
            connectToDevice(device)
        } else {
            statusText.text = "Error: No device address provided"
            Toast.makeText(this, "No device address provided", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_menu) {
            drawerLayout.openDrawer(GravityCompat.END)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_settings -> { 
                // Handle Settings click
            }
            // Add other cases for your menu items
        }
        drawerLayout.closeDrawers()
        return true
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    /**
     * Format accelerometer/gyroscope data for display
     * Input: "1.23,4.56,7.89" -> Output: "X: 1.23, Y: 4.56, Z: 7.89"
     */
    private fun formatAccelGyroData(data: String): String {
        return try {
            val parts = data.split(",")
            if (parts.size == 3) {
                "X: ${parts[0].trim()}, Y: ${parts[1].trim()}, Z: ${parts[2].trim()}"
            } else {
                data
            }
        } catch (e: Exception) {
            data
        }
    }

    /**
     * Format heart rate data for display
     * Input: "72.0,70,69,71" -> Output: "BPM: 72.0 | Avg: 70 | Min Avg: 69 | HR Avg: 71"
     */
    private fun formatHeartRateData(data: String): String {
        return try {
            val parts = data.split(",")
            if (parts.size >= 4) {
                "BPM: ${parts[0].trim()} | Avg: ${parts[1].trim()} | Min Avg: ${parts[2].trim()} | HR Avg: ${parts[3].trim()}"
            } else if (parts.size == 2) {
                "BPM: ${parts[0].trim()} | Avg: ${parts[1].trim()}"
            } else {
                data
            }
        } catch (e: Exception) {
            data
        }
    }

    /**
     * Format SpO2 data for display
     * Input: "98.5,98.2" -> Output: "SpO2: 98.5% | Estimated: 98.2%"
     */
    private fun formatSpO2Data(data: String): String {
        return try {
            val parts = data.split(",")
            if (parts.size == 2) {
                "Estimated SpO2: ${parts[0].trim()}% | SpO2: ${parts[1].trim()}%"
            } else {
                data
            }
        } catch (e: Exception) {
            data
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { statusText.text = getString(R.string.connected_discovering) }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread { statusText.text = getString(R.string.disconnected) }
                    bluetoothGatt?.close()
                    isProcessingQueue = false
                    notificationQueue.clear()
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusText.text = "Connection error: $status"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val imuService = gatt.getService(imuServiceUuid)
            val accelChar = imuService?.getCharacteristic(accelCharUuid)
            val gyroChar = imuService?.getCharacteristic(gyroCharUuid)
            val heartRateChar = imuService?.getCharacteristic(heartRateCharUuid)
            val spo2Char = imuService?.getCharacteristic(spo2CharUuid)

            // Add all available characteristics to notification queue
            if (accelChar != null) {
                notificationQueue.add(accelChar)
                Log.d("BLE_SETUP", "Added Accel characteristic to queue")
            }
            if (gyroChar != null) {
                notificationQueue.add(gyroChar)
                Log.d("BLE_SETUP", "Added Gyro characteristic to queue")
            }
            if (heartRateChar != null) {
                notificationQueue.add(heartRateChar)
                Log.d("BLE_SETUP", "Added Heart Rate characteristic to queue")
            }
            if (spo2Char != null) {
                notificationQueue.add(spo2Char)
                Log.d("BLE_SETUP", "Added SpO2 characteristic to queue")
            }

            processNotificationQueue(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isProcessingQueue = false
            processNotificationQueue(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Get raw bytes from BLE characteristic
            val bytes = characteristic.value ?: return
            
            // Convert to plain text string
            val plainData = String(bytes, Charsets.UTF_8).trim()
            
            // Skip empty data
            if (plainData.isEmpty()) {
                return
            }
            
            Log.d("BLE_RAW", "Received: $plainData")
            
            // Update UI with plain text data
            runOnUiThread {
                when (characteristic.uuid) {
                    accelCharUuid -> {
                        val formatted = formatAccelGyroData(plainData)
                        accelText.text = formatted
                        Log.d("BLE_DATA", "Accel: $formatted")
                    }
                    gyroCharUuid -> {
                        val formatted = formatAccelGyroData(plainData)
                        gyroText.text = formatted
                        Log.d("BLE_DATA", "Gyro: $formatted")
                    }
                    heartRateCharUuid -> {
                        // Only display if we have actual data (not "--")
                        if (plainData != "--" && plainData.contains(",")) {
                            val formatted = formatHeartRateData(plainData)
                            heartRateText.text = formatted
                            Log.d("BLE_DATA", "Heart Rate: $formatted")
                        } else {
                            heartRateText.text = "No heart rate detected"
                        }
                    }
                    spo2CharUuid -> {
                        // Only display if we have actual data (not "--")
                        if (plainData != "--" && plainData.contains(",")) {
                            val formatted = formatSpO2Data(plainData)
                            spo2Text.text = formatted
                            Log.d("BLE_DATA", "SpO2: $formatted")
                        } else {
                            spo2Text.text = "No SpO2 detected"
                        }
                    }
                }
            }
        }

        private fun processNotificationQueue(gatt: BluetoothGatt) {
            if (isProcessingQueue || notificationQueue.isEmpty()) {
                if (!isProcessingQueue) {
                    runOnUiThread { statusText.text = getString(R.string.receiving_data) }
                }
                return
            }

            isProcessingQueue = true
            val characteristic = notificationQueue.poll() ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
