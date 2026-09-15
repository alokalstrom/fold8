package dev.foldprobe.animation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Optional post-process over our own rendered objects. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class DuoShader {
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float2 resolution;
        uniform float progress;
        half4 main(float2 point) {
            float bend = sin(progress * 3.14159265);
            float2 uv = point / resolution;
            float center = uv.x - 0.5;
            float local = exp(-abs(center) * 7.0);
            float2 q = point;
            q.x += center * local * bend * resolution.x * 0.10;
            q.y += sin(uv.y * 3.14159265) * local * bend * resolution.y * 0.016;
            float b = bend * 3.0;
            float2 lo = float2(0.5);
            float2 hi = resolution - float2(0.5);
            half4 c = content.eval(clamp(q, lo, hi)) * 0.40;
            c += content.eval(clamp(q + float2(b, 0), lo, hi)) * 0.15;
            c += content.eval(clamp(q - float2(b, 0), lo, hi)) * 0.15;
            c += content.eval(clamp(q + float2(0, b), lo, hi)) * 0.15;
            c += content.eval(clamp(q - float2(0, b), lo, hi)) * 0.15;
            return c;
        }
    """.trimIndent())
    fun effect(width: Float, height: Float, p: Float): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("resolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        shader.setFloatUniform("progress", DuoLayout.bounded(p))
        // RenderEffect captures the shader state: supply this frame's uniforms before creating it.
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
