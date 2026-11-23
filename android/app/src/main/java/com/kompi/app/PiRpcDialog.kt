package com.kompi.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.kompi.app.protocol.generated.*
import com.kompi.app.transport.PiDialogRequest
import com.kompi.app.transport.PiDialogText
import com.kompi.app.ui.KompiAlertDialog as AlertDialog
import com.kompi.app.ui.KompiOutlinedTextField as OutlinedTextField
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiTextButton as TextButton
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.launch

private sealed interface DialogSubmission {
    data object Editing : DialogSubmission
    data object Sending : DialogSubmission
    data class Failed(val message: String) : DialogSubmission
}

/** Pi's supported RPC dialogs, without approving or submitting anything on the user's behalf. */
@Composable
internal fun PiRpcDialog(request: PiDialogRequest, remoteError: String?, onAnswer: suspend (PiUiAnswer) -> Unit) {
    var submission by remember(request.interactionId) { mutableStateOf<DialogSubmission>(DialogSubmission.Editing) }
    var input by remember(request.interactionId) { mutableStateOf((request.question as? PiUiDialog.Editor)?.payload?.prefill.orEmpty()) }
    val parsedInput = remember(input) { runCatching { PiDialogText.parse(input) } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(remoteError) { if (remoteError != null) submission = DialogSubmission.Failed(remoteError) }
    val sending = submission == DialogSubmission.Sending
    fun submit(answer: PiUiAnswer) {
        if (sending) return
        submission = DialogSubmission.Sending
        scope.launch {
            try { onAnswer(answer) }
            catch (_: Exception) { submission = DialogSubmission.Failed("Could not send the answer. Reconnect to the computer and try again.") }
        }
    }
    fun cancel() = submit(PiUiAnswer.Cancelled(PiUiAnswerCancelledPayload()))
    val bodyHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    AlertDialog(
        onDismissRequest = { if (!sending) cancel() },
        title = { Text("Pi needs your input") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = bodyHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.title, style = KompiUi.typography.titleMedium)
                when (val question = request.question) {
                    is PiUiDialog.Select -> question.payload.options.forEachIndexed { index, option ->
                        TextButton(onClick = { submit(PiUiAnswer.Selected(PiUiAnswerSelectedPayload(index))) }, enabled = !sending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(option.ifBlank { "Option ${index + 1}" })
                        }
                    }
                    is PiUiDialog.Confirm -> question.payload.message?.takeIf(String::isNotBlank)?.let { Text(it) }
                    is PiUiDialog.Input -> OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(question.payload.placeholder.orEmpty()) },
                        minLines = 1, maxLines = 4, isError = parsedInput.isFailure,
                    )
                    is PiUiDialog.Editor -> OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                        minLines = 3, maxLines = 8, isError = parsedInput.isFailure,
                    )
                }
                if (parsedInput.isFailure) Text("Answer must be at most 16,000 characters.", color = KompiUi.colors.error)
                (submission as? DialogSubmission.Failed)?.let { Text(it.message, color = KompiUi.colors.error) }
                if (sending) Text("Sending answer…", color = KompiUi.colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            when (request.question) {
                is PiUiDialog.Confirm -> TextButton(onClick = { submit(PiUiAnswer.Confirmed(PiUiAnswerConfirmedPayload(true))) }, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yes") }
                is PiUiDialog.Input, is PiUiDialog.Editor -> TextButton(
                    onClick = { parsedInput.getOrNull()?.let { submit(PiUiAnswer.Text(PiUiAnswerTextPayload(it.value))) } },
                    enabled = !sending && parsedInput.isSuccess, modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Send") }
                is PiUiDialog.Select -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (request.question is PiUiDialog.Confirm) submit(PiUiAnswer.Confirmed(PiUiAnswerConfirmedPayload(false))) else cancel()
            }, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (request.question is PiUiDialog.Confirm) "No" else "Cancel") }
        },
    )
}
