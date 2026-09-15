package dev.foldprobe.animation

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** One scene, split into complementary sharp and native Gaussian-blurred branches. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RevealSweepShader {
    private val sharpMask = RuntimeShader(SOURCE)
    private val softMask = RuntimeShader(SOURCE)
    private var cachedRadius = -1f
    private var cachedBlur: RenderEffect? = null

    fun effect(width: Float, p: Float, outer: Boolean): androidx.compose.ui.graphics.RenderEffect? {
        if (RevealSweep.isSharp(p, outer)) return null
        val mask = RevealSweep.mask(p, outer)
        val safeWidth = width.coerceAtLeast(1f)
        fun configure(shader: RuntimeShader, soft: Float) {
            shader.setFloatUniform("width", safeWidth)
            shader.setFloatUniform("edges", mask.low, mask.high)
            shader.setFloatUniform("outer", if (outer) 1f else 0f)
            shader.setFloatUniform("innerEdge", RevealSweep.INNER_EDGE)
            shader.setFloatUniform("soft", soft)
        }
        configure(sharpMask, 0f)
        configure(softMask, 1f)
        val radius = (if (outer) 2.2f else 3f) * safeWidth / DuoLayout.WIDTH
        if (radius != cachedRadius || cachedBlur == null) {
            cachedRadius = radius
            cachedBlur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
        val sharp = RenderEffect.createRuntimeShaderEffect(sharpMask, "content")
        val soft = RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(softMask, "content"), requireNotNull(cachedBlur))
        // Complementary premultiplied weights sum to one; SRC_OVER would darken the seam.
        return RenderEffect.createBlendModeEffect(sharp, soft, BlendMode.PLUS).asComposeRenderEffect()
    }

    private companion object {
        val SOURCE = """
            uniform shader content;
            uniform float width;
            uniform float2 edges;
            uniform float outer;
            uniform float innerEdge;
            uniform float soft;
            half4 main(float2 point) {
                float x = point.x / width;
                float ramp = smoothstep(edges.x, edges.y, x);
                float weight = outer > 0.5 ? ramp :
                    (1.0 - ramp) * (1.0 - smoothstep(innerEdge - 0.04, innerEdge, x));
                float part = soft > 0.5 ? weight : 1.0 - weight;
                return content.eval(point) * half(part);
            }
        """.trimIndent()
    }
}
