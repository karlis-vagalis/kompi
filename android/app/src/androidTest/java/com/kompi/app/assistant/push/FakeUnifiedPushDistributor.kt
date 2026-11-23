package com.kompi.app.assistant.push

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Target-UID helper for the test-APK-owned distributor's explicit staging/cleanup IPC. */
internal object FakeUnifiedPushDistributorControl {
    fun stage(context: Context, ciphertext: ByteArray, timeoutSeconds: Long = 5) {
        require(ciphertext.size in 1..4096) { "Ciphertext exceeds the test distributor size limit" }
        send(
            context,
            FakeUnifiedPushDistributor.ACTION_STAGE_PAYLOAD,
            ciphertext,
            FakeUnifiedPushDistributor.ACK_STAGED,
            timeoutSeconds,
        )
    }

    /** Dispatch stored bytes through the real SDK receiver; this ACK does not prove posting. */
    fun deliver(context: Context, timeoutSeconds: Long = 5) {
        send(
            context,
            FakeUnifiedPushDistributor.ACTION_DELIVER_STAGED,
            null,
            FakeUnifiedPushDistributor.ACK_DELIVERED,
            timeoutSeconds,
        )
    }

    fun clear(context: Context, timeoutSeconds: Long = 5) {
        send(
            context,
            FakeUnifiedPushDistributor.ACTION_CLEAR_STAGED,
            null,
            FakeUnifiedPushDistributor.ACK_CLEARED,
            timeoutSeconds,
        )
    }

    /** Receiver-owned observation of the unmodified SDK registration broadcast, with no secrets. */
    fun registrationReceived(context: Context, timeoutSeconds: Long = 5): Boolean = when (
        send(context, FakeUnifiedPushDistributor.ACTION_REGISTRATION_STATUS, null, null, timeoutSeconds)
    ) {
        FakeUnifiedPushDistributor.ACK_REGISTERED -> true
        FakeUnifiedPushDistributor.ACK_NOT_REGISTERED -> false
        else -> error("Test distributor returned an invalid registration observation")
    }

    private fun send(
        context: Context,
        action: String,
        ciphertext: ByteArray?,
        expectedAck: String?,
        timeoutSeconds: Long,
    ): String {
        require(timeoutSeconds in 1..30) { "IPC acknowledgement timeout is outside its bound" }
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val request = Intent(action)
            .setComponent(ComponentName(testPackage, FakeUnifiedPushDistributor::class.java.name))
            // Instrumentation executes in the target UID; installing the separate distributor APK
            // does not itself launch it. Explicit fixture control must reach a stopped test package.
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("application", context.packageName)
        ciphertext?.let { request.putExtra(FakeUnifiedPushDistributor.EXTRA_CIPHERTEXT, it) }
        val completed = CountDownLatch(1)
        var resultCode = Activity.RESULT_CANCELED
        var resultData: String? = null
        context.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                resultCode = getResultCode()
                resultData = getResultData()
                completed.countDown()
            }
        }, null, Activity.RESULT_CANCELED, null, null)
        check(completed.await(timeoutSeconds, TimeUnit.SECONDS)) { "Test distributor IPC acknowledgement timed out" }
        check(resultCode == Activity.RESULT_OK && (expectedAck == null || resultData == expectedAck)) {
            "Test distributor IPC rejected the control request"
        }
        return checkNotNull(resultData) { "Test distributor IPC omitted its acknowledgement" }
    }
}
