package com.kompi.app.assistant

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kompi.app.transport.AndroidToolRequest
import kotlinx.coroutines.flow.first

/** Gives the transport a foreground Activity, not permission to act. The transport claims the
 * request and checks that computer's grant before Android APIs are called. */
@Composable
internal fun AndroidToolHandler(request: AndroidToolRequest, complete: suspend (Activity) -> Unit) {
    val activity = LocalContext.current.activity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestComplete = rememberUpdatedState(complete)
    LaunchedEffect(request, activity, lifecycle) {
        // Don't cancel on a pause caused by our own system UI handoff.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        try {
            latestComplete.value(activity)
        } catch (_: IllegalStateException) {
            // The request or assistant runtime was retired before it could be claimed.
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
