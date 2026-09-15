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

    fun effect(width: Float, height: Float, p: Float, outer: Boolean,
        perspective: Boolean = false): androidx.compose.ui.graphics.RenderEffect? {
        if (RevealSweep.isSharp(p, outer)) return null
        val mask = RevealSweep.mask(p, outer)
        val safeWidth = width.coerceAtLeast(1f)
        val projection = CoverPerspective.projection(p, perspective, outer)
        fun configure(shader: RuntimeShader, soft: Float) {
            shader.setFloatUniform("width", safeWidth)
            shader.setFloatUniform("height", height.coerceAtLeast(1f))
            shader.setFloatUniform("projection", projection.compression, projection.skew)
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
            uniform float height;
            uniform float2 projection;
            uniform float2 edges;
            uniform float outer;
            uniform float innerEdge;
            uniform float soft;
            half4 main(float2 point) {
                float2 samplePoint = point;
                if (projection.x > 0.0 || projection.y > 0.0) {
                    float span = 1.0 - innerEdge;
                    float u = clamp((point.x / width - innerEdge) / span, 0.0, 1.0);
                    float denominator = 1.0 + projection.y * u;
                    samplePoint.x = width * (innerEdge + span * u * (1.0 - projection.x) / denominator);
                    samplePoint.y = height * (0.5 + (point.y / height - 0.5) / denominator);
                    // Keep filtering inside the recorded layer at its top/bottom edges.
                    samplePoint = clamp(samplePoint, float2(0.5), float2(width, height) - 0.5);
                }
                float x = samplePoint.x / width;
                float ramp = smoothstep(edges.x, edges.y, x);
                float weight = outer > 0.5 ? ramp :
                    (1.0 - ramp) * (1.0 - smoothstep(innerEdge - 0.04, innerEdge, x));
                float part = soft > 0.5 ? weight : 1.0 - weight;
                return content.eval(samplePoint) * half(part);
            }
        """.trimIndent()
    }
}
