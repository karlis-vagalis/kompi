package com.kompi.app.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

private const val GRAIN_SHADER = """
    uniform shader composable;
    uniform float noiseAmount;
    uniform float grainSize;

    // Sine-free spatial hash: cross-mix both pixel axes before taking the fraction.
    // Small intermediate values avoid the precision bands of a large sine multiplier.
    float rand(float2 pixel) {
        float3 mixed = fract(float3(pixel.x, pixel.y, pixel.x) * float3(0.1031, 0.1030, 0.0973));
        mixed += dot(mixed, mixed.yzx + 33.33);
        return fract((mixed.x + mixed.y) * mixed.z);
    }

    half4 main(float2 fragCoord) {
        half4 color = composable.eval(fragCoord);
        float noise = rand(floor(fragCoord / grainSize)) - 0.5;
        // Keep the input's premultiplied alpha and do not paint transparent pixels.
        color.rgb = clamp(color.rgb + half3(noise * noiseAmount) * color.a,
                          half3(0.0), half3(color.a));
        return color;
    }
"""

/** Static grain sized in physical pixels; older Android versions retain the original background. */
@Composable
fun Modifier.grainEffect(amount: Float = 0.08f, grainSize: Float = 1.5f): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this

    // No size/time uniform: resizing needs no shader allocation, and idle grain never animates.
    val effect = remember(amount, grainSize) {
        val shader = RuntimeShader(GRAIN_SHADER).apply {
            setFloatUniform("noiseAmount", amount)
            setFloatUniform("grainSize", grainSize)
        }
        RenderEffect.createRuntimeShaderEffect(shader, "composable").asComposeRenderEffect()
    }
    return graphicsLayer { renderEffect = effect }
}
