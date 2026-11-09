package org.blackaddons.blackvertex.texture

import com.mojang.blaze3d.platform.NativeImage
import org.blackaddons.blackvertex.texture.TextureCompositor.compose
import kotlin.math.roundToInt

object TextureCompositor {

    /**
     * An overlay image plus the accent color (ARGB) it is tinted with.
     *
     * @param invertAlpha flips the mask (uses `255 - alpha`) when the art is authored the other way around, for example
     *     a gradient that is dense at the base when you want it at the tip.
     * @param supportsAlpha uses [tintArgb]'s alpha byte as the layer's opacity (0xFF is full, 0x80 is 50%). When false,
     *     the tint alpha is ignored and the layer is applied fully.
     */
    class Layer(
        val image: NativeImage,
        val tintArgb: Int,
        val invertAlpha: Boolean = false,
        val supportsAlpha: Boolean = false,
    )

    /**
     * Tints [base] and draws [overlays] over it.
     *
     * @param base a grayscale base image whose alpha holds the silhouette.
     * @param baseTintArgb the primary color multiplied onto the base (0xFFFFFFFF leaves it untinted).
     * @param overlays accent layers, composited in order.
     * @return a new [NativeImage] that the caller owns. The inputs are not closed.
     */
    fun compose(base: NativeImage, baseTintArgb: Int, overlays: List<Layer>): NativeImage {
        val w = base.width
        val h = base.height
        val out = NativeImage(w, h, false)

        val btR = argbR(baseTintArgb); val btG = argbG(baseTintArgb); val btB = argbB(baseTintArgb)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val bp = base.getPixel(x, y)
                val baseA = argbA(bp)
                var r = argbR(bp) * btR / 255
                var g = argbG(bp) * btG / 255
                var b = argbB(bp) * btB / 255

                for (layer in overlays) {
                    if (x >= layer.image.width || y >= layer.image.height) continue
                    val op = layer.image.getPixel(x, y)
                    val tint = layer.tintArgb
                    // Blend strength: the overlay's mask alpha (inverted if set) scaled by the layer's opacity, which
                    // is the tint alpha when supported and full otherwise.
                    val mask = if (layer.invertAlpha) 255 - argbA(op) else argbA(op)
                    val tintA = if (layer.supportsAlpha) argbA(tint) else 255
                    val f = mask * tintA / 255
                    if (f == 0) continue
                    // Accent: the tint color multiplied by the overlay's (white) RGB.
                    val ar = argbR(tint) * argbR(op) / 255
                    val ag = argbG(tint) * argbG(op) / 255
                    val ab = argbB(tint) * argbB(op) / 255
                    r = (r * (255 - f) + ar * f) / 255
                    g = (g * (255 - f) + ag * f) / 255
                    b = (b * (255 - f) + ab * f) / 255
                }

                out.setPixel(x, y, (baseA shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return out
    }

    /**
     * Bakes the emissive (glow) [layers] into a mostly transparent image. Each layer adds `rgb = tint ⊗ overlayRGB`
     * where its alpha mask is set, combined source-over, and the result is drawn later as a separate fullbright pass
     * (the glowing tip). Layers that support alpha use the tint's alpha as their opacity, as in [compose]. Returns null
     * when there are no emissive layers. The caller owns the result, and the inputs are not closed.
     */
    fun composeEmissive(layers: List<Layer>): NativeImage? {
        if (layers.isEmpty()) return null
        val w = layers.first().image.width
        val h = layers.first().image.height
        val out = IntArray(w * h) // ARGB, starts fully transparent

        for (layer in layers) {
            val img = layer.image
            val tR = argbR(layer.tintArgb); val tG = argbG(layer.tintArgb); val tB = argbB(layer.tintArgb)
            val tintA = if (layer.supportsAlpha) argbA(layer.tintArgb) else 255
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (x >= img.width || y >= img.height) continue
                    val sp = img.getPixel(x, y)
                    var a = if (layer.invertAlpha) 255 - argbA(sp) else argbA(sp)
                    if (tintA != 255) a = a * tintA / 255
                    if (a == 0) continue
                    // Accent: the tint multiplied by the overlay's (white) RGB.
                    val sr = tR * argbR(sp) / 255
                    val sg = tG * argbG(sp) / 255
                    val sb = tB * argbB(sp) / 255
                    val idx = y * w + x
                    val dp = out[idx]
                    val sa = a / 255f
                    val da = argbA(dp) / 255f
                    val oa = sa + da * (1f - sa)
                    if (oa <= 0f) continue
                    val r = ((sr * sa + argbR(dp) * da * (1f - sa)) / oa).roundToInt().coerceIn(0, 255)
                    val g = ((sg * sa + argbG(dp) * da * (1f - sa)) / oa).roundToInt().coerceIn(0, 255)
                    val b = ((sb * sa + argbB(dp) * da * (1f - sa)) / oa).roundToInt().coerceIn(0, 255)
                    val na = (oa * 255f).roundToInt().coerceIn(0, 255)
                    out[idx] = (na shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val image = NativeImage(w, h, false)
        var idx = 0
        for (y in 0 until h) for (x in 0 until w) image.setPixel(x, y, out[idx++])
        return image
    }

    private fun argbA(c: Int) = (c ushr 24) and 0xFF
    private fun argbR(c: Int) = (c ushr 16) and 0xFF
    private fun argbG(c: Int) = (c ushr 8) and 0xFF
    private fun argbB(c: Int) = c and 0xFF
}
