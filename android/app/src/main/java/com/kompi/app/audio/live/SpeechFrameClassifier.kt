package com.kompi.app.audio.live

import android.content.Context
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/** One bounded classifier result for one complete cadence-sized frame. */
internal const val SILERO_SPEECH_THRESHOLD = 0.5f

internal data class SpeechFrameDecision(
    val speech: Boolean,
    /** Bounded raw model output when the selected classifier has a score; never calibrated confidence. */
    val modelScore: SpeechModelScore?,
) {
    companion object {
        fun categorical(speech: Boolean) = SpeechFrameDecision(speech, null)
        fun scored(score: SpeechModelScore, threshold: Float) = SpeechFrameDecision(score.value >= threshold, score)
    }
}

/** A finite Silero output value in its documented [0, 1] range, not speaker identity or certainty. */
@JvmInline
internal value class SpeechModelScore private constructor(val value: Float) {
    companion object {
        fun parse(value: Float): SpeechModelScore {
            require(value.isFinite() && value in 0f..1f) { "Speech model output is outside its finite [0, 1] range" }
            return SpeechModelScore(value)
        }
    }
}

/** Streaming classifier contract; implementations own one state stream and serialize classify/close. */
internal fun interface SpeechFrameClassifier : Closeable {
    val frameSamples: Int get() = SileroSpeechClassifier.SILERO_FRAME_SAMPLES
    fun classify(frame: FloatArray): SpeechFrameDecision
    override fun close() = Unit
}

/**
 * Pinned, offline Silero v6.2.3 model running on ONNX Runtime Android CPU. Each instance is owned
 * by one Live capture generation. Model bytes are verified before ORT sees them; state is never
 * shared across capture generations or written/logged/transmitted.
 */
internal class SileroSpeechClassifier(context: Context) : SpeechFrameClassifier {
    private lateinit var environment: OrtEnvironment // process-shared; never close it here
    private lateinit var session: OrtSession
    private lateinit var audioTensor: OnnxTensor
    private lateinit var stateTensor: OnnxTensor
    private lateinit var sampleRateTensor: OnnxTensor
    private lateinit var audioBuffer: FloatBuffer
    private lateinit var stateBuffer: FloatBuffer
    private lateinit var sampleRateBuffer: LongBuffer
    private lateinit var inputs: Map<String, OnnxTensor>
    private val contextSamples = FloatArray(CONTEXT_SAMPLES)
    private val recurrentState = FloatArray(STATE_VALUES)
    private var closed = false

    override val frameSamples: Int = SILERO_FRAME_SAMPLES

