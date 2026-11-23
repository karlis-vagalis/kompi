package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kompi.app.live.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Live screen/semantics with explicit snapshots, not a microphone or acoustic acceptance test. */
class LiveScreenBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun mount(snapshot: LiveConversationSnapshot, onStart: () -> Unit = {}, onEnd: () -> Unit = {}) {
        compose.setContent { KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot, onEnd, onStart, {}, {}) } }
    }
    private fun actionMatching(expression: String) = compose.onNode(hasClickAction() and SemanticsMatcher("action named $expression") { node ->
        val labels = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        labels.any { Regex(expression, RegexOption.IGNORE_CASE).containsMatchIn(it) }
    })

    @Test fun l01_screenMountDoesNotInvokeStartEndOrInterrupt() {
        var starts = 0; var ends = 0; var interrupts = 0
        compose.setContent { KompiTheme(AppTheme.System) { LiveTalkScreen(LiveConversationSnapshot(), { ends++ }, { starts++ }, { interrupts++ }, {}) } }
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, ends); assertEquals(0, interrupts) }
        compose.onNodeWithText("Start Live").performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, ends) }
    }

    @Test fun l08_endIsReachableWhileWaitingNotJustInterrupt() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Waiting("request-a")))
        compose.onNodeWithText("Interrupt").assertIsDisplayed()
        actionMatching("^(end|stop)( live)?$").assertIsDisplayed()
    }

    @Test fun l08_endIsReachableWhileSpeakingNotJustInterrupt() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Speaking("request-a"), assistantText = "An answer is playing"))
        compose.onNodeWithText("Interrupt").assertIsDisplayed()
        actionMatching("^(end|stop)( live)?$").assertIsDisplayed()
    }

    @Test fun l08_startupCanBeStoppedWithoutWaitingForModelOrNetwork() {
        var ended = 0
        mount(LiveConversationSnapshot(state = LiveConversationState.Preparing), onEnd = { ended++ })
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ended) }
    }

    @Test fun l09_inputMuteIsAnIndependentNamedControl() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Listening))
        actionMatching("(mute|pause|hold).*(microphone|mic)|(microphone|mic).*(mute|pause|hold)").assertIsDisplayed()
    }

    @Test fun l10_outputMuteIsAnIndependentNamedControl() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Speaking("request-a"), assistantText = "Keep the text"))
        actionMatching("mute.*(output|speaker|voice|audio)|(output|speaker|voice|audio).*mute").assertIsDisplayed()
    }

    @Test fun l11_interruptedSessionOffersExplicitRestart() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Interrupted))
        compose.onNodeWithText("Start Live").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun l37_textIsVisibleBeforePcmAndWithoutSpeakingState() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Preparing, assistantText = "Text is ready before audio", replyRequestId = "request-a"))
        compose.onNodeWithText("Text is ready before audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice waveform").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preparing. Audio level 0 percent."))
    }

    @Test fun l51_userCaptionSurvivesWaitingAndPreparation() {
        var snapshot by mutableStateOf(LiveConversationSnapshot(state = LiveConversationState.Waiting("request-a"), caption = LiveCaption.fromWhistle("Committed words", "", null)))
        compose.setContent { KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot, {}, {}, {}, {}) } }
        compose.onNodeWithText("Committed words").assertIsDisplayed()
        compose.runOnIdle { snapshot = snapshot.copy(state = LiveConversationState.Preparing) }
        compose.onNodeWithText("Committed words").assertIsDisplayed()
    }

    @Test fun l53_longTranscriptScrollsWithoutCoveringStop() {
        val full = "Readable complete paragraph. ".repeat(210) + "Tail sentinel"
        mount(LiveConversationSnapshot(state = LiveConversationState.Listening, assistantText = full, replyRequestId = "request-a"))
        compose.onNodeWithText("Tail sentinel", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test fun l58_waveformUsesOwnedPlaybackNotMicEnergyWhilePreparingOrDraining() {
        var snapshot by mutableStateOf(LiveConversationSnapshot(state = LiveConversationState.Preparing, micLevel = AudioLevel.normalized(0.8f), playbackLevel = AudioLevel.Quiet))
        compose.setContent { KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Voice waveform").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preparing. Audio level 0 percent."))
        compose.runOnIdle { snapshot = snapshot.copy(state = LiveConversationState.Speaking("request-a"), playbackLevel = AudioLevel.normalized(0.5f)) }
        compose.onNodeWithContentDescription("Voice waveform").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Speaking. Audio level 50 percent."))
        compose.runOnIdle { snapshot = snapshot.copy(playbackLevel = AudioLevel.Quiet) }
        compose.onNodeWithContentDescription("Voice waveform").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Speaking. Audio level 0 percent."))
    }

    @Test fun l81_accessibleStateIsExposedWithoutStealingFocus() {
        var snapshot by mutableStateOf(LiveConversationSnapshot())
        compose.setContent { KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Live voice").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Idle"))
        compose.runOnIdle { snapshot = snapshot.copy(state = LiveConversationState.Listening) }
        compose.onNodeWithContentDescription("Live voice").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening"))
        compose.onNodeWithText("Stop").assertHasClickAction()
    }

    @Test fun l82_largeFontSmallViewportKeepsExitControlReachable() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                KompiTheme(AppTheme.System) {
                    Box(Modifier.requiredSize(320.dp, 420.dp)) {
                        LiveTalkScreen(LiveConversationSnapshot(state = LiveConversationState.Listening,
                            caption = LiveCaption.fromWhistle("A long caption with readable words. ".repeat(25), "", null)), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Stop").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun l83_lightDarkAndSystemRetainReadableNamedActions() {
        var mode by mutableStateOf(AppTheme.System)
        compose.setContent { KompiTheme(mode) { LiveTalkScreen(LiveConversationSnapshot(state = LiveConversationState.Listening), {}, {}, {}, {}) } }
        for (next in AppTheme.entries) {
            compose.runOnIdle { mode = next }
            compose.onNodeWithText("Stop").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithContentDescription("Live voice").assertExists()
        }
    }

    @Test fun l85_stopHasAtLeast48DpInteractiveHeight() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            KompiTheme(AppTheme.System) { LiveTalkScreen(LiveConversationSnapshot(state = LiveConversationState.Listening), {}, {}, {}, {}) }
        }
        val stop = compose.onNodeWithText("Stop").assertHasClickAction().fetchSemanticsNode()
        assertTrue("Interactive height was ${stop.boundsInRoot.height / density} dp", stop.boundsInRoot.height / density >= 48f)
    }

    @Test fun l86_failureWithoutPendingRequestMustNotOfferNonfunctionalRetry() {
        mount(LiveConversationSnapshot(state = LiveConversationState.Failure("Audio route is unavailable"), replyRequestId = null))
        compose.onNodeWithText("Audio route is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Start Live").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
