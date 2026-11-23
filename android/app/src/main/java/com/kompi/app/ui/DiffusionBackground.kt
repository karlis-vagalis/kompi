package com.kompi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The approved CSS color-field: composite six blocks, then blur that single layer. */
@Composable
fun DiffusionBackground(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 38.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.background(Color(0xFF030000)).clipToBounds()) {
        val overscan = 38.dp
        val w = maxWidth + overscan * 2
        val h = maxHeight + overscan * 2
        Box(
            Modifier
                // Allow real overscan instead of constraining the field to the viewport.
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(-overscan, -overscan)
                .requiredSize(w, h)
                .blur(blurRadius, BlurredEdgeTreatment.Unbounded),
        ) {
            ColorBlock(0.dp, 0.dp, w, h * 0.36f, Color(0xFFFF8C08))
            ColorBlock(0.dp, h * 0.25f, w, h * 0.12f, Color(0xFFA42A05))
            ColorBlock(0.dp, h * 0.22f, w * 0.23f, h * 0.85f, Color(0xFFB33209))
            ColorBlock(w * 0.77f, h * 0.22f, w * 0.23f, h * 0.85f, Color(0xFFB33209))
            ColorBlock(0.dp, h * 0.84f, w, h * 0.16f, Color(0xFF961F07))
            // Black is last: the broad center remains dark after the layers diffuse.
            ColorBlock(w * 0.16f, h * 0.36f, w * 0.68f, h * 0.54f, Color(0xFF020000))
        }
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun ColorBlock(x: Dp, y: Dp, width: Dp, height: Dp, color: Color) {
    Box(Modifier.offset(x, y).requiredSize(width, height).background(color))
}
