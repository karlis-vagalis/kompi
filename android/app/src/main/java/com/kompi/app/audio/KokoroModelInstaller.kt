package com.kompi.app.audio

import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Explicit, integrity-checked Kokoro model installer.
 *
 * No download occurs during construction, app startup, or synthesis. The only accepted files are
 * pinned LiteRT-LM model bundles served from the pinned Hugging Face repository revision.
 */
internal class KokoroModelInstaller(private val modelDirectory: File) {
    data class ModelAsset(
        val filename: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val label: String,
        val voice: String,
        val delegate: EngineDelegate,
    )

    enum class EngineDelegate { CPU, GPU }

    /** Variant picked by the device ABI, not by a guess that an emulator has a GPU delegate. */
    fun selectedAsset(): ModelAsset =
        if (Build.SUPPORTED_ABIS.any { it == "x86_64" }) CPU_ASSET else GPU_ASSET

    fun installed(asset: ModelAsset = selectedAsset()): File? =
        File(modelDirectory, asset.filename).takeIf { it.isFile && it.length() == asset.sizeBytes }

    suspend fun download(
        onProgress: (received: Long, total: Long) -> Unit,
        asset: ModelAsset = selectedAsset(),
    ): File = withContext(Dispatchers.IO) {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) error("Cannot create private Kokoro model directory")
        require(modelDirectory.isDirectory) { "Kokoro model path is not a directory" }

        val destination = File(modelDirectory, asset.filename)
        if (destination.isFile && destination.length() == asset.sizeBytes && sha256(destination) == asset.sha256) {
            onProgress(asset.sizeBytes, asset.sizeBytes)
            return@withContext destination
        }
        val temporary = File(modelDirectory, ".${asset.filename}.part")
        temporary.delete()
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kompi-Android-Kokoro/1")
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Kokoro model download failed (HTTP ${connection.responseCode})" }
            require(connection.contentLengthLong == asset.sizeBytes) { "Unexpected model size; refusing unpinned asset" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= asset.sizeBytes) { "Downloaded model exceeds pinned size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(total, asset.sizeBytes)
                    }
                    output.fd.sync()
                }
            }
            require(total == asset.sizeBytes) { "Downloaded model is incomplete" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual == asset.sha256) { "Kokoro model SHA-256 did not match the pinned release" }
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // HF model repo revision d59e0ad77eca53c26b9e493ddec7de7af481952c.
        // LFS SHA-256 values and sizes are from the official repository's tree API.
        private const val REVISION = "d59e0ad77eca53c26b9e493ddec7de7af481952c"
        private const val BASE_URL = "https://huggingface.co/litert-community/Kokoro-82M/resolve/$REVISION/"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000

        val CPU_ASSET = ModelAsset(
            filename = "kokoro_int8_fp16.litertlm",
            url = BASE_URL + "kokoro_int8_fp16.litertlm",
            sha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
            sizeBytes = 294_783_298L,
            label = "Kokoro-82M · CPU",
            voice = "af_heart",
            delegate = EngineDelegate.CPU,
        )
        val GPU_ASSET = ModelAsset(
            filename = "kokoro_int8_gpu_fp16.litertlm",
            url = BASE_URL + "kokoro_int8_gpu_fp16.litertlm",
            sha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            sizeBytes = 294_750_530L,
            label = "Kokoro-82M · GPU",
            voice = "af_heart",
            delegate = EngineDelegate.GPU,
        )

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
