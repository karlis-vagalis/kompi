package com.kompi.app.assistant.native

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompi.app.AppTheme
import com.kompi.app.KompiTheme
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

/** Permission-rationale destination only; opening it never reads data or grants access. */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val theme = runCatching { AppTheme.valueOf(getSharedPreferences("settings", 0).getString("theme", "System").orEmpty()) }.getOrDefault(AppTheme.System)
        setContent { KompiTheme(theme) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                KompiText("Health data access", style = KompiUi.typography.headlineSmall)
                KompiText("Kompi can read steps, sleep, exercise or heart-rate records that you choose to allow in Health Connect. It does not write health records or track health in the background.")
                KompiText("Each read requires confirmation while the phone is unlocked. The requested data is sent to your selected computer for the current Pi conversation and may be retained in that conversation or processed by its configured model provider.")
                KompiText("Only records shared by your data sources are available. Empty results do not mean zero activity. This integration does not provide medical advice.")
                KompiText("Revoke access at any time in Android Settings → Health Connect → App permissions. Revoke individual data types or all Kompi access.")
                KompiTextButton(onClick = { finish() }) { KompiText("Close") }
            }
        } }
    }
}