    init {
        try {
            val model = loadVerifiedModel(context.applicationContext)
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                session = environment.createSession(model, options)
            } finally {
                options.close()
            }
            validateModelContract(session)
            audioBuffer = directFloatBuffer(INPUT_SAMPLES)
            stateBuffer = directFloatBuffer(STATE_VALUES)
            sampleRateBuffer = ByteBuffer.allocateDirect(Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).asLongBuffer()
            audioTensor = OnnxTensor.createTensor(environment, audioBuffer, longArrayOf(1, INPUT_SAMPLES.toLong()))
            stateTensor = OnnxTensor.createTensor(environment, stateBuffer, longArrayOf(2, 1, STATE_WIDTH.toLong()))
            // The verified model input `sr` is a scalar int64 (rank zero), not shape [1].
            sampleRateTensor = OnnxTensor.createTensor(environment, sampleRateBuffer, longArrayOf())
            inputs = mapOf("input" to audioTensor, "state" to stateTensor, "sr" to sampleRateTensor)
            warmUpAndReset()
        } catch (error: Throwable) {
            close()
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            throw SpeechClassifierUnavailable(error)
        }
    }

    @Synchronized
    override fun classify(frame: FloatArray): SpeechFrameDecision {
        check(!closed) { "Local speech classifier is closed" }
        require(frame.size == SILERO_FRAME_SAMPLES) { "Silero needs one 32 ms, 16 kHz mono frame" }
        require(frame.all { it.isFinite() && it in -1f..1f }) { "Classifier PCM must be finite normalized audio" }
        return infer(frame)
    }

    /** Reset recurrent state only at stream boundaries; never between adjacent model frames. */
    @Synchronized
    fun resetStream() {
        check(!closed) { "Local speech classifier is closed" }
        contextSamples.fill(0f)
        recurrentState.fill(0f)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failures = 0
        fun release(action: () -> Unit) { if (runCatching(action).isFailure) failures++ }
        // Keep teardown nonthrowing here: decoder shutdown must still run if one ORT close fails.
        if (::sampleRateTensor.isInitialized) release { sampleRateTensor.close() }
        if (::stateTensor.isInitialized) release { stateTensor.close() }
        if (::audioTensor.isInitialized) release { audioTensor.close() }
        if (::session.isInitialized) release { session.close() }
        contextSamples.fill(0f)
        recurrentState.fill(0f)
        if (::audioBuffer.isInitialized) { audioBuffer.clear(); while (audioBuffer.hasRemaining()) audioBuffer.put(0f); audioBuffer.rewind() }
        if (::stateBuffer.isInitialized) { stateBuffer.clear(); while (stateBuffer.hasRemaining()) stateBuffer.put(0f); stateBuffer.rewind() }
        if (failures > 0) android.util.Log.w("KompiSpeechClassifier", "phase=close_failed resource_count=$failures")
    }

    private fun warmUpAndReset() {
        // Force native graph/session setup before capture starts, then discard all warm-up state.
        infer(FloatArray(SILERO_FRAME_SAMPLES))
        contextSamples.fill(0f)
        recurrentState.fill(0f)
    }

    private fun infer(frame: FloatArray): SpeechFrameDecision {
        audioBuffer.clear()
        contextSamples.forEach(audioBuffer::put)
        frame.forEach(audioBuffer::put)
        audioBuffer.rewind()
        stateBuffer.clear()
        recurrentState.forEach(stateBuffer::put)
        stateBuffer.rewind()
        sampleRateBuffer.clear()
        sampleRateBuffer.put(SAMPLE_RATE.toLong()).rewind()

        val result = session.run(inputs)
        val score: SpeechModelScore
        try {
            val output = (result.get("output").orElse(null) as? OnnxTensor)
                ?: error("Silero output tensor is missing")
            val outputBuffer = checkNotNull(output.floatBuffer) { "Silero output is not float32" }
            require(outputBuffer.remaining() == 1) { "Silero output must contain one score" }
            // Copy the score and recurrent state before closing the session result. Never retain ORT views.
            require(output.info.shape.contentEquals(longArrayOf(1, 1))) { "Silero score output has an unexpected shape" }
            score = SpeechModelScore.parse(outputBuffer.get())
            val nextState = (result.get("stateN").orElse(null) as? OnnxTensor)
                ?: error("Silero recurrent-state output is missing")
            val nextStateBuffer = checkNotNull(nextState.floatBuffer) { "Silero state output is not float32" }
            require(nextState.info.shape.contentEquals(longArrayOf(2, 1, STATE_WIDTH.toLong())) &&
                nextStateBuffer.remaining() == STATE_VALUES) { "Silero recurrent state has an unexpected shape" }
            nextStateBuffer.get(recurrentState)
            require(recurrentState.all { it.isFinite() }) { "Silero recurrent state contains a non-finite value" }
        } finally {
            result.close()
        }
        frame.copyInto(contextSamples, destinationOffset = 0, startIndex = frame.size - CONTEXT_SAMPLES)
        return SpeechFrameDecision.scored(score, SILERO_SPEECH_THRESHOLD)
    }

    private fun validateModelContract(model: OrtSession) {
        require(model.inputNames == setOf("input", "state", "sr")) { "Bundled Silero model inputs do not match the pinned contract" }
        require(model.outputNames == setOf("output", "stateN")) { "Bundled Silero model outputs do not match the pinned contract" }
        val audio = model.inputTensorInfo("input")
        require(audio.type == OnnxJavaType.FLOAT && audio.shape.size == 2 && audio.shape[0] in setOf(-1L, 1L) &&
            audio.shape[1] in setOf(-1L, INPUT_SAMPLES.toLong())) {
            "Bundled Silero audio input contract is incompatible"
        }
        val state = model.inputTensorInfo("state")
        require(state.type == OnnxJavaType.FLOAT && state.shape.size == 3 && state.shape[0] == 2L &&
            state.shape[1] in setOf(-1L, 1L) && state.shape[2] == STATE_WIDTH.toLong()) {
            "Bundled Silero recurrent-state input contract is incompatible"
        }
        val sampleRate = model.inputTensorInfo("sr")
        require(sampleRate.type == OnnxJavaType.INT64 && sampleRate.shape.isEmpty()) {
            "Bundled Silero sample-rate input must be a scalar int64"
        }
        val output = model.outputTensorInfo("output")
        require(output.type == OnnxJavaType.FLOAT && output.shape.size == 2 && output.shape[0] in setOf(-1L, 1L) &&
            output.shape.last() == 1L) {
            "Bundled Silero score output contract is incompatible"
        }
        val nextState = model.outputTensorInfo("stateN")
        // The pinned upstream graph declares all stateN dimensions symbolic. Validate the declared
        // rank/type and reject any known contradictory dimensions; infer() below still requires
        // the concrete runtime shape [2, 1, 128] and finite copied state values on every frame.
        require(nextState.type == OnnxJavaType.FLOAT && nextState.shape.size == 3 &&
            nextState.shape[0] in setOf(-1L, 2L) &&
            nextState.shape[1] in setOf(-1L, 1L) &&
            nextState.shape[2] in setOf(-1L, STATE_WIDTH.toLong())) {
            "Bundled Silero recurrent-state output contract is incompatible"
        }
    }

    private fun OrtSession.inputTensorInfo(name: String): TensorInfo =
        (inputInfo.getValue(name).info as? TensorInfo) ?: error("Silero input $name is not a tensor")

    private fun OrtSession.outputTensorInfo(name: String): TensorInfo =
        (outputInfo.getValue(name).info as? TensorInfo) ?: error("Silero output $name is not a tensor")

    private fun loadVerifiedModel(context: Context): ByteArray {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        require(bytes.size == MODEL_SIZE_BYTES && sha256(bytes) == MODEL_SHA256) {
            "Bundled Silero model failed its pinned size/SHA-256 check"
        }
        return bytes
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        const val SILERO_FRAME_SAMPLES = 512 // 32 ms at 16 kHz, per the pinned streaming wrapper
        private const val SAMPLE_RATE = 16_000
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_WIDTH = 128
        private const val STATE_VALUES = 2 * STATE_WIDTH
        private const val INPUT_SAMPLES = CONTEXT_SAMPLES + SILERO_FRAME_SAMPLES
        private const val MODEL_ASSET = "vad/silero-v6.2.3.onnx"
        private const val MODEL_SIZE_BYTES = 2_327_524
        private const val MODEL_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    }
}

/** Safe product-facing category; keep provider diagnostics out of UI/capture messages. */
internal class SpeechClassifierUnavailable(cause: Throwable) :
    IllegalStateException("The bundled local speech classifier could not start", cause)
