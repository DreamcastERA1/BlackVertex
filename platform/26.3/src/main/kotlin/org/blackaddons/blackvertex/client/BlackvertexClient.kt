package org.blackaddons.blackvertex.client

import net.fabricmc.api.ClientModInitializer
import org.blackaddons.blackvertex.render.PlayerCosmetics
import org.blackaddons.blackvertex.render.gpu.BlackVertexGpu
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics

class BlackvertexClient : ClientModInitializer {

    override fun onInitializeClient() {
        PlayerCosmetics.init()
        // Installs the 26.3 GPU backend behind the shared interface, then registers the skinning pipeline before the
        // loading screen compiles pipelines. The GPU path is the default; -Dblackvertex.backend=cpu forces the CPU
        // path.
        BlackVertexGpu.backend = GpuCosmetics
        GpuCosmetics.init()

        if (BlackvertexDemo.ENABLED) {
            DemoStress.register()
            DemoTune.register()
            BlackvertexDemo.setup()
        }
    }
}
