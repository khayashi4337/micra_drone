package io.github.khayashi4337.micradrone;

import io.github.khayashi4337.micradrone.client.DroneModel;
import io.github.khayashi4337.micradrone.client.DroneRenderer;
import io.github.khayashi4337.micradrone.client.DroneScreen;
import io.github.khayashi4337.micradrone.client.EnchantScrollScreen;
import io.github.khayashi4337.micradrone.client.IdeScreen;
import io.github.khayashi4337.micradrone.client.ShopScreen;
import io.github.khayashi4337.micradrone.drone.ScriptId;
import io.github.khayashi4337.micradrone.drone.ScriptScrollItem;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MicraDrone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MicraDrone.MODID, value = Dist.CLIENT)
public class MicraDroneClient {
    /** Registered in {@link #registerLayerDefinitions}, baked in {@link DroneRenderer}'s constructor. */
    public static final ModelLayerLocation DRONE_MODEL_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "drone"), "main");

    /**
     * The enchanting table this player most recently right-clicked (issue #8), remembered just long
     * enough to know which table a subsequently-opened {@link EnchantmentMenu} belongs to - the menu
     * itself doesn't expose its block position. Single field: only one local player exists client-side.
     */
    private static BlockPos pendingEnchantTablePos;

    public MicraDroneClient() {
        // NeoForge.EVENT_BUS (game events, e.g. PlayerInteractEvent/PlayerContainerEvent below) is
        // separate from the mod bus the class-level @EventBusSubscriber above wires up (FML
        // lifecycle/client-setup events) - same split MicraDrone itself uses for its own game-event
        // handlers, see its constructor.
        NeoForge.EVENT_BUS.register(this);
    }

    /**
     * Remembers which enchanting table the player is right-clicking (issue #8) so the
     * {@link #onContainerOpen} handler below knows which table the vanilla menu that opens right
     * after belongs to - vanilla's own click handling is left completely alone here (no
     * cancellation): the player drags their blank scroll into the table's own item slot exactly
     * like any other enchant-table use, matching how the table already works instead of requiring a
     * separate hold-then-click gesture (real-machine feedback: the latter didn't match what players
     * expect from an enchanting table at all).
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(Blocks.ENCHANTING_TABLE)) {
            pendingEnchantTablePos = event.getPos();
        }
    }

    /**
     * The actual trigger (issue #8): when the enchanting menu that just opened is the one at
     * {@link #pendingEnchantTablePos}, watch its item slot (slot 0, {@link EnchantmentMenu}'s
     * layout) for a blank script scroll landing in it - the moment a player drops one in, same as
     * dropping in any other enchant target, opens the sample picker. A one-shot listener: it
     * unregisters itself the instant it fires, so it neither re-opens the picker on every later slot
     * change nor lingers once its job is done.
     */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        BlockPos tablePos = pendingEnchantTablePos;
        if (tablePos == null || !(event.getContainer() instanceof EnchantmentMenu menu)) {
            return;
        }
        menu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu changedMenu, int slotIndex, ItemStack stack) {
                if (slotIndex == 0 && ScriptScrollItem.isBlank(stack)) {
                    changedMenu.removeSlotListener(this);
                    openEnchantScrollScreen(tablePos);
                }
            }

            @Override
            public void dataChanged(AbstractContainerMenu changedMenu, int dataSlotIndex, int value) {
            }
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

    /** Called from {@link #onContainerOpen} once a blank scroll lands in the enchanting table's item slot. */
    public static void openEnchantScrollScreen(BlockPos tablePos) {
        Minecraft.getInstance().setScreen(new EnchantScrollScreen(tablePos));
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
