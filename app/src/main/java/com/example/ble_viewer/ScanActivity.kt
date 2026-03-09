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

        deviceListAdapter = DeviceListAdapter(mutableListOf()) { scanResult ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e("ScanActivity", "Error stopping scan: ${e.message}")
            }
            // Delay 500ms after stopping scan - BLE stack needs time to settle (fixes GATT 133)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("DEVICE_ADDRESS", scanResult.device.address)
                startActivity(intent)
                finish()
            }, 500)
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
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1)
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            Toast.makeText(this, "Location services MUST be enabled for BLE scanning.", Toast.LENGTH_LONG).show()
            val locationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(locationIntent)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && bluetoothAdapter?.isEnabled == true) {
            requestPermissionsAndStartScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            Toast.makeText(this, "Permissions are required to scan for BLE devices", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        deviceListAdapter.clearDevices()
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanner.startScan(null, settings, scanCallback)
        Log.d("ScanActivity", "Scan started...")
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { processScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanActivity", "Scan failed with error code: $errorCode")
        }
    }

    private fun processScanResult(result: ScanResult) {
        val device = result.device
        val deviceName = result.scanRecord?.deviceName

        if (deviceName != null && deviceName.equals("SoleMate", ignoreCase = true)) {
            val deviceAddress = device.address
            Log.d("ScanActivity", "Found SoleMate device: $deviceName ($deviceAddress)")
            runOnUiThread {
                deviceListAdapter.addDevice(DeviceScanResult(deviceName, deviceAddress, device))
            }
        }
    }
}