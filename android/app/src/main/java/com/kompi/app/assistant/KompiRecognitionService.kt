package com.kompi.app.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires a recognition-service component in voice-interaction metadata. Kompi's
 * Whistle Live pipeline owns recognition; this component does not claim to be a general-purpose
 * Android SpeechRecognizer provider and explicitly rejects those separate framework requests.
 */
class KompiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback) = Unit
}
