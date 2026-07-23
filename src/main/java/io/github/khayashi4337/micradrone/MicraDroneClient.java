package io.github.khayashi4337.micradrone;

import io.github.khayashi4337.micradrone.client.DroneModel;
import io.github.khayashi4337.micradrone.client.DroneRenderer;
import io.github.khayashi4337.micradrone.client.DroneScreen;
import io.github.khayashi4337.micradrone.client.EnchantScrollScreen;
import io.github.khayashi4337.micradrone.client.IdeScreen;
import io.github.khayashi4337.micradrone.client.ShopScreen;
import io.github.khayashi4337.micradrone.drone.ScriptId;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MicraDrone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MicraDrone.MODID, value = Dist.CLIENT)
public class MicraDroneClient {
    /** Registered in {@link #registerLayerDefinitions}, baked in {@link DroneRenderer}'s constructor. */
    public static final ModelLayerLocation DRONE_MODEL_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "drone"), "main");

    public MicraDroneClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MicraDrone.LOGGER.info("MicraDrone: client setup complete");
    }

    @SubscribeEvent
    static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DRONE_MODEL_LAYER, DroneModel::createBodyLayer);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // DroneEntity is a plain Allay subclass server-side (see DroneEntity) purely to reuse Allay's
        // behavior/hitbox; its look is fully custom (see DroneModel/DroneRenderer).
        event.registerEntityRenderer(MicraDrone.DRONE_ENTITY.get(), DroneRenderer::new);
    }

    /**
     * Called from DroneControllerBlock's client-side useWithoutItem branch (issue #8): the IDE,
     * editing the slotted scroll, is the controller's default screen. The script list/log screen
     * (DroneScreen) opens from the IDE's Scripts button.
     */
    public static void openIdeScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new IdeScreen(pos, ScriptId.CONTROLLER_ID,
                Component.translatable("gui.micradrone.ide_screen.slotted_scroll").getString()));
    }

    /** Called from CornerMarkerBlock's client-side useWithoutItem branch. pos is the marker, not a controller. */
    public static void openShopScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ShopScreen(pos));
    }

    /** Called from ScriptScrollItem's client-side onItemUseFirst branch (blank scroll on an enchanting table). */
    public static void openEnchantScrollScreen(BlockPos tablePos, InteractionHand hand) {
        Minecraft.getInstance().setScreen(new EnchantScrollScreen(tablePos, hand));
    }

    /** Registered as the DroneLogPayload handler in MicraDrone's RegisterPayloadHandlersEvent listener. */
    public static void handleDroneLog(DroneLogPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof DroneScreen screen) {
            screen.updateLog(payload.pos(), payload.lines(), payload.pointsByCrop(),
                    payload.scripts(), payload.selectedScript(), payload.alias());
        }
    }

    /** Registered as the ShopStatePayload handler in MicraDrone's RegisterPayloadHandlersEvent listener. */
    public static void handleShopState(ShopStatePayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof ShopScreen screen) {
            screen.updateShopState(payload.pos(), payload.unlockedCrops(), payload.pointsByCrop());
        }
    }

    /** Registered as the ScriptSourcePayload handler: loads the fetched source into the IDE's editor. */
    public static void handleScriptSource(ScriptSourcePayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof IdeScreen screen) {
            screen.updateSource(payload.pos(), payload.scriptName(), payload.source());
        }
    }

    /** Registered as the DebugStatePayload handler: drives the IDE's line highlight and debug buttons. */
    public static void handleDebugState(DebugStatePayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof IdeScreen screen) {
            screen.updateDebugState(payload.pos(), payload.state(), payload.currentLine(), payload.breakpoints());
        }
    }

}
