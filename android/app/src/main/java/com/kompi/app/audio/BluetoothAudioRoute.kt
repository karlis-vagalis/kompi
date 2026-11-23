package com.kompi.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Uses Android's communication-device routing API; it never captures or transmits audio. */
object BluetoothAudioRoute {
    fun routeToBluetooth(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "Bluetooth communication routing requires Android 12 or newer"
        return try {
            val manager = context.getSystemService(AudioManager::class.java)
            val device = manager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return "Connect a Bluetooth headset first"
            if (manager.setCommunicationDevice(device)) "Bluetooth headset · ${device.productName}"
            else "Android could not select that headset"
        } catch (_: SecurityException) { "Allow Bluetooth permission to select a headset" }
    }
    fun useSystemRoute(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "System audio routing"
        return try {
            context.getSystemService(AudioManager::class.java).clearCommunicationDevice()
            "System audio routing"
        } catch (_: SecurityException) { "Allow Bluetooth permission to manage communication routing" }
    }
    /**
     * Resolve the selected Bluetooth communication output to its matching capture input.
     * Null means the system route is not Bluetooth; a selected Bluetooth route without a
     * matching input must be treated as unavailable rather than silently using the handset mic.
     */
    fun selectedBluetoothCommunicationDevice(context: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        val selected = context.getSystemService(AudioManager::class.java).communicationDevice ?: return null
        return selected.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }

    fun selectedBluetoothInput(context: Context): AudioDeviceInfo? {
        val selected = selectedBluetoothCommunicationDevice(context) ?: return null
        return inputForDevice(context, selected)
    }

    /** Resolve a specific communication output, including a route just selected for Live. */
    fun inputForDevice(context: Context, selected: AudioDeviceInfo): AudioDeviceInfo? {
        val manager = context.getSystemService(AudioManager::class.java)
        val candidates = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { input ->
            input.type == selected.type && (selected.address.isNullOrBlank() || input.address == selected.address)
        }
        return candidates.singleOrNull()
    }

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "System audio routing"
        return try {
            val device = context.getSystemService(AudioManager::class.java).communicationDevice
            if (device == null) "System audio routing" else device.productName.toString()
        } catch (_: SecurityException) { "Bluetooth permission required" }
    }
}
