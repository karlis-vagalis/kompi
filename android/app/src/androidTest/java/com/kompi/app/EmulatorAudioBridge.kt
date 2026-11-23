package com.kompi.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.net.Socket

/** Test-only authenticated localhost bridge to the selected emulator's SDK virtual audio device. */
internal class EmulatorAudioBridge : AutoCloseable {
    private val arguments = InstrumentationRegistry.getArguments()
    private val port = arguments.getString("audioBridgePort")?.toIntOrNull()
        ?.takeIf { it in 1..65535 } ?: error("An authenticated emulator audio bridge is required")
    private val token = arguments.getString("audioBridgeToken")?.takeIf { it.length == 64 }
        ?: error("Audio bridge token missing")
    private val inputBackend = when (val value = arguments.getString("audioInputBackend")) {
        "sdk", "pulse" -> value
        else -> error("An explicit audioInputBackend is required for virtual audio acceptance")
    }
    private data class InputPortIdentity(val backend: String, val epoch: Long, val writerPid: Long, val sinkInput: Long)
    private var inputPortIdentity: InputPortIdentity? = null
    private var capturing = false
    private var echoing = false

    /** Host stream ownership only; successful reads/ASR remain separate Android assertions. */
    private fun verifyInputPort(response: JSONObject) {
        check(response.getString("inputBackend") == inputBackend) { "Audio bridge input backend changed" }
        if (inputBackend == "sdk") {
            check(!response.has("inputPortEpoch") && !response.has("inputWriterPid") &&
                !response.has("inputPulseSinkInputIndex")) { "SDK input cannot claim a private Pulse stream" }
            return
        }
        val identity = InputPortIdentity(inputBackend, response.getLong("inputPortEpoch"),
            response.getLong("inputWriterPid"), response.getLong("inputPulseSinkInputIndex"))
        check(identity.epoch > 0 && identity.writerPid > 0 && identity.sinkInput >= 0 &&
            response.getLong("inputPortIdentityVerifiedHostNs") > 0 &&
            response.getString("inputPortBoundary") == "actual-private-PA-stream-identity-not-Android-delivery") {
            "Private input port lacks verified native-stream identity"
        }
        val previous = inputPortIdentity
        check(previous == null || identity == previous) { "Private input stream was replaced between audio phases" }
        inputPortIdentity = identity
    }
    fun call(action: String, fixture: String? = null): JSONObject {
        val request = JSONObject().put("token", token).put("action", action)
        fixture?.let { request.put("fixture", it) }
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 130_000
            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine() ?: error("Emulator audio bridge closed")
            val response = JSONObject(line)
            check(response.getBoolean("ok")) { "Emulator audio bridge failed: ${response.optString("error")}" }
            if (action in setOf("inject", "echo-start", "echo-near-end", "echo-stop")) verifyInputPort(response)
            response
        }
    }
    fun inject(fixture: String) = call("inject", fixture)
    fun checkpoint(label: String) { call("checkpoint", label) }
    fun incomingCall() { call("call-start") }
    fun endCall() { call("call-end") }
    fun startCapture() { call("capture-start"); capturing = true }
    fun quietOutputWindow(): JSONObject = call("capture-window")
    fun stopCapture(): JSONObject { try { return call("capture-stop") } finally { capturing = false } }
    fun startEcho() { call("echo-start"); echoing = true }
    fun mixNearEndFixture(name: String): JSONObject = call("echo-near-end", name)
    fun stopEcho(): JSONObject { try { return call("echo-stop") } finally { echoing = false } }
    override fun close() {
        if (echoing) runCatching { stopEcho() }
        if (capturing) runCatching { stopCapture() }
    }
}
