package com.kompi.app.audio

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class InstalledWhistleModel internal constructor(val file: File)

sealed interface WhistleInstallResult {
    data class Installed(val model: InstalledWhistleModel) : WhistleInstallResult
    data class Rejected(val reason: String) : WhistleInstallResult
}

/** Explicit, pinned, single-file installer. It never runs as a transcription fallback. */
class WhistleModelInstaller(private val modelRoot: File) {
    /** Cheap startup probe only; the full checksum is rechecked on the inference worker before native load. */
    fun installed(): InstalledWhistleModel? = File(modelRoot, MODEL_NAME)
        .takeIf { it.isFile && it.length() == MODEL_SIZE }
        ?.let(::InstalledWhistleModel)

    fun download(): WhistleInstallResult {
        val destination = File(modelRoot, MODEL_NAME)
        val temporary = File(modelRoot, ".$MODEL_NAME.part")
        return try {
            if (!modelRoot.exists() && !modelRoot.mkdirs()) error("Could not create private model directory")
            require(modelRoot.isDirectory) { "Model path is not a directory" }
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Kompi-Whistle/1")
            try {
                connection.connect()
                require(connection.responseCode in 200..299) { "Model download failed (HTTP ${connection.responseCode})" }
                require(connection.contentLengthLong == MODEL_SIZE) { "Unexpected model size" }
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count: Int
                        var total = 0L
                        val digest = MessageDigest.getInstance("SHA-256")
                        while (input.read(buffer).also { count = it } >= 0) {
                            if (count == 0) continue
                            total += count
                            require(total <= MODEL_SIZE) { "Downloaded model exceeds expected size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        require(total == MODEL_SIZE) { "Downloaded model is incomplete" }
                        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        require(actual == MODEL_SHA256) { "Model SHA-256 did not match the pinned release" }
                    }
                }
            } finally {
                connection.disconnect()
            }
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            if (!temporary.renameTo(destination)) error("Could not atomically install the model")
            WhistleInstallResult.Installed(InstalledWhistleModel(destination))
        } catch (error: Exception) {
            temporary.delete()
            WhistleInstallResult.Rejected(error.message ?: "Whistle download failed")
        }
    }

    companion object {
        const val MODEL_NAME = "whistle.cact"
        const val MODEL_SIZE = 16_919_407L
        const val MODEL_SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
        const val MODEL_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/741118687e1f008f06d1351a402a731274789790/whistle.cact"

        fun isVerifiedModel(file: File): Boolean = file.isFile && file.length() == MODEL_SIZE && sha256(file) == MODEL_SHA256

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
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
