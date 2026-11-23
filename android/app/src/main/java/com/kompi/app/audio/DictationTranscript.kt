package com.kompi.app.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.flow.collect

internal const val DICTATION_TRANSCRIPT_TEST_TAG = "dictation-transcript-preview"
internal const val DICTATION_TRANSCRIPT_TEXT_TEST_TAG = "dictation-transcript-text"

/** True only when an unfinished dictation cannot still change the message being sent. */
internal fun dictationSendAllowed(
    phase: OfflineSpeechSessionPhase, hasInterim: Boolean, hasPendingStart: Boolean = false,
): Boolean = phase == OfflineSpeechSessionPhase.Idle && !hasInterim && !hasPendingStart

/** Actual selected-engine partial, kept separate from the durable composer draft. */
@Composable
internal fun DictationTranscript(
    partial: OfflineTranscript,
    modifier: Modifier = Modifier,
    copyAvailable: Boolean = false,
    onCopy: () -> Unit = {},
    dismissEnabled: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(scrollState) {
        var previous = scrollState.value
        snapshotFlow { scrollState.value }.collect { value ->
            if (scrollState.isScrollInProgress && !autoScrolling && value != previous) {
                followTail = value >= scrollState.maxValue - 1
            }
            previous = value
        }
    }
    LaunchedEffect(partial.text, scrollState.maxValue) {
        if (followTail) {
            withFrameNanos { }
            autoScrolling = true
            try { scrollState.animateScrollTo(scrollState.maxValue) }
            finally { autoScrolling = false }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(scrollState)
                .testTag(DICTATION_TRANSCRIPT_TEST_TAG),
        ) {
            SelectionContainer {
                KompiText(
                    partial.text,
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(DICTATION_TRANSCRIPT_TEXT_TEST_TAG),
                    style = KompiUi.typography.bodySmall,
                    color = KompiUi.colors.onSurfaceVariant,
                )
            }
        }
        if (copyAvailable || dismissEnabled) Row(Modifier.fillMaxWidth()) {
            if (copyAvailable) KompiTextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                KompiText("Copy transcript")
            }
            if (dismissEnabled) KompiTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                KompiText("Dismiss transcript")
            }
        }
    }
}
