package org.blackaddons.blackvertex.mixin;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1.2 has no way for a mod to put its own render pass into the submit pipeline — {@code
 * FeatureRendererType} arrived in 26.2 — so the GPU cosmetics path is driven from here instead.
 * Both draw hooks fire once per feature-render scope, which is all {@link GpuCosmetics} needs:
 * the world pass reaches them through {@code LevelRenderer}, and every GUI portrait through
 * {@code GuiEntityRenderer.renderAllFeatures}, which calls the same two methods.
 */
@Mixin(FeatureRenderDispatcher.class)
public class FeatureRenderDispatcherMixin {

    @Inject(method = "renderSolidFeatures", at = @At("TAIL"))
    private void blackvertex$drawSolid(CallbackInfo ci) {
        GpuCosmetics.INSTANCE.renderSolid();
    }

    @Inject(method = "renderTranslucentFeatures", at = @At("TAIL"))
    private void blackvertex$drawBlended(CallbackInfo ci) {
        GpuCosmetics.INSTANCE.renderBlended();
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void blackvertex$endFrame(CallbackInfo ci) {
        GpuCosmetics.INSTANCE.endFrame();
    }
}
