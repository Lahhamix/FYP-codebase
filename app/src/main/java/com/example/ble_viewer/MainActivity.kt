package com.example.ble_viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.bluetooth.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.ByteArrayOutputStream

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

            val intent = Intent(ACTION_SENSOR_DATA).apply {
                putExtra(EXTRA_UUID_STRING, characteristic.uuid.toString())
                putExtra(EXTRA_DECRYPTED_DATA, messageBytes)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
