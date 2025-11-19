package com.example.ble_viewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceListAdapter(
    private val devices: MutableList<Pair<String, String>>,
    private val onDeviceClicked: (Pair<String, String>) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

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

    fun addDevice(device: Pair<String, String>) {
        if (!devices.any { it.second == device.second }) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName: TextView = itemView.findViewById(R.id.device_name)
        val deviceAddress: TextView = itemView.findViewById(R.id.device_address)
    }
}