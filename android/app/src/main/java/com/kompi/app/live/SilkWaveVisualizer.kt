package com.kompi.app.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kompi.app.ui.KompiUi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** A normalized, finite audio magnitude parsed once at the capture/playback boundary. */
@JvmInline
value class AudioLevel private constructor(val value: Float) {
    companion object {
        fun normalized(raw: Float): AudioLevel = AudioLevel(
            when {
                !raw.isFinite() -> 0f
                else -> raw.coerceIn(0f, 1f)
            },
        )

        val Quiet = AudioLevel(0f)
    }
}

/**
 * A compact immutable snapshot of at most eight real frequency-band magnitudes.
 * Values are copied and clamped on construction; no caller-owned mutable array escapes.
 */
@Immutable
class AudioBands private constructor(
    val count: Int,
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val b3: Float,
    private val b4: Float,
    private val b5: Float,
    private val b6: Float,
    private val b7: Float,
) {
    operator fun get(index: Int): Float {
        require(index in 0 until count) { "Band index out of range" }
        return when (index) {
            0 -> b0
            1 -> b1
            2 -> b2
            3 -> b3
            4 -> b4
            5 -> b5
            6 -> b6
            else -> b7
        }
    }

    override fun equals(other: Any?): Boolean = other is AudioBands &&
        count == other.count && b0 == other.b0 && b1 == other.b1 && b2 == other.b2 && b3 == other.b3 &&
        b4 == other.b4 && b5 == other.b5 && b6 == other.b6 && b7 == other.b7

    override fun hashCode(): Int {
        var result = count
        result = 31 * result + b0.hashCode()
        result = 31 * result + b1.hashCode()
        result = 31 * result + b2.hashCode()
        result = 31 * result + b3.hashCode()
        result = 31 * result + b4.hashCode()
        result = 31 * result + b5.hashCode()
        result = 31 * result + b6.hashCode()
        result = 31 * result + b7.hashCode()
        return result
    }

    /** Linear interpolation across this bounded snapshot, or zero when no bands were supplied. */
    fun at(position: Float): Float {
        if (count == 0) return 0f
        if (count == 1) return b0
        val normalizedPosition = if (position.isFinite()) position.coerceIn(0f, 1f) else 0f
        val scaled = normalizedPosition * (count - 1)
        val lower = scaled.toInt().coerceIn(0, count - 1)
        val upper = (lower + 1).coerceAtMost(count - 1)
        return get(lower) + (get(upper) - get(lower)) * (scaled - lower)
    }

    companion object {
        val Empty = AudioBands(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun of(raw: FloatArray): AudioBands {
            val count = raw.size.coerceAtMost(MAX_BANDS)
            fun sample(index: Int): Float {
                if (index >= count) return 0f
                val value = raw[index]
                return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            }
            return AudioBands(
                count = count,
                b0 = sample(0), b1 = sample(1), b2 = sample(2), b3 = sample(3),
                b4 = sample(4), b5 = sample(5), b6 = sample(6), b7 = sample(7),
            )
        }

        private const val MAX_BANDS = 8
    }
}

enum class SilkPhase(val spokenLabel: String) {
    Idle("Idle"),
    Listening("Listening"),
    Recognizing("Recognizing speech"),
    Thinking("Thinking"),
    Speaking("Speaking"),
    Interrupted("Interrupted"),
    Paused("Paused"),
}

/**
 * Native Compose interpretation of the Silk Layers reference: a quiet centerline with fine,
 * parallel wisps that breathe only with the supplied microphone/TTS signal. It does not synthesize
 * levels or run an independent animation clock. Captions and controls belong in the caller's layout.
 */
@Composable
fun SilkLayersVisualizer(
    level: AudioLevel,
    bands: AudioBands = AudioBands.Empty,
    phase: SilkPhase,
    motionEnabled: Boolean,
    reducedMotion: Boolean = !motionEnabled,
    modifier: Modifier = Modifier,
) {
    val measuredLevel = when (phase) {
        SilkPhase.Listening, SilkPhase.Recognizing, SilkPhase.Speaking -> level.value
        SilkPhase.Idle, SilkPhase.Thinking, SilkPhase.Interrupted, SilkPhase.Paused -> 0f
    }
    val audioPhaseActive = phase == SilkPhase.Listening || phase == SilkPhase.Recognizing || phase == SilkPhase.Speaking
    val lowUpdateMode = reducedMotion || !motionEnabled
    // Perceptual sensitivity makes quiet, genuine PCM audible without adding a synthetic floor.
    val visualLevel = measuredLevel.pow(LEVEL_GAMMA)
    val latestVisualLevel by rememberUpdatedState(visualLevel)
    var reducedMotionLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(audioPhaseActive, lowUpdateMode) {
        if (audioPhaseActive && lowUpdateMode) {
            // Reduced motion keeps a static contour and samples the essential level at ~5 Hz.
            while (true) {
                reducedMotionLevel = latestVisualLevel.quantizedForReducedMotion()
                delay(REDUCED_MOTION_SAMPLE_MILLIS)
            }
        } else {
            reducedMotionLevel = if (audioPhaseActive) latestVisualLevel else 0f
        }
    }
    val targetLevel = if (audioPhaseActive && lowUpdateMode) reducedMotionLevel else visualLevel
    val animatedLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = if (motionEnabled && !reducedMotion && audioPhaseActive) {
            // Keep this enabled at zero input so the final quiet release eases, then stops.
            tween(durationMillis = LEVEL_EASE_MILLIS)
        } else {
            snap()
        },
        label = "silk audio level",
    )
    val drawnLevel = if (audioPhaseActive && lowUpdateMode) reducedMotionLevel else animatedLevel
    val foreground = KompiUi.colors.onSurface
    val paths = remember { List(STRAND_COUNT) { Path() } }
    // Bounded, one-decimal raw RMS diagnostics are accessible in normal mode too; no live region.
    val inputLevelTenthsPercent = (level.value * 1_000f).roundToInt().coerceIn(0, 1_000)
    val appliedLevelTenthsPercent = (measuredLevel * 1_000f).roundToInt().coerceIn(0, 1_000)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_HEIGHT_DP.dp, max = MAX_HEIGHT_DP.dp)
            .semantics(mergeDescendants = false) {
                role = Role.Image
                contentDescription = "Voice level visualization. Input audio ${inputLevelTenthsPercent / 10}.${inputLevelTenthsPercent % 10} percent. ${phase.spokenLabel}. Applied audio level ${appliedLevelTenthsPercent / 10}.${appliedLevelTenthsPercent % 10} percent."
                stateDescription = "${phase.spokenLabel}. Applied audio level ${appliedLevelTenthsPercent / 10}.${appliedLevelTenthsPercent % 10} percent."
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSilkLayers(
                level = drawnLevel,
                // Reduced motion communicates level through the coarse RMS contour, not fast band changes.
                bands = if (lowUpdateMode) AudioBands.Empty else bands,
                color = foreground,
                paths = paths,
            )
        }
    }
}

