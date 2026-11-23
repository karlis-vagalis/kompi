package com.kompi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/** Screen-wide appearance; foreground surfaces choose their own opacity. */
@Composable
fun ChatAtmosphere(modifier: Modifier = Modifier) {
    val decorative = modifier.semantics { hideFromAccessibility() }.grainEffect()
    if (KompiUi.colors.background.red < 0.2f) {
        DiffusionBackground(decorative.fillMaxSize())
        return
    }
    val accent = KompiUi.colors.primary
    Box(
        decorative.fillMaxSize().background(KompiUi.colors.background)
            .drawWithCache {
                val edgeLights = listOf(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.12f), accent.copy(alpha = 0.035f), Color.Transparent),
                        center = Offset(size.width * 0.98f, size.height * 0.30f),
                        radius = size.height * 0.58f,
                    ),
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.02f, size.height * 0.74f),
                        radius = size.height * 0.46f,
                    ),
                )
                onDrawBehind { edgeLights.forEach { brush -> drawRect(brush) } }
            },
    )
}
