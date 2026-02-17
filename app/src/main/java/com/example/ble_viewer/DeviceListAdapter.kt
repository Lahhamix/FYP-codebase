package com.example.ble_viewer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceListAdapter(
    private val devices: MutableList<Pair<String, String>>,
    private val onDeviceClick: (Pair<String, String>) -> Unit
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
    fun addDevice(device: Pair<String, String>) {
        if (!devices.contains(device)) {
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

        fun bind(device: Pair<String, String>) {
            deviceName.text = device.first
            deviceAddress.text = device.second
            itemView.setOnClickListener { onDeviceClick(device) }
        }
    }
}