private fun DrawScope.drawSilkLayers(
    level: Float,
    bands: AudioBands,
    color: androidx.compose.ui.graphics.Color,
    paths: List<Path>,
) {
    val strandCount = STRAND_COUNT
    val centerY = size.height * CENTER_Y
    val maxTravel = size.height * MAX_WAVE_HEIGHT
    val centerStroke = Stroke(width = CENTER_STROKE_DP.dp.toPx())
    val fineStroke = Stroke(width = FINE_STROKE_DP.dp.toPx())
    val width = size.width
    val usableWidth = width * (1f - HORIZONTAL_INSET * 2f)
    val startX = width * HORIZONTAL_INSET

    for (strand in 0 until strandCount) {
        val path = paths[strand]
        path.reset()
        val strandPosition = strand.toFloat() / (strandCount - 1)
        val strandOffset = (strandPosition - 0.5f) * size.height * STRAND_SPREAD * level
        val layerGain = if (bands.count == 0) 1f else 0.52f + 0.48f * bands.at(strandPosition)
        val amplitude = maxTravel * level * layerGain
        val phase = strand * LAYER_PHASE_STEP
        val alpha = if (strand == CENTER_STRAND) CENTER_ALPHA else {
            MIN_STRAND_ALPHA + (1f - abs(strandPosition - 0.5f) * 2f) * STRAND_ALPHA_RANGE
        }

        for (sample in 0..CURVE_SEGMENTS) {
            val progress = sample.toFloat() / CURVE_SEGMENTS
            val envelope = sin(PI.toFloat() * progress).coerceAtLeast(0f).pow(ENVELOPE_POWER)
            val broadCurve = sin(progress * PI.toFloat() * BROAD_CYCLES - phase) * BROAD_WEIGHT
            val fineCurve = sin(progress * PI.toFloat() * FINE_CYCLES + phase * FINE_PHASE_RATIO) * FINE_WEIGHT
            val y = centerY + strandOffset + envelope * amplitude * (broadCurve + fineCurve)
            val x = startX + usableWidth * progress
            if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = alpha),
            style = if (strand == CENTER_STRAND) centerStroke else fineStroke,
        )
    }
}

private fun Float.quantizedForReducedMotion(): Float =
    (coerceIn(0f, 1f) * REDUCED_MOTION_LEVEL_STEPS).roundToInt() / REDUCED_MOTION_LEVEL_STEPS

/* The remembered Path objects are reused across signal redraws; no unbounded global cache. */
private const val STRAND_COUNT = 9
private const val CENTER_STRAND = STRAND_COUNT / 2
private const val CURVE_SEGMENTS = 48
private const val CENTER_Y = 0.5f
private const val HORIZONTAL_INSET = 0.2f
private const val MAX_WAVE_HEIGHT = 0.34f
private const val ENVELOPE_POWER = 1.12f
private const val BROAD_CYCLES = 2.3f
private const val FINE_CYCLES = 4.7f
private const val BROAD_WEIGHT = 0.72f
private const val FINE_WEIGHT = 0.28f
private const val FINE_PHASE_RATIO = 0.71f
private const val LAYER_PHASE_STEP = 0.49f
private const val MIN_STRAND_ALPHA = 0.2f
private const val STRAND_ALPHA_RANGE = 0.36f
private const val CENTER_ALPHA = 0.96f
private const val CENTER_STROKE_DP = 1.1f
private const val FINE_STROKE_DP = 0.72f
private const val MIN_HEIGHT_DP = 48
private const val MAX_HEIGHT_DP = 120
private const val LEVEL_EASE_MILLIS = 70
private const val LEVEL_GAMMA = 0.25f
private const val STRAND_SPREAD = 0.28f
private const val REDUCED_MOTION_LEVEL_STEPS = 8f
private const val REDUCED_MOTION_SAMPLE_MILLIS = 180L
