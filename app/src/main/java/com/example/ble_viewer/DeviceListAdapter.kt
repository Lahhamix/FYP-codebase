package com.example.ble_viewer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class DeviceScanResult(val name: String, val address: String, val device: android.bluetooth.BluetoothDevice)

class DeviceListAdapter(
    private val devices: MutableList<DeviceScanResult>,
    private val onDeviceClick: (DeviceScanResult) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    @SuppressLint("NotifyDataSetChanged")
    fun addDevice(device: DeviceScanResult) {
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            notifyDataSetChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearDevices() {
        devices.clear()
        notifyDataSetChanged()
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceName: TextView = itemView.findViewById(R.id.device_name)
        private val deviceAddress: TextView = itemView.findViewById(R.id.device_address)

        fun bind(device: DeviceScanResult) {
            // If the name is null or "Unknown", we follow the image's "BLE Device (Address)" style
            val displayName = if (device.name == "Unknown Device" || device.name == "Unknown") {
                "BLE Device (${device.address})"
            } else {
                device.name
            }
            
            deviceName.text = displayName
            deviceAddress.text = device.address
            itemView.setOnClickListener { onDeviceClick(device) }
        }
    }
}
