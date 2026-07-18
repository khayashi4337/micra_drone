package io.github.khayashi4337.micradrone;

import net.minecraft.client.renderer.entity.AllayRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MicraDrone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MicraDrone.MODID, value = Dist.CLIENT)
public class MicraDroneClient {
    public MicraDroneClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MicraDrone.LOGGER.info("MicraDrone: client setup complete");
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // DroneEntity is a plain Allay subclass (see DroneEntity) purely to reuse AllayRenderer/
        // AllayModel, which are generically bound to Allay.
        event.registerEntityRenderer(MicraDrone.DRONE_ENTITY.get(), AllayRenderer::new);
    }
}
