package com.kompi.app.audio

import android.content.Context
import com.kompi.app.audio.whistle.WhistleVocabulary

/** Both explicit local engines expose the same true streaming snapshot boundary to dictation. */
internal fun SpeechRecognitionModel.offlineRecognizer(
    context: Context,
    language: String?,
    vocabulary: WhistleVocabulary,
    onPartial: (OfflineTranscript) -> Unit,
    onTranscript: (OfflineTranscript) -> Unit,
    onError: (String) -> Unit,
): OfflineSpeechRecognizer = OfflineStreamingRecognizer(
    context = context,
    model = this,
    language = language,
    vocabulary = vocabulary,
    onPartial = onPartial,
    onTranscript = onTranscript,
    onError = onError,
)
