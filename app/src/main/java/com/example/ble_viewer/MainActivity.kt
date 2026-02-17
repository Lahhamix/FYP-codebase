package com.example.ble_viewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

// Data class to hold timestamped sensor data
data class TimestampedSensorData(val timestamp: String, val heartRate: Int?, val spo2: Double?)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var statusText: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    companion object {
        const val ACTION_SENSOR_DATA = "com.example.ble_viewer.ACTION_SENSOR_DATA"
        const val EXTRA_UUID_STRING = "com.example.ble_viewer.EXTRA_UUID_STRING"
        const val EXTRA_DECRYPTED_DATA = "com.example.ble_viewer.EXTRA_DECRYPTED_DATA"
        private const val WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 101
    }

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val imuServiceUuid = UUID.fromString("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00")
    private val accelCharUuid = UUID.fromString("9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00")
    private val gyroCharUuid = UUID.fromString("9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00")
    private val heartRateCharUuid = UUID.fromString("9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00")
    private val spo2CharUuid = UUID.fromString("9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00")

    private val notificationQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var isProcessingQueue = false
    private val characteristicBuffers = mutableMapOf<UUID, ByteArrayOutputStream>()
    private val maxPayloadLength = 80

    private val timestampedSensorData = mutableListOf<TimestampedSensorData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener(this)

        statusText = findViewById(R.id.statusText)

        val vitalSignsCard: MaterialCardView = findViewById(R.id.vitalSignsCard)
        vitalSignsCard.setOnClickListener {
            val intent = Intent(this, ReadingsActivity::class.java)
            startActivity(intent)
        }

        val gaitCard: MaterialCardView = findViewById(R.id.gaitAnalysisCard)
        gaitCard.setOnClickListener {
            val intent = Intent(this, GaitAnalysisActivity::class.java)
            startActivity(intent)
        }

        val downloadButton: Button = findViewById(R.id.download_data_button)
        downloadButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                exportDataToCsv()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_EXTERNAL_STORAGE_REQUEST_CODE)
            }
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        if (deviceAddress != null) {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device != null) {
                connectToDevice(device)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportDataToCsv()
            } else {
                Toast.makeText(this, "Permission denied. Cannot download data.", Toast.LENGTH_SHORT).show()
            }
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
            R.id.nav_vital_signs -> {
                val intent = Intent(this, ReadingsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_gait_analysis -> {
                val intent = Intent(this, GaitAnalysisActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_about_us -> {
                val intent = Intent(this, AboutUsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_settings -> {
                val intent = Intent(this, ReadingsActivity::class.java)
                startActivity(intent)
            }
        }
        drawerLayout.closeDrawers()
        return true
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
                characteristicBuffers.values.forEach { it.reset() }
                characteristicBuffers.clear()
                bluetoothGatt?.close()
                isProcessingQueue = false
                notificationQueue.clear()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val imuService = gatt.getService(imuServiceUuid)
                val characteristics = listOf(
                    imuService?.getCharacteristic(accelCharUuid),
                    imuService?.getCharacteristic(gyroCharUuid),
                    imuService?.getCharacteristic(heartRateCharUuid),
                    imuService?.getCharacteristic(spo2CharUuid)
                )
                notificationQueue.addAll(characteristics.filterNotNull())
                processNotificationQueue(gatt)
            } 
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isProcessingQueue = false
            processNotificationQueue(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            val buffer = characteristicBuffers.getOrPut(characteristic.uuid) { ByteArrayOutputStream() }
            buffer.write(bytes)
            drainBuffer(characteristic, buffer)
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
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                isProcessingQueue = false
                processNotificationQueue(gatt)
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun drainBuffer(characteristic: BluetoothGattCharacteristic, buffer: ByteArrayOutputStream) {
        while (buffer.size() > 0) {
            val packet = buffer.toByteArray()
            if (packet.isEmpty()) return

            val payloadLength = packet[0].toInt() and 0xFF
            if (payloadLength == 0 || payloadLength > maxPayloadLength) {
                Log.e("BLE_BUFFER", "Invalid payload length $payloadLength for ${characteristic.uuid}, clearing buffer")
                buffer.reset()
                return
            }

            if (packet.size - 1 < payloadLength) {
                // wait for more bytes
                return
            }

            val messageBytes = packet.copyOfRange(1, payloadLength + 1)
            val remainingBytes = packet.copyOfRange(payloadLength + 1, packet.size)
            buffer.reset()
            buffer.write(remainingBytes)

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

            when (characteristic.uuid) {
                heartRateCharUuid -> {
                    val bpm = AESCrypto.decryptHeartRate(messageBytes).trim().toIntOrNull()
                    if (bpm != null) {
                        timestampedSensorData.add(TimestampedSensorData(timestamp, bpm, null))
                    }
                }
                spo2CharUuid -> {
                    val spo2 = AESCrypto.decryptSpO2(messageBytes).trim().toDoubleOrNull()
                    if (spo2 != null) {
                        timestampedSensorData.add(TimestampedSensorData(timestamp, null, spo2))
                    }
                }
            }

            val intent = Intent(ACTION_SENSOR_DATA).apply {
                putExtra(EXTRA_UUID_STRING, characteristic.uuid.toString())
                putExtra(EXTRA_DECRYPTED_DATA, messageBytes)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun exportDataToCsv() {
        val username = getSharedPreferences("SolematePrefs", MODE_PRIVATE)
            .getString("username", "user")

        val fileName = "${username}_sensor_data.csv"

        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        try {
            if (uri == null) {
                Toast.makeText(this, "Download failed: cannot create file.", Toast.LENGTH_LONG).show()
                return
            }

            resolver.openOutputStream(uri)?.use { os ->
                os.write("Timestamp,Heart Rate,SpO2\n".toByteArray())
                timestampedSensorData.forEach { data ->
                    val heartRate = data.heartRate?.toString() ?: ""
                    val spo2 = data.spo2?.toString() ?: ""
                    os.write("${data.timestamp},$heartRate,$spo2\n".toByteArray())
                }
            }

            Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
