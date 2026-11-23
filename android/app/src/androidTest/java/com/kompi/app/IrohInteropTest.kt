package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import com.kompi.app.protocol.generated.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Native QUIC + current generated framing integration; no daemon/model or external server. */
@RunWith(AndroidJUnit4::class)
class IrohInteropTest {
    @Test fun c2_r1_nativeStreamPreservesRequestIdentityAndUnicodeAcrossRetransmission() = runBlocking {
        withTimeout(30_000) {
            IrohAndroid.installAndroidContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val alpn = ALPN.toByteArray()
            val server = Endpoint.bind(EndpointOptions(bindAddr = "127.0.0.1:0", alpns = listOf(alpn)))
            val client = Endpoint.bind(EndpointOptions(bindAddr = "127.0.0.1:0", alpns = listOf(alpn)))
            try {
                val message = EnvelopeV3(id = "same-request", message = WireMessage.Prompt(
                    configuration = ChatModelConfiguration("high", "test-model", "test-provider"),
                    delivery = "followUp", text = "First line\nLatviešu teksts 🎧",
                ))
                val received = async {
                    val connection = server.acceptNext()!!.accept().connect()
                    assertEquals(client.id().toBytes().toList(), connection.remoteId().toBytes().toList())
                    val stream = connection.acceptBi()
                    val recv = stream.recv()
                    val messages = (0..1).map {
                        val size = ByteBuffer.wrap(recv.readExact(4u)).int
                        require(size in 1..MAX_PAYLOAD)
                        WireCodec.decode(recv.readExact(size.toUInt()))
                    }
                    stream.send().writeAll(FrameCodec.frame(WireCodec.encode(EnvelopeV3(id = "received", message = WireMessage.Pong))))
                    messages
                }
                val address = EndpointAddr(server.id(), null, server.boundSockets())
                val connection = client.connect(address, alpn)
                assertEquals(server.id().toBytes().toList(), connection.remoteId().toBytes().toList())
                val stream = connection.openBi()
                repeat(2) { stream.send().writeAll(FrameCodec.frame(WireCodec.encode(message))) }
                val responseSize = ByteBuffer.wrap(stream.recv().readExact(4u)).int
                assertEquals(WireMessage.Pong, WireCodec.decode(stream.recv().readExact(responseSize.toUInt())).message)
                assertEquals(listOf(message, message), received.await())
            } finally {
                client.shutdown()
                server.shutdown()
            }
        }
    }
}
