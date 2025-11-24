package com.example.ble_viewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class DeviceListAdapter(
    private val devices: MutableList<Pair<String, String>>,
    private val onDeviceClicked: (Pair<String, String>) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    // Use a Set to track seen device addresses for faster duplicate detection
    // Using Collections.synchronizedSet for thread safety
    private val seenAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (deviceName, deviceAddress) = devices[position]
        holder.deviceName.text = deviceName
        holder.deviceAddress.text = deviceAddress
        holder.itemView.setOnClickListener { onDeviceClicked(devices[position]) }
    }

    override fun getItemCount() = devices.size

    @Synchronized
    fun addDevice(device: Pair<String, String>): Boolean {
        // Normalize address: uppercase and trim to handle case sensitivity and whitespace
        val normalizedAddress = device.second.uppercase().trim()
        
        // Check if we've already seen this address
        if (seenAddresses.contains(normalizedAddress)) {
            Log.v("DeviceListAdapter", "Device already in list: ${device.first} ($normalizedAddress) - skipping")
            return false
        }
        
        // Add to seen set and device list
        seenAddresses.add(normalizedAddress)
        devices.add(device)
        notifyItemInserted(devices.size - 1)
        Log.d("DeviceListAdapter", "✅ Added device: ${device.first} ($normalizedAddress), Total: ${devices.size}")
        return true
    }
    
    fun clearDevices() {
        val previousCount = devices.size
        seenAddresses.clear()
        devices.clear()
        notifyDataSetChanged()
        Log.d("DeviceListAdapter", "Cleared $previousCount devices from list")
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName: TextView = itemView.findViewById(R.id.device_name)
        val deviceAddress: TextView = itemView.findViewById(R.id.device_address)
    }
}