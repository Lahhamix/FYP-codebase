package com.example.ble_viewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.bluetooth.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

// Data class to hold timestamped sensor data
data class TimestampedSensorData(val timestamp: String, val heartRate: Int?, val spo2: Double?, val edema: String?)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var statusLoadingDots: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusPrimaryRow: LinearLayout
    private lateinit var offlineCenterHint: TextView
    private lateinit var deviceContent: LinearLayout
    private lateinit var wearableImage: ImageView
    private lateinit var disconnectedPanelUnderlay: View
    private lateinit var disconnectedPanel: LinearLayout
    private lateinit var reconnectInlineButton: Button
    private lateinit var scanDevicesInlineButton: Button
    private lateinit var toolbarUsername: TextView
    private lateinit var wearableDashboardCard: View
    private lateinit var currentVitalsCard: View
    private lateinit var heartRateText: TextView
    private lateinit var heartStatus: TextView
    private lateinit var spo2Text: TextView
    private lateinit var spo2Status: TextView
    private lateinit var spo2Card: View
    private lateinit var bpCard: View
    private lateinit var swellingText: TextView
    private lateinit var swellingStatus: TextView
    private lateinit var swellingCard: View
    private lateinit var ataxiaCard: View
    private lateinit var sharingExportCard: View
    private lateinit var pressureMatrixCard: View
    private lateinit var vitalSignsCard: View
    private lateinit var gaitAnalysisCard: View
    private lateinit var fadeTargets: List<View>

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var lastDeviceAddress: String? = null
    private var isDeviceConnected = false
    private var hasAttemptedConnection = false

    // --- Data Logging Members ---
    private val uiHandler = Handler(Looper.getMainLooper())
    private var latestBpm: Int? = null
    private var latestSpo2: Double? = null
    private var latestEdema: String? = null
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
        private const val BLUETOOTH_CONNECT_REQUEST_CODE = 102
        private const val PERIPHERAL_PUBLIC_KEY_LENGTH = 65
        private const val TAG = "BLE_VIEWER_MAIN"
        val pressureCharUuid = UUID.fromString("9a8b0007-6d5e-4c10-b6d9-1f25c09d9e00")
        const val PRESSURE_MATRIX_ENCRYPTION_ENABLED = true
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
    private val edemaCharUuid = UUID.fromString("9a8b0006-6d5e-4c10-b6d9-1f25c09d9e00")

    private val notificationQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var isProcessingQueue = false
    private val characteristicBuffers = mutableMapOf<UUID, ByteArrayOutputStream>()
    private val maxPayloadLength = 80
    private var keyExchangeTimeoutRunnable: Runnable? = null
    private val loadingDotFrames = listOf("", ".", "..", "...")
    private var loadingDotIndex = 0

    private val timestampedSensorData = mutableListOf<TimestampedSensorData>()

    private enum class DashboardStatusVisual {
        LOADING,
        CHECK,
        ALERT,
        TEXT_ONLY
    }

    private val loadingDotsRunnable = object : Runnable {
        override fun run() {
            statusLoadingDots.text = loadingDotFrames[loadingDotIndex]
            loadingDotIndex = (loadingDotIndex + 1) % loadingDotFrames.size
            uiHandler.postDelayed(this, 320)
        }
    }

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SENSOR_DATA) return

            val uuidString = intent.getStringExtra(EXTRA_UUID_STRING) ?: return
            val encryptedBytes = intent.getByteArrayExtra(EXTRA_DECRYPTED_DATA) ?: return

            try {
                val uuid = UUID.fromString(uuidString)
                when (uuid) {
                    heartRateCharUuid -> {
                        val bpm = AESCrypto.decryptHeartRate(encryptedBytes).trim().toIntOrNull() ?: return
                        latestBpm = bpm
                        heartRateText.text = formatValueWithUnit(bpm.toString(), "BPM")
                        heartStatus.text = heartRateState(bpm)
                    }
                    spo2CharUuid -> {
                        val spo2 = AESCrypto.decryptSpO2(encryptedBytes).trim().toDoubleOrNull() ?: return
                        latestSpo2 = spo2
                        spo2Text.text = formatValueWithUnit(String.format(Locale.US, "%.1f", spo2), "%")
                        spo2Status.text = spo2State(spo2)
                    }
                    edemaCharUuid -> {
                        val edemaPayload = AESCrypto.decryptFlex(encryptedBytes).trim()
                        val edema = edemaPayload.split(",").firstOrNull()?.trim().orEmpty()
                        if (edema.isEmpty()) return
                        latestEdema = edema
                        swellingText.text = edema.replaceFirstChar { it.uppercaseChar() }
                        swellingStatus.text = swellingState(edema)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process sensor data on dashboard: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        statusText = findViewById(R.id.statusText)
        statusIcon = findViewById(R.id.statusIcon)
        statusLoadingDots = findViewById(R.id.statusLoadingDots)
        statusContainer = findViewById(R.id.status_container)
        statusPrimaryRow = findViewById(R.id.status_primary_row)
        offlineCenterHint = findViewById(R.id.offlineCenterHint)
        deviceContent = findViewById(R.id.device_content)
        wearableImage = findViewById(R.id.wearable_image)
        disconnectedPanelUnderlay = findViewById(R.id.disconnected_panel_underlay)
        disconnectedPanel = findViewById(R.id.disconnected_panel)
        reconnectInlineButton = findViewById(R.id.reconnect_inline_button)
        scanDevicesInlineButton = findViewById(R.id.scan_devices_inline_button)
        toolbarUsername = findViewById(R.id.toolbar_username)
        wearableDashboardCard = findViewById(R.id.wearableDashboardCard)
        currentVitalsCard = findViewById(R.id.currentVitalsCard)
        heartRateText = findViewById(R.id.heartRateText)
        heartStatus = findViewById(R.id.heartStatus)
        spo2Text = findViewById(R.id.spo2Text)
        spo2Status = findViewById(R.id.spo2Status)
        spo2Card = findViewById(R.id.spo2Card)
        bpCard = findViewById(R.id.bpCard)
        swellingText = findViewById(R.id.swellingText)
        swellingStatus = findViewById(R.id.swellingStatus)
        swellingCard = findViewById(R.id.swellingCard)
        ataxiaCard = findViewById(R.id.ataxiaCard)
        sharingExportCard = findViewById(R.id.sharingExportCard)
        pressureMatrixCard = findViewById(R.id.pressureMatrixCard)
        vitalSignsCard = findViewById(R.id.vitalSignsCard)
        gaitAnalysisCard = findViewById(R.id.gaitAnalysisCard)

        fadeTargets = listOf(
            wearableImage,
            deviceContent,
            vitalSignsCard,
            currentVitalsCard,
            spo2Card,
            bpCard,
            swellingCard,
            ataxiaCard,
            gaitAnalysisCard,
            sharingExportCard,
            pressureMatrixCard
        )

        heartRateText.text = formatValueWithUnit("--", "BPM")
        spo2Text.text = formatValueWithUnit("--", "%")

        updateToolbarUsername()
        disconnectedPanel.bringToFront()
        setDisconnectedMode(true)

        reconnectInlineButton.setOnClickListener {
            reconnectLastDevice()
        }
        scanDevicesInlineButton.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        findViewById<android.view.View>(R.id.vitalSignsCard).setOnClickListener {
            startActivity(Intent(this, ReadingsActivity::class.java))
        }
        findViewById<TextView>(R.id.seeMoreDetailed).setOnClickListener {
            startActivity(Intent(this, ReadingsActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.gaitAnalysisCard).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SCROLL_TO_RESOURCES, true)
            })
        }

        findViewById<MaterialCardView>(R.id.pressureMatrixCard).setOnClickListener {
            startActivity(Intent(this, PressureMatrixActivity::class.java))
        }
        
        findViewById<Button>(R.id.calibrateButton)?.setOnClickListener {
            startActivity(Intent(this, PressureMatrixActivity::class.java).apply {
                putExtra(PressureMatrixActivity.EXTRA_START_CALIBRATION, true)
            })
        }

        findViewById<Button>(R.id.download_data_button).setOnClickListener {
            checkPermissionAndExport()
        }
        findViewById<Button>(R.id.share_report_button).setOnClickListener {
            shareCsvReport()
        }

        findViewById<View>(R.id.profile_card).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        toolbarUsername.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        findViewById<View>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
            if (!hasBluetoothConnectPermission()) {
                requestBluetoothConnectPermission()
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_bluetooth_permission_connect), Toast.LENGTH_SHORT).show()
                }
                return
            }

            lastDeviceAddress = deviceAddress
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                if (device != null) {
                    connectToDevice(device)
                } else {
                    Log.e(TAG, "Invalid device address: $deviceAddress")
                    updateDashboardStatus("Invalid Device", DashboardStatusVisual.TEXT_ONLY)
                    setDisconnectedMode(true)
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid Bluetooth address format: $deviceAddress")
                updateDashboardStatus("Invalid Device Address", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission while handling address", e)
                updateDashboardStatus("Permission Required", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
                requestBluetoothConnectPermission()
            }
        } else if (reconnectLast) {
            if (!hasBluetoothConnectPermission()) {
                requestBluetoothConnectPermission()
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_bluetooth_permission_reconnect), Toast.LENGTH_SHORT).show()
                }
                return
            }

            lastDeviceAddress?.let { address ->
                try {
                    val device = bluetoothAdapter?.getRemoteDevice(address)
                    if (device != null) {
                        connectToDevice(device)
                    } else {
                        Log.e(TAG, "Cached device no longer available: $address")
                    }
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid cached address: $address")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing Bluetooth permission during reconnect", e)
                    requestBluetoothConnectPermission()
                }
            }
        } else {
            isDeviceConnected = false
            setDisconnectedMode(true)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_CONNECT_REQUEST_CODE
            )
        }
    }

    private fun formatValueWithUnit(value: String, unit: String): CharSequence {
        val formattedText = "$value $unit"
        return SpannableString(formattedText).apply {
            setSpan(
                RelativeSizeSpan(0.5f),
                value.length + 1,
                formattedText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sensorDataReceiver,
            IntentFilter(ACTION_SENSOR_DATA)
        )
    }

    override fun onResume() {
        super.onResume()
        reconcileDisconnectedOverlay()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            reconcileDisconnectedOverlay()
        }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
        super.onStop()
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
            timestampedSensorData.add(TimestampedSensorData(timestamp, latestBpm, latestSpo2, latestEdema))
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
        hasAttemptedConnection = true
        runOnUiThread { 
            setDisconnectedMode(false)
            updateDashboardStatus("Connecting", DashboardStatusVisual.LOADING)
        }

        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
            requestBluetoothConnectPermission()
            runOnUiThread {
                updateDashboardStatus("Permission Required", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            }
            return
        }
        
        // Verify Bluetooth adapter is available before attempting connection
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is not available")
            runOnUiThread { 
                Toast.makeText(this, getString(R.string.toast_bluetooth_not_available), Toast.LENGTH_SHORT).show()
                updateDashboardStatus("Bluetooth Unavailable", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            }
            return
        }
        
        try {
            cleanup() 
            keyExchangeManager = try {
                KeyExchangeManager()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize key exchange manager: ${e.message}, will use legacy mode")
                null
            }
            isKeyExchangeInProgress = keyExchangeManager != null
            
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
            
            if (bluetoothGatt == null) {
                Log.e(TAG, "Failed to create GATT connection")
                runOnUiThread { 
                    updateDashboardStatus("Connection Refused", DashboardStatusVisual.TEXT_ONLY)
                    setDisconnectedMode(true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}", e)
            runOnUiThread { 
                Toast.makeText(this, getString(R.string.toast_connection_failed, e.message ?: getString(R.string.unknown_error)), Toast.LENGTH_SHORT).show()
                updateDashboardStatus("Error", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                isDeviceConnected = false
                gatt.close()
                runOnUiThread { 
                    updateDashboardStatus("Disconnected", DashboardStatusVisual.TEXT_ONLY)
                    setDisconnectedMode(true)
                    broadcastDisconnection()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isDeviceConnected = true
                runOnUiThread { setDisconnectedMode(false) }
                uiHandler.postDelayed({
                    runOnUiThread {
                        updateDashboardStatus(getString(R.string.connected_discovering), DashboardStatusVisual.LOADING)
                    }
                    gatt.discoverServices()
                }, 800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isDeviceConnected = false
                runOnUiThread { 
                    updateDashboardStatus(getString(R.string.disconnected), DashboardStatusVisual.TEXT_ONLY)
                    setDisconnectedMode(true)
                    broadcastDisconnection()
                }
                cleanup()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val keyService = gatt.getService(keyExchangeServiceUuid)
                if (keyService == null) {
                    runOnUiThread { 
                        setDisconnectedMode(false)
                        updateDashboardStatus(getLocalizedConnectedLabel(), DashboardStatusVisual.CHECK)
                    }
                    AESCrypto.initWithLegacyKeys()
                    isKeyExchangeInProgress = false
                    setupDataNotifications(gatt)
                    return
                }
                
                // Device has key exchange service - attempt key exchange
                val phoneKeyChar = keyService.getCharacteristic(phonePublicKeyCharUuid)
                if (phoneKeyChar != null && keyExchangeManager != null) {
                    isKeyExchangeInProgress = true
                    phoneKeyChar.value = keyExchangeManager!!.publicKeyBytes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(phoneKeyChar, keyExchangeManager!!.publicKeyBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        gatt.writeCharacteristic(phoneKeyChar)
                    }
                    // Set a timeout for key exchange - if no response in 3 seconds, proceed with legacy mode
                    keyExchangeTimeoutRunnable = Runnable {
                        if (isKeyExchangeInProgress) {
                            Log.w(TAG, "Key exchange timeout - falling back to legacy mode")
                            isKeyExchangeInProgress = false
                            AESCrypto.initWithLegacyKeys()
                            setupDataNotifications(gatt)
                        }
                    }
                    uiHandler.postDelayed(keyExchangeTimeoutRunnable!!, 3000)
                    return
                }
                
                // Fallback: key exchange service exists but we can't access the characteristic
                Log.w(TAG, "Cannot access key exchange characteristic - falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != phonePublicKeyCharUuid) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to write phone public key, status=$status. Falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
                return
            }

            val keyService = gatt.getService(keyExchangeServiceUuid)
            val peripheralKeyChar = keyService?.getCharacteristic(peripheralPublicKeyCharUuid)
            if (peripheralKeyChar == null) {
                Log.e(TAG, "Peripheral public key characteristic not found. Falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
                return
            }

            if (!gatt.readCharacteristic(peripheralKeyChar)) {
                Log.e(TAG, "Failed to start peripheral public key read. Falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid != peripheralPublicKeyCharUuid) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                completeKeyExchange(gatt, value)
            } else {
                Log.e(TAG, "Failed to read peripheral public key, status=$status. Falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != peripheralPublicKeyCharUuid) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: ByteArray(0)
                completeKeyExchange(gatt, value)
            } else {
                Log.e(TAG, "Failed to read peripheral public key, status=$status. Falling back to legacy mode")
                isKeyExchangeInProgress = false
                AESCrypto.initWithLegacyKeys()
                setupDataNotifications(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Handle device public key response during key exchange
            if (isKeyExchangeInProgress && characteristic.uuid == peripheralPublicKeyCharUuid) {
                val devicePubKeyBytes = characteristic.value ?: ByteArray(0)
                completeKeyExchange(gatt, devicePubKeyBytes)
                return
            }
            
            if(isKeyExchangeInProgress) return
            val bytes = characteristic.value ?: return
            
            if (characteristic.uuid == pressureCharUuid) {
                val intent = Intent(ACTION_SENSOR_DATA).apply {
                    putExtra(EXTRA_UUID_STRING, characteristic.uuid.toString())
                    putExtra(EXTRA_DECRYPTED_DATA, bytes)
                }
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)
                return
            }
            
            val buffer = characteristicBuffers.getOrPut(characteristic.uuid) { ByteArrayOutputStream() }
            buffer.write(bytes)
            drainBuffer(characteristic, buffer)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isProcessingQueue = false
            processNotificationQueue(gatt)
        }
    }

    private fun completeKeyExchange(gatt: BluetoothGatt, devicePubKeyBytes: ByteArray) {
        if (!isKeyExchangeInProgress) return

        if (devicePubKeyBytes.size != PERIPHERAL_PUBLIC_KEY_LENGTH || keyExchangeManager == null) {
            Log.e(TAG, "Invalid peripheral public key; falling back to legacy mode")
            isKeyExchangeInProgress = false
            keyExchangeTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
            AESCrypto.initWithLegacyKeys()
            setupDataNotifications(gatt)
            return
        }

        try {
            val sharedSecret = keyExchangeManager!!.generateSharedSecret(devicePubKeyBytes)
            AESCrypto.init(sharedSecret)
            Log.i(TAG, "Key exchange successful, shared secret established")
            runOnUiThread {
                setDisconnectedMode(false)
                updateDashboardStatus(getLocalizedConnectedLabel(), DashboardStatusVisual.CHECK)
            }
            isKeyExchangeInProgress = false
            keyExchangeTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
            setupDataNotifications(gatt)
        } catch (e: Exception) {
            Log.e(TAG, "Key exchange failed: ${e.message}")
            isKeyExchangeInProgress = false
            keyExchangeTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
            AESCrypto.initWithLegacyKeys()
            setupDataNotifications(gatt)
        }
    }

    private fun setupDataNotifications(gatt: BluetoothGatt) {
        val imuService = gatt.getService(imuServiceUuid)
        if (imuService == null) {
            Log.w(TAG, "IMU service not found on device")
            runOnUiThread {
                Toast.makeText(this, getString(R.string.toast_device_missing_service), Toast.LENGTH_SHORT).show()
                updateDashboardStatus("Incomplete Device", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            }
            return
        }
        
        val characteristics = listOf(
            imuService.getCharacteristic(accelCharUuid),
            imuService.getCharacteristic(gyroCharUuid),
            imuService.getCharacteristic(heartRateCharUuid),
            imuService.getCharacteristic(spo2CharUuid),
            imuService.getCharacteristic(edemaCharUuid),
            imuService.getCharacteristic(pressureCharUuid)
        )
        
        val validCharacteristics = characteristics.filterNotNull()
        if (validCharacteristics.isEmpty()) {
            Log.e(TAG, "No sensor characteristics found on device")
            runOnUiThread {
                Toast.makeText(this, getString(R.string.toast_device_no_characteristics), Toast.LENGTH_SHORT).show()
                updateDashboardStatus("Incompatible Device", DashboardStatusVisual.TEXT_ONLY)
                setDisconnectedMode(true)
            }
            return
        }
        
        Log.i(TAG, "Found ${validCharacteristics.size} sensor characteristics")
        notificationQueue.addAll(validCharacteristics)
        processNotificationQueue(gatt)
    }

    private fun processNotificationQueue(gatt: BluetoothGatt) {
        if (isProcessingQueue || notificationQueue.isEmpty()) {
            if (!isProcessingQueue) {
                if (!isDeviceConnected || bluetoothGatt == null) {
                    return
                }
                updateDashboardStatus(getString(R.string.system_ready), DashboardStatusVisual.CHECK)
                startDataLogging()
            }
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

    private fun getLocalizedConnectedLabel(): String {
        val connectedDiscovering = getString(R.string.connected_discovering)
        return connectedDiscovering.substringBefore('.').trim().ifEmpty { connectedDiscovering }
    }

    private fun broadcastDisconnection() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_DEVICE_DISCONNECTED))
    }

    private fun cleanup() {
        isDeviceConnected = false
        uiHandler.removeCallbacks(loadingDotsRunnable)
        keyExchangeTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        keyExchangeTimeoutRunnable = null
        notificationQueue.clear()
        isProcessingQueue = false
        characteristicBuffers.values.forEach { it.reset() }
        characteristicBuffers.clear()
        bluetoothGatt?.close()
        bluetoothGatt = null
        stopDataLogging()
        keyExchangeManager?.close()
        keyExchangeManager = null
    }

    private fun reconnectLastDevice() {
        if (!hasBluetoothConnectPermission()) {
            requestBluetoothConnectPermission()
            Toast.makeText(this, getString(R.string.toast_bluetooth_permission_reconnect), Toast.LENGTH_SHORT).show()
            return
        }

        lastDeviceAddress?.let { address ->
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                if (device != null) {
                    connectToDevice(device)
                } else {
                    startActivity(Intent(this, ScanActivity::class.java))
                }
            } catch (e: IllegalArgumentException) {
                startActivity(Intent(this, ScanActivity::class.java))
            } catch (e: SecurityException) {
                requestBluetoothConnectPermission()
            }
        } ?: startActivity(Intent(this, ScanActivity::class.java))
    }

    private fun reconcileDisconnectedOverlay() {
        if (!hasAttemptedConnection && !isDeviceConnected && bluetoothGatt == null) {
            setDisconnectedMode(true)
            return
        }

        val shouldShowDisconnected = !isDeviceConnected || bluetoothGatt == null
        if (shouldShowDisconnected) {
            setDisconnectedMode(true)
        } else {
            setDisconnectedMode(false)
        }
    }

    private fun setDisconnectedMode(disconnected: Boolean) {
        if (disconnected) {
            disconnectedPanelUnderlay.visibility = View.VISIBLE
            disconnectedPanel.visibility = View.VISIBLE
            disconnectedPanelUnderlay.bringToFront()
            disconnectedPanelUnderlay.post {
                disconnectedPanelUnderlay.requestLayout()
                disconnectedPanelUnderlay.invalidate()
            }
            disconnectedPanel.alpha = 1.0f
            disconnectedPanel.translationZ = 100f
            disconnectedPanel.bringToFront()
            (disconnectedPanel.parent as? View)?.invalidate()
            disconnectedPanel.post {
                disconnectedPanel.bringToFront()
                disconnectedPanel.requestLayout()
                disconnectedPanel.invalidate()
            }
            applyDashboardFade(0.42f)
            statusPrimaryRow.setBackgroundResource(R.drawable.status_offline_chip_bg)
            offlineCenterHint.visibility = View.VISIBLE
            updateDashboardStatus("Sock Offline", DashboardStatusVisual.ALERT)
        } else {
            disconnectedPanelUnderlay.visibility = View.GONE
            disconnectedPanel.visibility = View.GONE
            applyDashboardFade(1.0f)
            statusPrimaryRow.background = null
            offlineCenterHint.visibility = View.GONE
        }
    }

    private fun applyDashboardFade(alpha: Float) {
        fadeTargets.forEach { view ->
            view.alpha = alpha
            view.isEnabled = alpha >= 1.0f
        }
    }

    private fun updateDashboardStatus(text: String, visual: DashboardStatusVisual) {
        uiHandler.removeCallbacks(loadingDotsRunnable)

        statusContainer.post {
            statusText.text = text
            statusText.requestLayout()
            statusPrimaryRow.requestLayout()
            statusContainer.requestLayout()
        }

        when (visual) {
            DashboardStatusVisual.LOADING -> {
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                statusIcon.visibility = View.GONE
                statusLoadingDots.visibility = View.VISIBLE
                loadingDotIndex = 0
                uiHandler.post(loadingDotsRunnable)
            }
            DashboardStatusVisual.CHECK -> {
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                statusIcon.setImageResource(R.drawable.ic_status_check)
                statusIcon.imageTintList = ContextCompat.getColorStateList(this, android.R.color.white)
                statusIcon.visibility = View.VISIBLE
                statusLoadingDots.visibility = View.GONE
            }
            DashboardStatusVisual.ALERT -> {
                statusText.setTextColor(ContextCompat.getColor(this, R.color.dark_blue))
                statusIcon.setImageResource(R.drawable.ic_circle_alert_black)
                statusIcon.imageTintList = null
                statusIcon.visibility = View.VISIBLE
                statusLoadingDots.visibility = View.GONE
            }
            DashboardStatusVisual.TEXT_ONLY -> {
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                statusIcon.visibility = View.GONE
                statusLoadingDots.visibility = View.GONE
            }
        }
    }

    private fun drainBuffer(characteristic: BluetoothGattCharacteristic, buffer: ByteArrayOutputStream) {
        while (buffer.size() > 0) {
            val packet = buffer.toByteArray()
            val payloadLength = packet[0].toInt() and 0xFF
            if (packet.size - 1 < payloadLength) return
            val messageBytes = packet.copyOfRange(1, payloadLength + 1)
            val remainingBytes = packet.copyOfRange(payloadLength + 1, packet.size)
            buffer.reset()
            buffer.write(remainingBytes)

            val intent = Intent(ACTION_SENSOR_DATA).apply {
                putExtra(EXTRA_UUID_STRING, characteristic.uuid.toString())
                putExtra(EXTRA_DECRYPTED_DATA, messageBytes)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun exportDataToCsv() {
        val fileUri = createCsvInDownloads()
        if (fileUri != null) {
            Toast.makeText(this, getString(R.string.toast_csv_exported), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCsvReport() {
        val fileUri = createCsvInDownloads()
        if (fileUri == null) {
            Toast.makeText(this, getString(R.string.toast_unable_generate_csv), Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "SoleMate Health Report")
            putExtra(Intent.EXTRA_TEXT, "Attached is my latest SoleMate CSV report.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.chooser_share_report)))
    }

    private fun createCsvInDownloads(): Uri? {
        val username = getSharedPreferences("SolematePrefs", MODE_PRIVATE).getString("username", "user")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val fileName = "${username}_sensor_data_${date}.csv"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) return null

                resolver.openOutputStream(uri)?.use { os ->
                    os.write("Timestamp,Heart Rate,SpO2,Edema\n".toByteArray())
                    timestampedSensorData.forEach { data ->
                        val heartRate = data.heartRate?.toString() ?: ""
                        val spo2 = data.spo2?.toString() ?: ""
                        val edema = data.edema ?: ""
                        os.write("${data.timestamp},$heartRate,$spo2,$edema\n".toByteArray())
                    }
                }

                return uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
        }

        return null
    }

    private fun updateToolbarUsername() {
        val username = getSharedPreferences("SolematePrefs", MODE_PRIVATE).getString("username", "Username")
        toolbarUsername.text = username
    }

    private fun heartRateState(bpm: Int): String {
        return when {
            bpm < 60 -> "Low"
            bpm < 100 -> "Normal"
            bpm < 140 -> "Elevated"
            else -> "High"
        }
    }

    private fun spo2State(spo2: Double): String {
        return when {
            spo2 >= 95 -> "Healthy"
            spo2 >= 90 -> "Watch"
            spo2 >= 85 -> "Low"
            else -> "Critical"
        }
    }

    private fun swellingState(edema: String): String {
        return when (edema.lowercase(Locale.US)) {
            "none" -> "Stable"
            "subclinical" -> "Monitor"
            "mild" -> "Mild"
            "moderate" -> "Moderate"
            "severe" -> "Severe"
            else -> "Stable"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }
}