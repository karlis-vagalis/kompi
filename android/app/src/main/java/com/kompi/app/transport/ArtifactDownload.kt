package com.kompi.app.transport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kompi.app.protocol.generated.ATTACHMENT_CHUNK_MAX_BYTES
import com.kompi.app.protocol.generated.EnvelopeV3
import com.kompi.app.protocol.generated.FrameCodec
import com.kompi.app.protocol.generated.MAX_PAYLOAD
import com.kompi.app.protocol.generated.OfferedArtifact
import com.kompi.app.protocol.generated.WireCodec
import com.kompi.app.protocol.generated.WireMessage
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class ArtifactDownloadTarget(val peer: String, val conversation: String, val offer: OfferedArtifact)
internal class ArtifactSaveException(message: String) : Exception(message)

/** Fetch exactly one offered snapshot over the current authenticated Iroh connection. */
internal suspend fun readOfferedArtifact(
    connection: Connection,
    target: ArtifactDownloadTarget,
    requestId: String,
    isCurrent: () -> Boolean,
): ByteArray = try {
    withTimeoutOrNull(125_000) {
        val request = WireMessage.ArtifactGet(artifactId = target.offer.artifactId, conversationId = target.conversation)
        check(isCurrent())
        connection.openBi().use { stream ->
            stream.send().use { send ->
                send.setPriority(0)
                check(isCurrent())
                send.writeAll(FrameCodec.frame(WireCodec.encode(EnvelopeV3(id = requestId, message = request))))
                send.finish()
            }
            stream.recv().use { recv ->
                suspend fun receive(): WireMessage {
                    val size = ByteBuffer.wrap(recv.readExact(4u)).int
                    check(size in 1..MAX_PAYLOAD)
                    val envelope = WireCodec.decode(recv.readExact(size.toUInt()))
                    check(isCurrent() && envelope.id == requestId)
                    return envelope.message
                }
                when (val ready = receive()) {
                    is WireMessage.ArtifactReady -> check(ready.artifact == target.offer)
                    is WireMessage.Error -> throw ArtifactSaveException(when (ready.code) {
                        "artifact-unavailable" -> "File offer unavailable. Ask for a new offer."
                        "stream-overloaded" -> "The computer is busy. Try downloading again."
                        else -> "Download failed. Try again."
                    })
                    else -> error("Expected artifact metadata")
                }
                val output = ByteArray(target.offer.length.toInt())
                val sha256 = MessageDigest.getInstance("SHA-256")
                var offset = 0
                while (offset < output.size) {
                    val chunk = (receive() as? WireMessage.ArtifactData)?.data ?: error("Expected file bytes")
                    check(chunk.size in 1..minOf(ATTACHMENT_CHUNK_MAX_BYTES, output.size - offset))
                    chunk.copyInto(output, destinationOffset = offset)
                    sha256.update(chunk)
                    offset += chunk.size
                }
                check(receive() == WireMessage.ArtifactEnd)
                val actual = sha256.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (actual != target.offer.sha256) throw ArtifactSaveException("The file didn’t pass verification. Try downloading again.")
                output
            }
        }
    } ?: throw ArtifactSaveException("The download timed out. Try again.")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ArtifactSaveException) {
    throw failure
} catch (_: Exception) {
    throw ArtifactSaveException("Download interrupted. Reconnect to the computer and try again.")
}

/** A picker-created document is opened for writing only after the complete download verifies. */
internal suspend fun saveOfferedArtifact(context: Context, destination: Uri, fetch: suspend () -> ByteArray) {
    if (destination.scheme != "content") throw ArtifactSaveException("Choose a destination in the system file picker.")
    val resolver = context.contentResolver
    var saved = false
    try {
        withContext(Dispatchers.IO) {
            val bytes = fetch()
            try {
                resolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
                    ?: error("Destination is not writable")
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw ArtifactSaveException("Couldn’t save the file. Try a different destination.")
            }
        }
    } finally {
        if (!saved) withContext(NonCancellable + Dispatchers.IO) {
            // CREATE_DOCUMENT makes a fresh document; some providers refuse deletion.
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
        }
    }
}
