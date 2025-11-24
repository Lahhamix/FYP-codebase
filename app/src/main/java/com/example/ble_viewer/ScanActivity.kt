package com.example.ble_viewer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("MissingPermission")
class ScanActivity : AppCompatActivity() {

    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var cancelButton: Button

    private lateinit var deviceListAdapter: DeviceListAdapter
    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        devicesRecyclerView = findViewById(R.id.devices_recycler_view)
        cancelButton = findViewById(R.id.cancel_button)

        deviceListAdapter = DeviceListAdapter(mutableListOf()) { (deviceName, deviceAddress) ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e("ScanActivity", "Error stopping scan: ${e.message}")
            }
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("DEVICE_ADDRESS", deviceAddress)
            startActivity(intent)
            finish()
        }

        devicesRecyclerView.adapter = deviceListAdapter
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        cancelButton.setOnClickListener {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e("ScanActivity", "Error stopping scan: ${e.message}")
            }
            finish()
        }

        requestPermissionsAndStartScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e("ScanActivity", "Error stopping scan in onDestroy: ${e.message}")
        }
    }

    private fun requestPermissionsAndStartScan() {
        // Check if Bluetooth is available
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            Log.e("ScanActivity", "Bluetooth adapter is null")
            return
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1)
            return
        }

        // Check location services - REQUIRED for BLE scanning on all Android versions
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        if (!isLocationEnabled) {
            Log.w("ScanActivity", "⚠️ Location services are disabled - BLE scanning REQUIRES location to be ON")
            Toast.makeText(this, "Location services MUST be enabled for BLE scanning. Please enable it.", Toast.LENGTH_LONG).show()
            val locationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(locationIntent)
            return
        } else {
            Log.d("ScanActivity", "✅ Location services are enabled")
        }

        val permissionsToRequest = mutableListOf<String>()
        
        // Android 12+ (API 31+) requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Some Android 12+ devices still need location permission
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            // Android 11 and below require location permission for BLE scanning
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("ScanActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.d("ScanActivity", "All permissions granted, checking location services and starting scan")
            startScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            // Bluetooth enable request result
            if (bluetoothAdapter?.isEnabled == true) {
                requestPermissionsAndStartScan()
            } else {
                Toast.makeText(this, "Bluetooth must be enabled to scan for devices", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("ScanActivity", "All permissions granted")
                startScan()
            } else {
                Log.e("ScanActivity", "Permissions denied")
                Toast.makeText(this, "Permissions are required to scan for BLE devices", Toast.LENGTH_LONG).show()
                // Show which permissions were denied
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                Log.e("ScanActivity", "Denied permissions: ${deniedPermissions.joinToString()}")
            }
        }
    }

    private fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e("ScanActivity", "Cannot start scan: Bluetooth not available or not enabled")
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e("ScanActivity", "BLE Scanner is null")
            Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous scan results and seen addresses when starting a new scan
        deviceListAdapter.clearDevices()
        Log.d("ScanActivity", "Cleared previous devices list")

        // Stop any existing scan first to avoid conflicts
        try {
            scanner.stopScan(scanCallback)
            Log.d("ScanActivity", "Stopped any existing scan")
        } catch (e: Exception) {
            Log.d("ScanActivity", "No existing scan to stop: ${e.message}")
        }

        Log.d("ScanActivity", "Starting BLE scan...")
        Log.d("ScanActivity", "Android version: ${Build.VERSION.SDK_INT}")
        Log.d("ScanActivity", "Bluetooth enabled: ${bluetoothAdapter?.isEnabled}")
        
        // Verify ALL required permissions before scanning
        var allPermissionsGranted = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.e("ScanActivity", "❌ BLUETOOTH_SCAN permission not granted!")
                Toast.makeText(this, "BLUETOOTH_SCAN permission required", Toast.LENGTH_LONG).show()
                allPermissionsGranted = false
            } else {
                Log.d("ScanActivity", "✅ BLUETOOTH_SCAN permission granted")
            }
            
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e("ScanActivity", "❌ BLUETOOTH_CONNECT permission not granted!")
                Toast.makeText(this, "BLUETOOTH_CONNECT permission required", Toast.LENGTH_LONG).show()
                allPermissionsGranted = false
            } else {
                Log.d("ScanActivity", "✅ BLUETOOTH_CONNECT permission granted")
            }
        }
        
        // Location permission is required on ALL Android versions for BLE scanning
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ScanActivity", "❌ ACCESS_FINE_LOCATION permission not granted!")
            Toast.makeText(this, "Location permission required for BLE scanning", Toast.LENGTH_LONG).show()
            allPermissionsGranted = false
        } else {
            Log.d("ScanActivity", "✅ ACCESS_FINE_LOCATION permission granted")
        }
        
        if (!allPermissionsGranted) {
            Log.e("ScanActivity", "❌ Missing required permissions - requesting again...")
            requestPermissionsAndStartScan()
            return
        }
        
        // Use LOW_LATENCY mode for immediate results
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0) // Report results immediately
            .build()
        
        // Use null filters to scan for all devices
        val filters: List<android.bluetooth.le.ScanFilter>? = null
        
        try {
            Log.d("ScanActivity", "Attempting to start scan with LOW_LATENCY mode...")
            scanner.startScan(filters, settings, scanCallback)
            Log.d("ScanActivity", "✅ Scan started successfully with mode: LOW_LATENCY")
            runOnUiThread {
                Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
            }
            
            // Log test messages to verify scan is running
            devicesRecyclerView.postDelayed({
                Log.d("ScanActivity", "=== SCAN STATUS CHECK (3s) ===")
                Log.d("ScanActivity", "Devices found: ${deviceListAdapter.itemCount}")
                Log.d("ScanActivity", "If no devices found, check:")
                Log.d("ScanActivity", "1. Is Arduino advertising? (Check Serial Monitor for '📡 Advertising as Cura...')")
                Log.d("ScanActivity", "2. Are permissions granted?")
                Log.d("ScanActivity", "3. Check for '✅ onScanResult CALLED!' logs above")
                Log.d("ScanActivity", "4. Try restarting Bluetooth on phone")
            }, 3000)
            
            devicesRecyclerView.postDelayed({
                Log.d("ScanActivity", "=== SCAN STATUS CHECK (10s) ===")
                Log.d("ScanActivity", "Devices found: ${deviceListAdapter.itemCount}")
                if (deviceListAdapter.itemCount == 0) {
                    Log.w("ScanActivity", "⚠️ No devices found after 10 seconds!")
                    Log.w("ScanActivity", "Troubleshooting:")
                    Log.w("ScanActivity", "   - Check Arduino Serial Monitor - should show '📡 Advertising as Cura...'")
                    Log.w("ScanActivity", "   - Verify Bluetooth is ON and Location is ON")
                    Log.w("ScanActivity", "   - Try restarting the scan")
                    Log.w("ScanActivity", "   - Check if '✅ onScanResult CALLED!' appears in logs")
                    
                    // Try restarting scan
                    runOnUiThread {
                        Toast.makeText(this@ScanActivity, "No devices found. Check Arduino is advertising.", Toast.LENGTH_LONG).show()
                    }
                }
            }, 10000)
            
        } catch (e: SecurityException) {
            Log.e("ScanActivity", "SecurityException: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: IllegalStateException) {
            Log.e("ScanActivity", "IllegalStateException: ${e.message}", e)
            // Try stopping and restarting
            try {
                scanner.stopScan(scanCallback)
                Thread.sleep(100)
                scanner.startScan(filters, settings, scanCallback)
                Log.d("ScanActivity", "Restarted scan after IllegalStateException")
            } catch (e2: Exception) {
                Log.e("ScanActivity", "Error restarting scan: ${e2.message}", e2)
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e2.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Error starting scan: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Error starting scan: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            Log.d("ScanActivity", "✅✅✅✅✅ onScanResult CALLED! callbackType: $callbackType")
            if (result == null) {
                Log.e("ScanActivity", "❌ Result is null in onScanResult")
                return
            }
            val device = result.device
            if (device == null) {
                Log.e("ScanActivity", "❌ Device is null in onScanResult")
                return
            }
            val address = device.address ?: "unknown"
            val rssi = result.rssi
            Log.d("ScanActivity", "📡📡📡 Found device in callback: $address, RSSI: $rssi")
            processScanResult(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            Log.d("ScanActivity", "✅✅✅✅✅ onBatchScanResults CALLED! ${results?.size ?: 0} devices")
            if (results == null || results.isEmpty()) {
                Log.w("ScanActivity", "⚠️ Batch results is null or empty")
                return
            }
            Log.d("ScanActivity", "Processing ${results.size} devices from batch...")
            results.forEachIndexed { index, result ->
                val device = result.device
                val address = device?.address ?: "unknown"
                Log.d("ScanActivity", "📡📡📡 Batch device $index: $address, RSSI: ${result.rssi}")
                processScanResult(result)
            }
        }
        
        @SuppressLint("MissingPermission")
        private fun processScanResult(result: ScanResult?) {
            if (result == null) {
                Log.w("ScanActivity", "Received null scan result")
                return
            }
            
            val device = result.device
            if (device == null) {
                Log.w("ScanActivity", "Device is null in scan result")
                return
            }

            // Get address - this should always work
            val address = try {
                device.address ?: run {
                    Log.e("ScanActivity", "❌ Device address is null - skipping device")
                    return
                }
            } catch (e: SecurityException) {
                Log.e("ScanActivity", "❌ SecurityException getting address: ${e.message}")
                // Try to continue anyway with a placeholder
                "UNKNOWN_${System.currentTimeMillis()}"
            } catch (e: Exception) {
                Log.e("ScanActivity", "❌ Error getting address: ${e.message}", e)
                return
            }

            val normalizedAddress = address.uppercase().trim()
            Log.d("ScanActivity", "Processing device with address: $normalizedAddress, RSSI: ${result.rssi}")

            // Try multiple ways to get device name - but don't fail if we can't get it
            var deviceName: String? = null
            
            // Method 1: From scan record (most reliable, doesn't need BLUETOOTH_CONNECT)
            try {
                deviceName = result.scanRecord?.deviceName
                if (!deviceName.isNullOrBlank()) {
                    Log.d("ScanActivity", "✅ Got name from scanRecord: $deviceName")
                }
            } catch (e: Exception) {
                Log.w("ScanActivity", "Could not get name from scanRecord: ${e.message}")
            }
            
            // Method 2: From device name (requires BLUETOOTH_CONNECT permission on Android 12+)
            if (deviceName.isNullOrBlank()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // On Android 12+, we need BLUETOOTH_CONNECT permission
                        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            deviceName = device.name
                            if (!deviceName.isNullOrBlank()) {
                                Log.d("ScanActivity", "✅ Got name from device.name: $deviceName")
                            }
                        } else {
                            Log.w("ScanActivity", "⚠️ BLUETOOTH_CONNECT permission not granted - cannot get device name")
                        }
                    } else {
                        // On older Android, we can try without special permission
                        deviceName = device.name
                        if (!deviceName.isNullOrBlank()) {
                            Log.d("ScanActivity", "✅ Got name from device.name: $deviceName")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w("ScanActivity", "SecurityException getting device name: ${e.message}")
                } catch (e: Exception) {
                    Log.w("ScanActivity", "Error getting device name: ${e.message}")
                }
            }
            
            // Method 3: Use address as name if we can't get the name
            if (deviceName.isNullOrBlank()) {
                deviceName = "BLE Device ($normalizedAddress)"
                Log.d("ScanActivity", "Using address as device name: $deviceName")
            }

            Log.d("ScanActivity", "Final device info: Name=$deviceName, Address=$normalizedAddress, RSSI=${result.rssi}")

            runOnUiThread {
                try {
                    val added = deviceListAdapter.addDevice(Pair(deviceName!!, normalizedAddress))
                    if (added) {
                        Log.d("ScanActivity", "🔍✅✅✅✅✅ SUCCESS! Device added to UI: $deviceName ($normalizedAddress), RSSI: ${result.rssi}, Total devices: ${deviceListAdapter.itemCount}")
                    } else {
                        Log.d("ScanActivity", "⚠️ Device already in list (duplicate): $deviceName ($normalizedAddress)")
                    }
                } catch (e: Exception) {
                    Log.e("ScanActivity", "❌❌❌ Error adding device to adapter: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanActivity", "❌❌❌ Scan failed with error code: $errorCode")
            runOnUiThread {
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> {
                        Log.w("ScanActivity", "Scan already started, stopping and restarting...")
                        try {
                            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                            // Restart scan after a short delay
                            devicesRecyclerView.postDelayed({
                                Log.d("ScanActivity", "Restarting scan after SCAN_FAILED_ALREADY_STARTED")
                                startScan()
                            }, 1000)
                        } catch (e: Exception) {
                            Log.e("ScanActivity", "Error restarting scan: ${e.message}")
                        }
                    }
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                        Log.e("ScanActivity", "Application registration failed - try restarting app")
                        Toast.makeText(this@ScanActivity, "Application registration failed. Restart app.", Toast.LENGTH_LONG).show()
                    }
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                        Log.e("ScanActivity", "BLE scanning not supported on this device")
                        Toast.makeText(this@ScanActivity, "BLE scanning not supported", Toast.LENGTH_LONG).show()
                    }
                    SCAN_FAILED_INTERNAL_ERROR -> {
                        Log.e("ScanActivity", "Internal error - trying to restart scan")
                        Toast.makeText(this@ScanActivity, "Internal error. Retrying...", Toast.LENGTH_SHORT).show()
                        devicesRecyclerView.postDelayed({
                            startScan()
                        }, 2000)
                    }
                    else -> {
                        Log.e("ScanActivity", "Unknown scan error: $errorCode")
                        Toast.makeText(this@ScanActivity, "Scan failed: $errorCode", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}