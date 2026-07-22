package org.blackaddons.blackvertex.client

import net.fabricmc.api.ClientModInitializer
import org.blackaddons.blackvertex.render.PlayerCosmetics
import org.blackaddons.blackvertex.render.gpu.BlackVertexGpu
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics

class BlackvertexClient : ClientModInitializer {

    override fun onInitializeClient() {
        PlayerCosmetics.init()
        // Install the 26.1.2 GPU backend behind the shared seam; FeatureRenderDispatcherMixin drives
        // its draws. GPU default, CPU forced via -Dblackvertex.backend=cpu.
        BlackVertexGpu.backend = GpuCosmetics
        GpuCosmetics.init()

        if (BlackvertexDemo.ENABLED) {
            DemoStress.register()
            DemoTune.register()
            BlackvertexDemo.setup()
        }
    }
}
