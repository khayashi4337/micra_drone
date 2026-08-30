package io.github.khayashi4337.micradrone;

import io.github.khayashi4337.micradrone.client.DroneModel;
import io.github.khayashi4337.micradrone.client.DroneRenderer;
import io.github.khayashi4337.micradrone.client.EnchantScrollScreen;
import io.github.khayashi4337.micradrone.client.EnchantTableWatcher;
import io.github.khayashi4337.micradrone.client.IdeScreen;
import io.github.khayashi4337.micradrone.client.RegionPointerListener;
import io.github.khayashi4337.micradrone.client.RegionSelectionRenderer;
import io.github.khayashi4337.micradrone.client.ShopScreen;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MicraDrone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MicraDrone.MODID, value = Dist.CLIENT)
public class MicraDroneClient {
    /** Registered in {@link #registerLayerDefinitions}, baked in {@link DroneRenderer}'s constructor. */
    public static final ModelLayerLocation DRONE_MODEL_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "drone"), "main");

    public MicraDroneClient() {
        // A separate instance, not `this` - this class's own static @SubscribeEvent methods below
        // (registered to the FML mod bus via the class-level @EventBusSubscriber above) make
        // NeoForge.EVENT_BUS.register(this) reject the whole class outright at mod-construction time
        // ("Expected @SubscribeEvent method ... to NOT be static") - confirmed by a real-machine
        // crash the first time this was tried directly here. See EnchantTableWatcher's own javadoc.
        NeoForge.EVENT_BUS.register(new EnchantTableWatcher());
        NeoForge.EVENT_BUS.register(new RegionPointerListener());
        NeoForge.EVENT_BUS.register(new RegionSelectionRenderer());
        // The unsaved-draft cache's key carries no save/server identity, so it must not outlive the
        // world it was written in - why, and what the key is made of, is documented once, on
        // IdeScreen#unsavedDrafts. Logged (not just silently done) because this is the one piece of
        // this whole feature no JUnit test can reach - a real-machine check needs a visible trace
        // that this actually fired, not just that clearUnsavedDrafts() is correct in isolation.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            IdeScreen.clearUnsavedDrafts();
            MicraDrone.LOGGER.info("MicraDrone: cleared unsaved IDE drafts on world/server logout");
        });
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
     * Called from DroneControllerBlock's client-side useWithoutItem branch: the IDE is the
     * controller's only screen (GUI reduction follow-up dissolved the separate list/log screen
     * into the IDE's own "List" toggle). The jukebox-style item slot is gone too - there's no
     * fixed id to open on anymore, so this opens unresolved; {@code IdeScreen#updateLog} resolves
     * it to the server's current selection the first time a DroneLogPayload arrives.
     */
    public static void openIdeScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new IdeScreen(pos, "",
                Component.translatable("gui.micradrone.ide_screen.loading").getString()));
    }

    /** Called from CornerMarkerBlock's client-side useWithoutItem branch. pos is the marker, not a controller. */
    public static void openShopScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ShopScreen(pos));
    }

    /** Called from {@link EnchantTableWatcher} once a blank scroll lands in the enchanting table's item slot. */
    public static void openEnchantScrollScreen(BlockPos tablePos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof EnchantmentMenu menu) {
            minecraft.setScreen(new EnchantScrollScreen(tablePos, menu, minecraft.player.getInventory()));
        }
    }

    /** Registered as the DroneLogPayload handler in MicraDrone's RegisterPayloadHandlersEvent listener. */
    public static void handleDroneLog(DroneLogPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof IdeScreen screen) {
            screen.updateLog(payload.pos(), payload.lines(), payload.pointsByCrop(), payload.unlockedCrops(),
                    payload.scripts(), payload.selectedScript(), payload.alias());
        }
    }

    /** Registered as the ShopStatePayload handler in MicraDrone's RegisterPayloadHandlersEvent listener. */
    public static void handleShopState(ShopStatePayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof ShopScreen screen) {
            screen.updateShopState(payload.pos(), payload.unlockedCrops(), payload.pointsByCrop(), payload.plotId());
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
            screen.updateDebugState(payload.pos(), payload.state(), payload.currentLine(), payload.breakpoints(),
                    payload.breakpointRevision());
        }
    }

}
