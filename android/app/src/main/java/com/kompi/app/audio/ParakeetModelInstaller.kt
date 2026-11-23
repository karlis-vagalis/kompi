package com.kompi.app.audio

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class InstalledParakeetModel internal constructor(val file: File)

/** Explicit, revision-pinned install of Google's recommended CPU-compatible Parakeet bundle. */
class ParakeetModelInstaller(private val directory: File) {
    /** Call on IO: file identity/integrity is established before handing a model to native code. */
    fun installed(): InstalledParakeetModel? {
        val file = File(directory, MODEL_NAME)
        if (!file.isFile || file.length() != MODEL_SIZE) return null
        return file.takeIf { digest(it) == MODEL_SHA256 }?.let(::InstalledParakeetModel)
    }

    suspend fun download(onProgress: (Float) -> Unit): Result<InstalledParakeetModel> = withContext(Dispatchers.IO) {
        val temporary = File(directory, ".$MODEL_NAME.part")
        try {
            if (!directory.exists()) check(directory.mkdirs()) { "Could not create Parakeet model directory" }
            check(directory.usableSpace >= MODEL_SIZE + 64L * 1024 * 1024) { "Free at least 680 MB to download Parakeet" }
            check(!temporary.exists() || temporary.delete()) { "Could not remove the previous partial Parakeet download" }
            android.util.Log.i("KompiParakeet", "phase=model_download_started bytes=$MODEL_SIZE")
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Kompi-Parakeet/1")
            try {
                connection.connect()
                check(connection.responseCode == 200) { "Parakeet download failed (HTTP ${connection.responseCode})" }
                if (connection.contentLengthLong >= 0) check(connection.contentLengthLong == MODEL_SIZE) { "Unexpected Parakeet model size" }
                val checksum = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgressNanos = 0L
                onProgress(0f)
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            received += count
                            check(received <= MODEL_SIZE) { "Parakeet download exceeds the pinned size" }
                            checksum.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgressNanos >= 250_000_000) {
                                lastProgressNanos = now
                                onProgress(received.toFloat() / MODEL_SIZE)
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(received == MODEL_SIZE) { "Parakeet download is incomplete" }
                check(hex(checksum.digest()) == MODEL_SHA256) { "Parakeet model checksum failed" }
            } finally { connection.disconnect() }
            currentCoroutineContext().ensureActive()
            check(temporary.setReadable(false, false) && temporary.setReadable(true, true)) { "Could not secure Parakeet model permissions" }
            check(temporary.setWritable(false, false)) { "Could not make Parakeet model read-only" }
            val destination = File(directory, MODEL_NAME)
            check(temporary.renameTo(destination)) { "Could not install the verified Parakeet model" }
            onProgress(1f)
            android.util.Log.i("KompiParakeet", "phase=model_installed bytes=$MODEL_SIZE")
            Result.success(InstalledParakeetModel(destination))
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (error: Exception) {
            temporary.delete()
            Result.failure(error)
        }
    }

    companion object {
        const val MODEL_NAME = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
        const val MODEL_SIZE = 614_588_752L
        const val MODEL_SHA256 = "66c5a74cf6c2c1d2b990901f6cece5bb53e3214dbceafa0989900a6f0eeb3504"
        const val REVISION = "1f78e5eb6175b1b2cce35f16bcf9e2027b83e8bd"
        const val MODEL_URL = "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/$REVISION/$MODEL_NAME"

        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hex(hash.digest())
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
