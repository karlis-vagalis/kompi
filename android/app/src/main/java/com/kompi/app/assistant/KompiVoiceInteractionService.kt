package com.kompi.app.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Android owns binding and hardware/assistant dispatch; this service never opens a microphone. */
class KompiVoiceInteractionService : VoiceInteractionService() {
    private val availability = services.register(this)

    override fun onReady() {
        super.onReady()
        if (availability.onReady()) refreshScreenContextPolicy(this)
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        if (services.current === this) showSession(null, 0)
    }

    override fun onShutdown() {
        if (availability.retire()) AssistantConversationService.stop(this)
        super.onShutdown()
    }

    override fun onDestroy() {
        if (availability.retire()) AssistantConversationService.stop(this)
        super.onDestroy()
    }

    companion object {
        private val services = AssistantServiceAvailability<KompiVoiceInteractionService>()

        internal fun refreshScreenContextPolicy(context: Context) {
            val disabled = VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
                if (AssistantScreenContext.enabled(context)) 0 else VoiceInteractionSession.SHOW_WITH_ASSIST
            services.current?.setDisabledShowContext(disabled)
        }

        /** Request a framework session; only its onShow callback may invoke microphone capture. */
        internal fun requestHeadsetSession(context: Context): String? {
            AssistantConversationService.prerequisite(context)?.let { return it }
            val service = services.current
                ?: return "Android's Kompi assistant service is not ready. Try the earbud gesture again."
            return try {
                refreshScreenContextPolicy(context)
                val contextFlags = if (!AssistantScreenContext.enabled(context)) 0 else
                    VoiceInteractionSession.SHOW_WITH_ASSIST or
                        if (android.os.Build.VERSION.SDK_INT >= 37) VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT else 0
                service.showSession(null, VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE or contextFlags)
                null
            } catch (error: RuntimeException) {
                "Android could not open Kompi's assistant session. Check the default assistant setting."
            }
        }
    }
}

class KompiVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var completion: kotlinx.coroutines.Job? = null
        private var invocation = 0L

        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            setUiEnabled(false) // The existing Kompi Live screen supplies the visible controls.
            invocation = AssistantScreenContext.begin(this@KompiVoiceSessionService)
            val captureInvocation = invocation
            scope.launch { kotlinx.coroutines.delay(120_000); AssistantScreenContext.clear(captureInvocation) }
            setDisabledShowContext(SHOW_WITH_SCREENSHOT or
                if (AssistantScreenContext.enabled(this@KompiVoiceSessionService)) 0 else SHOW_WITH_ASSIST)
        }

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            completion?.cancel()
            AssistantCoordinator.failure.value = null
            try { startAssistantActivity(AssistantConversationService.activityIntent(this@KompiVoiceSessionService)) }
            catch (error: Exception) {
                AssistantCoordinator.failure.value = "Android could not open Kompi's assistant screen. Open Kompi to check setup."
                finish()
                return
            }
            var started = AssistantCoordinator.mode.value.assistant
            completion = scope.launch {
                combine(AssistantCoordinator.mode, AssistantCoordinator.failure) { mode, failure ->
                    if (mode.assistant) started = true
                    failure != null || (started && !mode.assistant)
                }.first { it }
                finish()
            }
            AssistantConversationService.invoke(this@KompiVoiceSessionService)
        }

        @androidx.annotation.RequiresApi(29)
        override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
            // Split-screen/PiP can deliver multiple activities. Never merge another app into
            // the focused screen the user invoked the assistant from.
            if (state.isFocused) AssistantScreenContext.capture(this@KompiVoiceSessionService, invocation, state.assistStructure)
        }

        @Suppress("DEPRECATION")
        override fun onHandleAssist(data: Bundle?, structure: android.app.assist.AssistStructure?, content: android.app.assist.AssistContent?) {
            AssistantScreenContext.capture(this@KompiVoiceSessionService, invocation, structure)
        }

        override fun onAssistStructureFailure(failure: Throwable) {
            AssistantScreenContext.capture(this@KompiVoiceSessionService, invocation, null)
        }

        override fun onDestroy() { AssistantScreenContext.clear(invocation); scope.cancel(); super.onDestroy() }
    }
}
