package com.kompi.app.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kompi.app.ui.KompiUi
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Four restrained ribbons respond only to the actual mic/playback RMS supplied by the controller. */
@Composable
fun SpectrumRibbonsVisualizer(
    level: AudioLevel,
    active: Boolean,
    accessibleState: String,
    modifier: Modifier = Modifier,
) {
    val foreground = KompiUi.colors.onSurface
    val paths = remember { List(RIBBON_COUNT) { Path() } }
    val drawnLevel = if (active) level.value.pow(RMS_CURVE) else 0f
    val percent = (level.value * 100f).toInt().coerceIn(0, 100)
    Box(
        modifier = modifier.heightIn(min = 72.dp, max = 132.dp).semantics {
            role = Role.Image
            contentDescription = "Voice waveform. $accessibleState."
            stateDescription = "$accessibleState. Audio level $percent percent."
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height * 0.5f
            val spread = size.height * 0.055f
            val maxAmplitude = size.height * 0.30f * drawnLevel
            val inset = size.width * 0.06f
            val usableWidth = size.width - inset * 2f
            for (index in paths.indices) {
                val path = paths[index]
                path.reset()
                val position = index.toFloat() / (RIBBON_COUNT - 1)
                val phase = (position - 0.5f) * 1.05f
                val upper = Array(CURVE_SEGMENTS + 1) { Offset.Zero }
                val lower = Array(CURVE_SEGMENTS + 1) { Offset.Zero }
                for (sample in 0..CURVE_SEGMENTS) {
                    val xProgress = sample.toFloat() / CURVE_SEGMENTS
                    val envelope = sin(PI.toFloat() * xProgress).coerceAtLeast(0f).pow(0.7f)
                    val wave = sin(xProgress * PI.toFloat() * 2.35f + phase)
                    val y = centerY + (position - 0.5f) * spread + wave * envelope * maxAmplitude
                    val x = inset + usableWidth * xProgress
                    val thickness = (if (index == RIBBON_COUNT / 2) 2.4f else 1.7f).dp.toPx() * envelope
                    upper[sample] = Offset(x, y - thickness * 0.5f)
                    lower[sample] = Offset(x, y + thickness * 0.5f)
                }
                path.moveTo(upper[0].x, upper[0].y)
                for (point in 1..CURVE_SEGMENTS) path.lineTo(upper[point].x, upper[point].y)
                for (point in CURVE_SEGMENTS downTo 0) path.lineTo(lower[point].x, lower[point].y)
                path.close()
                drawPath(
                    path = path,
                    color = foreground.copy(alpha = 0.34f + 0.15f * (1f - kotlin.math.abs(position - 0.5f) * 2f)),
                )
            }
        }
    }
}

private const val RIBBON_COUNT = 4
private const val CURVE_SEGMENTS = 40
private const val RMS_CURVE = 0.48f
