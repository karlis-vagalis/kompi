package com.kompi.app.chat

import android.animation.ValueAnimator
import android.icu.text.BreakIterator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.min

private const val BACKLOG_DRAIN_MILLIS = 250.0
private const val MAX_CHARACTER_INTERVAL_MILLIS = 5.0

/** All source text is received text. Only this view-local prefix is paced; it is never persisted. */
private class AdaptiveTextReveal(initialText: String, streaming: Boolean) {
    private enum class Phase { Receiving, Settling, Complete }
    private var phase = if (streaming) Phase.Receiving else Phase.Complete
    var target by mutableStateOf(initialText)
        private set
    var visible by mutableStateOf(initialText)
        private set
    private var position = initialText.length.toDouble()
    private var resyncNextAppend = false
    private val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(initialText) }
    val hasBacklog: Boolean get() = visible.length < target.length

    fun update(source: String, streaming: Boolean, motionAllowed: Boolean) {
        val changed = source != target
        val replacement = changed && !source.startsWith(target)
        val resumedView = changed && resyncNextAppend
        if (changed) resyncNextAppend = false
        val resume = phase == Phase.Complete && streaming
        if (changed) {
            target = source
            boundaries.setText(source)
        }
        // Initial/history content, resumed views, canonical replacements and reduced motion
        // are truthful immediately. Never replay an existing reply from an empty prefix.
        if (!motionAllowed || replacement || resumedView || resume || phase == Phase.Complete) {
            snap()
        }
        phase = when {
            streaming -> Phase.Receiving
            hasBacklog -> Phase.Settling
            else -> Phase.Complete
        }
    }

    fun suspendReveal() {
        snap()
        resyncNextAppend = true
    }

    fun snap() {
        visible = target
        position = target.length.toDouble()
        if (phase == Phase.Settling) phase = Phase.Complete
    }

    fun advance(elapsedMillis: Double) {
        val remaining = target.length - position
        if (remaining <= 0.0) { snap(); return }
        // Match assistant-ui's adaptive pacing: short suffixes progress at 5 ms/character;
        // a large backlog speeds up according to a 250 ms drain target, not a fixed typing rate.
        val interval = min(MAX_CHARACTER_INTERVAL_MILLIS, BACKLOG_DRAIN_MILLIS / remaining)
        position = min(target.length.toDouble(), position + elapsedMillis / interval)
        val proposedEnd = position.toInt()
        // Budget counts UTF-16 units, but display commits only whole grapheme clusters:
        // never paint half a surrogate pair, combining mark sequence or joined emoji.
        val end = if (boundaries.isBoundary(proposedEnd)) proposedEnd else boundaries.preceding(proposedEnd)
        if (end > visible.length) visible = target.substring(0, end)
        if (!hasBacklog && phase == Phase.Settling) phase = Phase.Complete
    }
}

/** Message-scoped smoothing before Markdown parsing, with no text-opacity animation. */
@Composable
internal fun rememberAdaptiveTextReveal(messageKey: String, source: String, streaming: Boolean): String {
    val state = remember(messageKey) { AdaptiveTextReveal(source, streaming) }
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            // A backgrounded view must not retain an animation backlog to replay on return.
            if (foreground) state.snap() else state.suspendReveal()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val motionAllowed = foreground && ValueAnimator.areAnimatorsEnabled()
    state.update(source, streaming, motionAllowed)
    LaunchedEffect(state, owner) {
        // Observe the actual target, not only a backlog boolean: closely spaced append/catch-up
        // transitions cannot be conflated into a missed wake-up after an animation finishes.
        snapshotFlow { state.target }.distinctUntilChanged().collect {
            var previousFrame = 0L
            while (state.hasBacklog) {
                val now = withFrameNanos { it }
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !ValueAnimator.areAnimatorsEnabled()) {
                    state.snap()
                    break
                }
                if (previousFrame != 0L) state.advance(((now - previousFrame) / 1_000_000.0).coerceIn(0.0, BACKLOG_DRAIN_MILLIS))
                previousFrame = now
            }
        }
    }
    return state.visible
}
