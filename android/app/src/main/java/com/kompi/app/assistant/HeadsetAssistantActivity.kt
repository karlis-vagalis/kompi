package com.kompi.app.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Bluetooth HFP uses VOICE_COMMAND, independently of Android's selected assistant role. */
class HeadsetAssistantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do not accept arbitrary explicit intents or start the microphone service here.
        if (intent.action == Intent.ACTION_VOICE_COMMAND) {
            KompiVoiceInteractionService.requestHeadsetSession(this)?.let { problem ->
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
