package com.kompi.app.assistant.actions

import android.content.Intent
import androidx.activity.ComponentActivity

/** Debug-only Activity that captures handoffs so instrumentation tests do not launch apps or perform actions. */
class PhoneActionTestHostActivity : ComponentActivity() {
    var startedIntent: Intent? = null
    var failStartActivity: Boolean = false

    override fun startActivity(intent: Intent) {
        if (failStartActivity) throw android.content.ActivityNotFoundException("No test handler")
        startedIntent = intent
    }
}
