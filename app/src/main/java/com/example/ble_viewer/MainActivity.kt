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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var lastDeviceAddress: String? = null
    private var disconnectDialog: AlertDialog? = null

    // --- Data Logging Members ---
    private val uiHandler = Handler(Looper.getMainLooper())
    private var latestBpm: Int? = null
    private var latestSpo2: Double? = null
    private var isLoggingStarted = false
    
    // --- Key Exchange Members ---
    private var keyExchangeManager: KeyExchangeManager? = null
    private var isKeyExchangeInProgress = false

    companion object {
        const val ACTION_SENSOR_DATA = "com.example.ble_viewer.ACTION_SENSOR_DATA"
        const val ACTION_DEVICE_DISCONNECTED = "com.example.ble_viewer.ACTION_DEVICE_DISCONNECTED"
        const val EXTRA_UUID_STRING = "com.example.ble_viewer.EXTRA_UUID_STRING"
        const val EXTRA_DECRYPTED_DATA = "com.example.ble_viewer.EXTRA_DECRYPTED_DATA"
        private const val WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 101
        private const val PERIPHERAL_PUBLIC_KEY_LENGTH = 65 
        private const val TAG = "BLE_VIEWER_MAIN"
    }

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val imuServiceUuid = UUID.fromString("9a8b0001-6d5e-4c10-b6d9-1f25c09d9e00")
    
    private val keyExchangeServiceUuid = UUID.fromString("9a8b1001-6d5e-4c10-b6d9-1f25c09d9e00")
    private val phonePublicKeyCharUuid = UUID.fromString("9a8b1002-6d5e-4c10-b6d9-1f25c09d9e00")
    private val peripheralPublicKeyCharUuid = UUID.fromString("9a8b1003-6d5e-4c10-b6d9-1f25c09d9e00")

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

        findViewById<MaterialCardView>(R.id.vitalSignsCard).setOnClickListener {
            startActivity(Intent(this, ReadingsActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.gaitAnalysisCard).setOnClickListener {
            startActivity(Intent(this, GaitAnalysisActivity::class.java))
        }

        findViewById<Button>(R.id.download_data_button).setOnClickListener {
            checkPermissionAndExport()
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        val reconnectLast = intent.getBooleanExtra("RECONNECT_LAST", false)

        if (deviceAddress != null) {
            lastDeviceAddress = deviceAddress
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            device?.let { connectToDevice(it) }
        } else if (reconnectLast) {
            lastDeviceAddress?.let { address ->
                val device = bluetoothAdapter?.getRemoteDevice(address)
                device?.let { connectToDevice(it) }
            } ?: run {
                showDisconnectDialog()
            }
        } else if (bluetoothGatt == null) {
            showDisconnectDialog()
        }
    }

    private fun checkPermissionAndExport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportDataToCsv()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                exportDataToCsv()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_EXTERNAL_STORAGE_REQUEST_CODE)
            }
        }
    }

    private val dataLoggingRunnable = object : Runnable {
        override fun run() {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            timestampedSensorData.add(TimestampedSensorData(timestamp, latestBpm, latestSpo2))
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun startDataLogging() {
        if (!isLoggingStarted) {
            isLoggingStarted = true
            uiHandler.post(dataLoggingRunnable)
        }
    }

    private fun stopDataLogging() {
        if (isLoggingStarted) {
            isLoggingStarted = false
            uiHandler.removeCallbacks(dataLoggingRunnable)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        runOnUiThread { 
            statusText.text = "Status: Connecting..." 
            statusText.setTextColor(ContextCompat.getColor(this, R.color.dark_blue))
            disconnectDialog?.dismiss()
        }
        
        try {
            cleanup() 
            keyExchangeManager = KeyExchangeManager()
            isKeyExchangeInProgress = true
            
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error: ${e.message}")
            runOnUiThread { 
                statusText.text = "Status: 🔴 Error (Setup)" 
                showDisconnectDialog()
            }
        }
    }

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle("Connection Lost")
                .setMessage(
                    "The wearable device has been disconnected.\n\n" +
                            "You can reconnect to the last device or scan for another available device."
                )
                .setCancelable(false)
                .setPositiveButton("Reconnect") { _, _ ->
                    lastDeviceAddress?.let { address ->
                        val device = bluetoothAdapter?.getRemoteDevice(address)
                        if (device != null) {
                            connectToDevice(device)
                        } else {
                            startActivity(Intent(this, ScanActivity::class.java))
                            finish()
                        }
                    } ?: run {
                        startActivity(Intent(this, ScanActivity::class.java))
                        finish()
                    }
                }
                .setNegativeButton("Scan Devices") { _, _ ->
                    startActivity(Intent(this, ScanActivity::class.java))
                    finish()
                }
                .create()

            disconnectDialog?.show()

            disconnectDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(getColor(R.color.dark_blue))
                textSize = 15f
                isAllCaps = false
            }

            disconnectDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(getColor(R.color.dark_blue))
                textSize = 15f
                isAllCaps = false
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT Error code: $status")
                gatt.close()
                runOnUiThread { 
                    statusText.text = "🔴 Disconnected"
                    statusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                    broadcastDisconnection()
                    showDisconnectDialog()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.")
                uiHandler.postDelayed({
                    runOnUiThread { statusText.text = " Configuring link..." }
                    if (!gatt.requestMtu(128)) {
                        Log.w(TAG, "MTU request failed. Starting discovery.")
                        gatt.discoverServices()
                    }
                }, 800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.")
                runOnUiThread { 
                    statusText.text = " 🔴 Disconnected"
                    statusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                    broadcastDisconnection()
                    showDisconnectDialog()
                }
                cleanup()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status=$status")
            runOnUiThread { statusText.text = "Discovering services..." }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val keyService = gatt.getService(keyExchangeServiceUuid)
                
                if (keyService == null) {
                    Log.w(TAG, "Key Exchange Service not found. Using Legacy Mode.")
                    runOnUiThread { 
                        statusText.text = " 🟢 Connected (Legacy)"
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ble_connected))
                    }
                    AESCrypto.initWithLegacyKeys()
                    isKeyExchangeInProgress = false
                    setupDataNotifications(gatt)
                    return
                }

                runOnUiThread { statusText.text = " Securing link..." }
                val phoneKeyChar = keyService.getCharacteristic(phonePublicKeyCharUuid)
                val manager = keyExchangeManager
                if (phoneKeyChar == null || manager == null) {
                    Log.e(TAG, "Handshake characteristic missing.")
                    gatt.disconnect()
                    return
                }

                phoneKeyChar.value = manager.publicKeyBytes
                if (!gatt.writeCharacteristic(phoneKeyChar)) {
                    Log.e(TAG, "Failed to initiate handshake write.")
                    gatt.disconnect()
                }
            } else {
                Log.e(TAG, "Service discovery failed with status $status")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == phonePublicKeyCharUuid) {
                Log.d(TAG, "Our key written. Reading device key...")
                val keyService = gatt.getService(keyExchangeServiceUuid)
                val peripheralKeyChar = keyService?.getCharacteristic(peripheralPublicKeyCharUuid)
                if (peripheralKeyChar == null) {
                    Log.e(TAG, "Device key char missing.")
                    gatt.disconnect()
                    return
                }
                gatt.readCharacteristic(peripheralKeyChar)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == peripheralPublicKeyCharUuid) {
                val deviceKey = characteristic.value
                val manager = keyExchangeManager

                if (deviceKey == null || deviceKey.size != PERIPHERAL_PUBLIC_KEY_LENGTH || manager == null) {
                    Log.e(TAG, "Invalid device key received.")
                    gatt.disconnect()
                    return
                }

                try {
                    val sharedSecret = manager.generateSharedSecret(deviceKey)
                    AESCrypto.init(sharedSecret)
                    sharedSecret.fill(0) // Secure wipe
                    
                    isKeyExchangeInProgress = false
                    keyExchangeManager = null

                    runOnUiThread { 
                        statusText.text = " 🟢 Connected"
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ble_connected))
                    }
                    setupDataNotifications(gatt)
                } catch (e: Exception) {
                    Log.e(TAG, "Handshake error: ${e.message}")
                    gatt.disconnect()
                }
            }
        }

        private fun setupDataNotifications(gatt: BluetoothGatt) {
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

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isProcessingQueue = false
            processNotificationQueue(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if(isKeyExchangeInProgress) return
            val bytes = characteristic.value ?: return
            val buffer = characteristicBuffers.getOrPut(characteristic.uuid) { ByteArrayOutputStream() }
            buffer.write(bytes)
            drainBuffer(characteristic, buffer)
        }

        private fun processNotificationQueue(gatt: BluetoothGatt) {
            if (isProcessingQueue || notificationQueue.isEmpty()) {
                if (!isProcessingQueue) startDataLogging()
                return
            }

            isProcessingQueue = true
            val characteristic = notificationQueue.poll() ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
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

    private fun broadcastDisconnection() {
        val intent = Intent(ACTION_DEVICE_DISCONNECTED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun cleanup() {
        characteristicBuffers.values.forEach { it.reset() }
        characteristicBuffers.clear()
        bluetoothGatt?.close()
        bluetoothGatt = null
        stopDataLogging()
        keyExchangeManager?.close()
        keyExchangeManager = null
    }

    private fun drainBuffer(characteristic: BluetoothGattCharacteristic, buffer: ByteArrayOutputStream) {
        while (buffer.size() > 0) {
            val packet = buffer.toByteArray()
            if (packet.isEmpty()) return

            val payloadLength = packet[0].toInt() and 0xFF
            if (payloadLength == 0 || payloadLength > maxPayloadLength) {
                buffer.reset()
                return
            }

            if (packet.size - 1 < payloadLength) return

            val messageBytes = packet.copyOfRange(1, payloadLength + 1)
            val remainingBytes = packet.copyOfRange(payloadLength + 1, packet.size)
            buffer.reset()
            buffer.write(remainingBytes)

            when (characteristic.uuid) {
                heartRateCharUuid -> latestBpm = AESCrypto.decryptHeartRate(messageBytes).trim().toIntOrNull()
                spo2CharUuid -> latestSpo2 = AESCrypto.decryptSpO2(messageBytes).trim().toDoubleOrNull()
            }

            val intent = Intent(ACTION_SENSOR_DATA).apply {
                putExtra(EXTRA_UUID_STRING, characteristic.uuid.toString())
                putExtra(EXTRA_DECRYPTED_DATA, messageBytes)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun exportDataToCsv() {
        val username = getSharedPreferences("SolematePrefs", MODE_PRIVATE).getString("username", "user")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val fileName = "${username}_sensor_data_${date}.csv"

        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        try {
            if (uri == null) {
                Toast.makeText(this, "File creation failed.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
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
            R.id.nav_vital_signs, R.id.nav_settings -> startActivity(Intent(this, ReadingsActivity::class.java))
            R.id.nav_gait_analysis -> startActivity(Intent(this, GaitAnalysisActivity::class.java))
            R.id.nav_about_us -> startActivity(Intent(this, AboutUsActivity::class.java))
        }
        drawerLayout.closeDrawers()
        return true
    }
}