package com.kompi.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real virtual wired output with the emulator's built-in mic, not a mocked AudioManager/route.
 * Ranchu rejects a wired microphone port; this fixture does not pretend otherwise. */
internal class EmulatedWiredHeadset(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    init { check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Virtual routing fixture is restricted to an Android emulator" } }
    private fun stateMatches(connected: Boolean): Boolean {
        val devices = manager.getDevices(AudioManager.GET_DEVICES_ALL).filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        return if (connected) devices.any { it.isSink } else devices.none { it.isSink }
    }
    private fun change(connected: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_ROUTING")
        try {
            val method = AudioManager::class.java.getMethod("setWiredDeviceConnectionState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java)
            method.invoke(manager, 4, if (connected) 1 else 0, "", "Kompi Virtual Headset")
        } finally { automation.dropShellPermissionIdentity() }
        val changed = CountDownLatch(1)
        val callback = object : AudioDeviceCallback() {
            private fun checkState() { if (stateMatches(connected)) changed.countDown() }
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkState()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkState()
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        try {
            if (stateMatches(connected)) changed.countDown()
            check(changed.await(5, TimeUnit.SECONDS)) { "Virtual headset ports did not ${if (connected) "connect" else "disconnect"}; configure this private AVD's audio policy first" }
        } finally { manager.unregisterAudioDeviceCallback(callback) }
    }
    fun connect() = change(true)
    fun disconnect() = change(false)
    override fun close() { runCatching { disconnect() } }
}
