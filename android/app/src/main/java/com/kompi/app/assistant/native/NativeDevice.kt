package com.kompi.app.assistant.native

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

internal object NativeDevice {
    fun parse(name: String, input: JSONObject): NativeOperation? = when (name) {
        "phone_status" -> {
            NativeArguments(input)
            NativeTask(name, "Share battery, charging, available storage, network type and next system alarm.") { context, _ ->
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val network = context.getSystemService(ConnectivityManager::class.java)
                val caps = network.getNetworkCapabilities(network.activeNetwork)
                val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock
                JSONObject().put("status", "read").put("batteryPercent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
                    .put("charging", if (status == null || status == -1) JSONObject.NULL else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("availableStorageBytes", StatFs(context.filesDir.path).availableBytes)
                    .put("network", when { caps == null -> "none"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"; caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"; else -> "other" })
                    .put("nextAlarmAt", alarm?.triggerTime ?: JSONObject.NULL)
            }
        }
        "set_volume" -> {
            val a = NativeArguments(input, "stream", "percent")
            val stream = a.choice("stream", setOf("media", "ring", "alarm"))
            val percent = a.long("percent", 0, 100).toInt()
            NativeTask(name, "Set $stream volume to $percent%.") { context, _ ->
                val audio = context.getSystemService(AudioManager::class.java)
                val id = when (stream) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
                if (audio.isVolumeFixed) nativeError("unavailable", "This device uses fixed volume.")
                else {
                    val max = audio.getStreamMaxVolume(id)
                    val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(id) else 0
                    val target = (max * percent / 100.0).toInt().coerceAtLeast(min)
                    // Do not implicitly enter/leave silent or DND modes through a ring-volume request.
                    if (stream == "ring" && (target == 0 || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL))
                        nativeError("use_system_settings", "Change silent/ringer mode in Android settings. This volume request will not change it.")
                    else { audio.setStreamVolume(id, target, 0); performed("Volume change submitted.").put("observedVolume", audio.getStreamVolume(id)).put("maxVolume", max) }
                }
            }
        }
        "set_flashlight" -> {
            val enabled = NativeArguments(input, "enabled").bool("enabled")
            NativeTask(name, "Turn the flashlight ${if (enabled) "on" else "off"}.") { context, _ ->
                if (!hasPermission(context, Manifest.permission.CAMERA)) missingPermission("Camera")
                else {
                    val cameras = context.getSystemService(CameraManager::class.java)
                    val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    if (id == null) nativeError("unavailable", "No flashlight is available.")
                    else { cameras.setTorchMode(id, enabled); performed("Flashlight request accepted by Android.") }
                }
            }
        }
        "vibrate" -> {
            val duration = NativeArguments(input, "milliseconds").long("milliseconds", 1, 1000)
            NativeTask(name, "Vibrate the phone for $duration milliseconds.") { context, _ ->
                @Suppress("DEPRECATION") val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (!vibrator.hasVibrator()) nativeError("unavailable", "This device has no vibrator.")
                else { vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)); performed("Vibration submitted; system sound policy may suppress it.") }
            }
        }
        "set_brightness" -> {
            val percent = NativeArguments(input, "percent").long("percent", 1, 100)
            NativeTask(name, "Set system screen brightness to $percent% and turn off adaptive brightness.") { context, _ ->
                if (!Settings.System.canWrite(context)) nativeError("special_access_required", "Allow Modify system settings for Kompi in Phone access settings.")
                else {
                    val mode = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    val changed = mode && Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).toInt())
                    if (changed) performed("System brightness was updated.") else nativeError("not_applied", "Android did not apply brightness. Check display settings.")
                }
            }
        }
        "set_do_not_disturb" -> {
            val mode = NativeArguments(input, "mode").choice("mode", setOf("off", "priority", "alarms", "none"))
            NativeTask(name, "Request Do Not Disturb mode: $mode. Android may apply this as Kompi's own automatic rule.") { context, _ ->
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.isNotificationPolicyAccessGranted) nativeError("special_access_required", "Allow Do Not Disturb access for Kompi in Phone access settings.")
                else {
                    manager.setInterruptionFilter(when (mode) { "off" -> NotificationManager.INTERRUPTION_FILTER_ALL; "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY; "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS; else -> NotificationManager.INTERRUPTION_FILTER_NONE })
                    performed("Do Not Disturb request submitted. Other rules may still apply.").put("observedFilter", manager.currentInterruptionFilter)
                }
            }
        }
        "get_location" -> {
            NativeArguments(input)
            NativeTask(name, "Obtain and share the phone's current location, accuracy and timestamp. This is one request, not background tracking.") { context, _ ->
                if (!hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) && !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) missingPermission("Location")
                else {
                    val manager = context.getSystemService(LocationManager::class.java)
                    val provider = when {
                        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        else -> null
                    }
                    if (provider == null) nativeError("location_disabled", "Enable location in Android settings.")
                    else {
                        val location = withTimeoutOrNull(12_000) { locate(manager, provider, context) }
                        if (location == null) nativeError("location_unavailable", "No current location was returned within 12 seconds.")
                        else if (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 120_000_000_000L) nativeError("stale_location", "Android returned an old location. Try outdoors or check location settings.")
                        else JSONObject().put("status", "read").put("latitude", location.latitude).put("longitude", location.longitude)
                            .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL).put("timestamp", location.time)
                            .put("approximatePermission", !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            }
        }
        else -> null
    }
    private suspend fun locate(manager: LocationManager, provider: String, context: Context): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { if (continuation.isActive) continuation.resume(it) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
                override fun onProviderDisabled(provider: String) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(null) }
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Legacy location callback") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            // Cancellation can win before registration; revoke a just-registered legacy listener too.
            if (!continuation.isActive) manager.removeUpdates(listener)
        }
    }
}
