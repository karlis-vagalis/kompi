package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.presetN0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device test for loading the official 1.2.0 Android FFI AAR and JNI key/endpoint APIs. */
@RunWith(AndroidJUnit4::class)
class IrohNativeSmokeTest {
    @Test fun bundledNativeLibraryBindsEndpointWithPersistentKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IrohAndroid.installAndroidContext(context)
        val secret = ByteArray(32) { (it + 1).toByte() }
        val endpoint = kotlinx.coroutines.runBlocking {
            Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = secret, alpns = listOf("pi-voice/1".toByteArray())))
        }
        try {
            assertEquals(secret.toList(), endpoint.secretKey().toBytes().toList())
            assertEquals(32, endpoint.id().toBytes().size)
            assertTrue(endpoint.boundSockets().isNotEmpty())
        } finally { kotlinx.coroutines.runBlocking { endpoint.shutdown() } }
    }
}
