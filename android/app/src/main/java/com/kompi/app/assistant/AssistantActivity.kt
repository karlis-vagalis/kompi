package com.kompi.app.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompi.app.AppTheme
import com.kompi.app.KompiTheme
import com.kompi.app.MainActivity
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

/** Android's assistant task layer must not create a second normal app/transport runtime. */
class AssistantActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        ReplyNotifications.visibilityChanged(this, true)
    }

    override fun onStop() {
        ReplyNotifications.visibilityChanged(this, false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            val mode by AssistantCoordinator.mode.collectAsState()
            val failure by AssistantCoordinator.failure.collectAsState()
            var sessionStarted by remember { mutableStateOf(mode.assistant) }
            LaunchedEffect(mode.assistant) { if (mode.assistant) sessionStarted = true }
            val theme = runCatching { AppTheme.valueOf(getSharedPreferences("settings", 0).getString("theme", "System").orEmpty()) }.getOrDefault(AppTheme.System)
            KompiTheme(theme) {
                if (mode.assistant) AssistantSessionScreen()
                else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    KompiText(when { failure != null -> "Assistant could not start"; sessionStarted -> "Voice session ended"; else -> "Starting Kompi assistant…" }, style = KompiUi.typography.headlineSmall)
                    failure?.let { KompiText(it, color = KompiUi.colors.onSurfaceVariant) }
                    KompiButton(onClick = {
                        AssistantCoordinator.failure.value = null
                        startActivity(Intent(this@AssistantActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }) { KompiText("Open Kompi") }
                    KompiTextButton(onClick = { AssistantCoordinator.failure.value = null; finish() }) { KompiText("Close") }
                }
            }
        }
    }
}
