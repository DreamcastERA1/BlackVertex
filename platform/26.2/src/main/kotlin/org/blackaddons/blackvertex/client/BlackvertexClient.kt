package org.blackaddons.blackvertex.client

import net.fabricmc.api.ClientModInitializer
import org.blackaddons.blackvertex.render.PlayerCosmetics
import org.blackaddons.blackvertex.render.gpu.BlackVertexGpu
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics

class BlackvertexClient : ClientModInitializer {

    override fun onInitializeClient() {
        PlayerCosmetics.init()
        // Install the 26.2 GPU backend behind the shared seam, then register the skinning pipeline
        // before the loading screen's pipeline-compile pass (GPU default, CPU forced via
        // -Dblackvertex.backend=cpu).
        BlackVertexGpu.backend = GpuCosmetics
        GpuCosmetics.init()

        if (BlackvertexDemo.ENABLED) {
            // Command-based demo tools are 26.2-specific (Fabric's client command API); the cosmetic
            // setup itself is shared.
            DemoStress.register()
            DemoTune.register()
            BlackvertexDemo.setup()
        }
    }
}
