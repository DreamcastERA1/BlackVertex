package org.blackaddons.blackvertex.mixin;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.class)
public class FeatureRenderDispatcherMixin {

    @Inject(method = "renderAllFeatures", at = @At("HEAD"))
    private void blackvertex$enterRenderAll(CallbackInfo ci) {
        GpuCosmetics.INSTANCE.beginRenderAll();
    }

    @Inject(method = "renderAllFeatures", at = @At("TAIL"))
    private void blackvertex$exitRenderAll(CallbackInfo ci) {
        GpuCosmetics.INSTANCE.endRenderAll();
    }

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
