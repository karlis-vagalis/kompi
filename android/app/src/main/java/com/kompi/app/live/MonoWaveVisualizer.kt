package com.kompi.app.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.ui.KompiUi
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** A single tapered string drawn from real PCM, never a time-driven sine animation. */
@Composable
fun MonoWaveVisualizer(
    level: AudioLevel,
    waveform: AudioWaveform,
    active: Boolean,
    accessibleState: String,
    modifier: Modifier = Modifier,
) {
    val foreground = KompiUi.colors.onSurface
    val path = remember { Path() }
    val audible = active && level.value > 0.003f
    Box(
        modifier.heightIn(min = 72.dp, max = 132.dp).semantics {
            role = Role.Image
            contentDescription = "Voice waveform"
            stateDescription = "$accessibleState. Audio level ${(level.value * 100).toInt()} percent."
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = size.width * 0.06f
            val centerY = size.height / 2f
            val width = size.width - inset * 2f
            val amplitude = if (audible) size.height * 0.42f * level.value.pow(0.48f) else 0f
            path.reset()
            path.moveTo(inset, centerY)
            var previousX = inset
            var previousY = centerY
            for (index in 0 until waveform.size) {
                val progress = index.toFloat() / (waveform.size - 1)
                val envelope = sin(PI.toFloat() * progress).coerceAtLeast(0f).pow(1.25f)
                val x = inset + progress * width
                val y = centerY + waveform.normalizedSample(index) * envelope * amplitude
                // Smooth the bounded PCM samples into one continuous string.
                path.quadraticTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
                previousX = x
                previousY = y
            }
            path.lineTo(inset + width, centerY)
            drawPath(
                path,
                color = foreground.copy(alpha = if (audible) 0.95f else 0.65f),
                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
