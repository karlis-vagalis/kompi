package com.kompi.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import com.kompi.app.ui.KompiAlertDialog as AlertDialog
import com.kompi.app.ui.KompiButton as Button
import com.kompi.app.ui.KompiOutlinedTextField as OutlinedTextField
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiTextButton as TextButton
import com.kompi.app.ui.KompiUi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.kompi.app.protocol.generated.PAIRING_TOKEN_PREFIX
import com.kompi.app.protocol.generated.PAIRING_VERSION
import com.kompi.app.security.SecureStore
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairScannerDialog(onDismiss: () -> Unit, onPairing: (SecureStore.Pairing) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("Center the peer's Kompi QR code in the frame.") }
    var found by remember { mutableStateOf<SecureStore.Pairing?>(null) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair a peer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (found == null && cameraGranted) QrCameraPreview(Modifier.fillMaxWidth().height(260.dp).clip(KompiUi.shapes.medium), onQr = { raw ->
                    val parsed = runCatching { parsePairingQr(raw) }
                    parsed.onSuccess { found = it; message = "Pair with ${it.name}?" }
                        .onFailure { message = it.message ?: "This QR code isn't a supported Kompi pairing code." }
                    parsed.isSuccess
                }, onError = { message = it })
                if (!cameraGranted) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow camera to scan") }
                Text(message, style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
            }
        },
        confirmButton = { if (found != null) TextButton(onClick = { found?.let(onPairing) }, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-pairing")) { Text("Confirm pairing") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }
    )
}

internal fun parsePairingQr(raw: String): SecureStore.Pairing = parsePairingInput(raw)

internal fun parsePairingInput(input: String): SecureStore.Pairing {
    require(input.length <= 8_192) { "Pairing code is too large." }
    val source = input.trim()
    val jsonText = if (source.startsWith(PAIRING_TOKEN_PREFIX)) {
        val payload = source.removePrefix(PAIRING_TOKEN_PREFIX).filterNot(Char::isWhitespace)
        require(payload.isNotEmpty() && payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '=' }) { "Invalid pairing token encoding." }
        val bytes = try { java.util.Base64.getUrlDecoder().decode(payload) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid pairing token encoding.") }
        require(bytes.size <= 8_192) { "Pairing code is too large." }
        try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Pairing token is not valid UTF-8.")
        }
    } else input
    require(jsonText.length <= 8_192) { "Pairing code is too large." }
    val json = JSONObject(jsonText)
    val keys = json.keys().asSequence().toSet()
    require(keys == setOf("v", "endpointTicket", "secret", "name")) { "Unexpected pairing QR fields." }
    require((json.opt("v") as? Number)?.toDouble() == PAIRING_VERSION.toDouble()) { "Unsupported pairing QR version." }
    require(json.opt("endpointTicket") is String && json.opt("secret") is String && json.opt("name") is String) { "Invalid pairing QR fields." }
    val ticket = json.getString("endpointTicket")
    val secret = json.getString("secret")
    val name = json.getString("name").trim()
    require(ticket.isNotBlank() && ticket.length <= 2048 && ticket.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }) { "Invalid Iroh endpoint ticket." }
    require(secret.length in 43..256 && secret.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid pairing secret." }
    val decoded = try { java.util.Base64.getUrlDecoder().decode(secret) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid pairing secret.") }
    require(decoded.size == 32) { "Pairing secret must contain 32 random bytes." }
    require(name.length in 1..128) { "Peer name must be 1–128 characters." }
    return SecureStore.Pairing(ticket, secret, name)
}


@Composable
fun PairingTokenDialog(onDismiss: () -> Unit, onPairing: (SecureStore.Pairing) -> Unit) {
    val input = rememberTextFieldState()
    val result = remember(input.text.toString()) { runCatching { parsePairingInput(input.text.toString()) } }
    val parsed = result.getOrNull()
    val error = if (input.text.isBlank() || parsed != null) "" else result.exceptionOrNull()?.message ?: "Invalid pairing code."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste pairing code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste the Kompi pairing token or QR JSON. The computer is added only after it accepts the connection.")
                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().testTag("pairing-token-input"),
                    label = { Text("Pairing code") },
                    placeholder = { Text("${PAIRING_TOKEN_PREFIX}…") },
                    minLines = 3,
                    maxLines = 5,
                    isError = error.isNotEmpty(),
                )
                if (error.isNotEmpty()) Text(error, color = KompiUi.colors.error)
                parsed?.let { Text("Review peer: ${it.name}", style = KompiUi.typography.titleMedium) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onPairing) },
                enabled = parsed != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-pairing"),
            ) { Text("Confirm pairing") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** A code is not success: keep the attempt visible until the authenticated Paired reply. */
@Composable
internal fun PairingConnectionDialog(name: String, failure: String?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (failure == null) "Connecting to $name" else "Could not pair with $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(failure ?: "Waiting for the computer to confirm pairing…", modifier = Modifier.testTag("pairing-connection-status"))
                Text("This computer is not added until it confirms the connection.", style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (failure != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("retry-pairing")) { Text("Try again") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-pairing")) { Text("Cancel") } },
    )
}

@Composable
private fun QrCameraPreview(modifier: Modifier, onQr: (String) -> Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val sent = remember { AtomicBoolean(false) }
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val scanner = remember { QRCodeReader() }
    AndroidView(modifier = modifier, factory = { ctx ->
        val view = PreviewView(ctx)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                providerRef.set(provider)
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        if (!sent.get()) {
                            val plane = proxy.planes.first()
                            val width = proxy.width
                            val height = proxy.height
                            val pixels = ByteArray(width * height)
                            val buffer = plane.buffer.duplicate()
                            val start = buffer.position()
                            for (row in 0 until height) {
                                buffer.position(start + row * plane.rowStride)
                                buffer.get(pixels, row * width, width)
                            }
                            val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                            val value = scanner.decode(BinaryBitmap(HybridBinarizer(source))).text
                            if (sent.compareAndSet(false, true)) ContextCompat.getMainExecutor(ctx).execute { if (!onQr(value)) sent.set(false) }
                        }
                    } catch (_: ReaderException) {
                        // Most preview frames contain no readable QR code.
                    } finally {
                        scanner.reset()
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { error -> onError("Camera could not start: ${error.message ?: "permission or device unavailable"}") }
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
    DisposableEffect(Unit) { onDispose { providerRef.getAndSet(null)?.unbindAll(); executor.shutdown() } }
}
