package io.github.khayashi4337.micradrone;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.khayashi4337.micradrone.drone.CornerMarkerBlock;
import io.github.khayashi4337.micradrone.drone.CornerMarkerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlock;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import io.github.khayashi4337.micradrone.drone.GiantPumpkinBlock;
import io.github.khayashi4337.micradrone.drone.ScriptScrollItem;
import io.github.khayashi4337.micradrone.drone.ScrollEnchanter;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.EnchantScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.PurchaseUnlockPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RequestShopStatePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.SelectScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SetBreakpointsPayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.StopViewingPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(MicraDrone.MODID)
public class MicraDrone {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "micradrone";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "micradrone" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "micradrone" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "micradrone" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // Create a Deferred Register to hold BlockEntityTypes which will all be registered under the "micradrone" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredBlock<DroneControllerBlock> DRONE_CONTROLLER_BLOCK = BLOCKS.registerBlock(
            "drone_controller", DroneControllerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f));
    public static final DeferredItem<BlockItem> DRONE_CONTROLLER_ITEM =
            ITEMS.registerSimpleBlockItem("drone_controller", DRONE_CONTROLLER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DroneControllerBlockEntity>> DRONE_CONTROLLER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("drone_controller", () -> BlockEntityType.Builder.of(
                    DroneControllerBlockEntity::new, DRONE_CONTROLLER_BLOCK.get()).build(null));

    // Placed at the opposite diagonal corner from a drone_controller to size its (square) plot, and
    // doubles as an unlock shop entry point (right-click, see CornerMarkerBlock). The controller
    // still scans the 4 diagonals for it (and vice versa for the shop), see
    // DroneControllerBlockEntity#scanForCornerMarker/#findByCornerMarker - the BlockEntity below is
    // only for the marker's own standalone id/friendly-name (CornerMarkerBlockEntity), not a
    // controller binding.
    public static final DeferredBlock<CornerMarkerBlock> CORNER_MARKER_BLOCK = BLOCKS.registerBlock(
            "corner_marker", CornerMarkerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0f));
    public static final DeferredItem<BlockItem> CORNER_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem("corner_marker", CORNER_MARKER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CornerMarkerBlockEntity>> CORNER_MARKER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("corner_marker", () -> BlockEntityType.Builder.of(
                    CornerMarkerBlockEntity::new, CORNER_MARKER_BLOCK.get()).build(null));

    // A portable, freely-rewritable script carrier (GitHub issue #1) - see ScriptScrollItem. Stacks
    // to 1, matching vanilla's own WritableBookItem (Items.WRITABLE_BOOK).
    public static final DeferredItem<ScriptScrollItem> SCRIPT_SCROLL_ITEM =
            ITEMS.registerItem("script_scroll", ScriptScrollItem::new, new Item.Properties().stacksTo(1));

    // Purely decorative reskin for a giant-pumpkin fusion patch (see LiveFarmBlockAccess). The mod
    // places/clears it itself; no BlockItem/recipe, players never obtain it directly.
    public static final DeferredBlock<GiantPumpkinBlock> GIANT_PUMPKIN_BLOCK = BLOCKS.registerBlock(
            "giant_pumpkin", GiantPumpkinBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.0f));

    // Stands in for a pumpkin that grew defective (~20% chance, matching the original game - see
    // LiveFarmBlockAccess). Plain block like CORNER_MARKER_BLOCK: no custom class needed. The mod
    // places/clears it itself; no BlockItem/recipe.
    public static final DeferredBlock<net.minecraft.world.level.block.Block> ROTTEN_PUMPKIN_BLOCK =
            BLOCKS.registerSimpleBlock("rotten_pumpkin", BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(1.0f));

    // Create a Deferred Register to hold EntityTypes which will all be registered under the "micradrone" namespace
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    // Visible drone entity: an Allay subclass with all its AI stripped, see DroneEntity. Same size as
    // vanilla Allay (its model/renderer are reused as-is) and MISC category (not a wild spawnable creature).
    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE_ENTITY = ENTITY_TYPES.register(
            "drone", () -> EntityType.Builder.of(DroneEntity::new, MobCategory.MISC)
                    .sized(0.35f, 0.6f)
                    .eyeHeight(0.36f)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("drone"));

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public MicraDrone(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Registers to the mod event bus
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);

        // Add the drone controller to the vanilla "Functional Blocks" creative tab
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerAttributes);
        modEventBus.addListener(this::registerPayloadHandlers);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("MicraDrone: common setup complete");
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // DroneEntity is a plain Allay subclass (see DroneEntity), so it needs the same attributes.
        event.put(DRONE_ENTITY.get(), Allay.createAttributes().build());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(DRONE_CONTROLLER_ITEM);
            event.accept(CORNER_MARKER_ITEM);
            event.accept(SCRIPT_SCROLL_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("MicraDrone: server starting");
    }

    private void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RunScriptPayload.TYPE, RunScriptPayload.STREAM_CODEC, MicraDrone::handleRunScript);
        registrar.playToServer(StopScriptPayload.TYPE, StopScriptPayload.STREAM_CODEC, MicraDrone::handleStopScript);
        registrar.playToServer(RequestLogPayload.TYPE, RequestLogPayload.STREAM_CODEC, MicraDrone::handleRequestLog);
        registrar.playToServer(PurchaseUnlockPayload.TYPE, PurchaseUnlockPayload.STREAM_CODEC, MicraDrone::handlePurchaseUnlock);
        registrar.playToServer(RequestShopStatePayload.TYPE, RequestShopStatePayload.STREAM_CODEC, MicraDrone::handleRequestShopState);
        registrar.playToServer(EnchantScrollPayload.TYPE, EnchantScrollPayload.STREAM_CODEC, MicraDrone::handleEnchantScroll);
        registrar.playToServer(RequestScriptSourcePayload.TYPE, RequestScriptSourcePayload.STREAM_CODEC, MicraDrone::handleRequestScriptSource);
        registrar.playToServer(SaveScriptPayload.TYPE, SaveScriptPayload.STREAM_CODEC, MicraDrone::handleSaveScript);
        registrar.playToServer(SelectScriptPayload.TYPE, SelectScriptPayload.STREAM_CODEC, MicraDrone::handleSelectScript);
        registrar.playToServer(SetBreakpointsPayload.TYPE, SetBreakpointsPayload.STREAM_CODEC, MicraDrone::handleSetBreakpoints);
        registrar.playToServer(DebugCommandPayload.TYPE, DebugCommandPayload.STREAM_CODEC, MicraDrone::handleDebugCommand);
        registrar.playToServer(StopViewingPayload.TYPE, StopViewingPayload.STREAM_CODEC, MicraDrone::handleStopViewing);
        registrar.playToClient(DroneLogPayload.TYPE, DroneLogPayload.STREAM_CODEC, MicraDroneClient::handleDroneLog);
        registrar.playToClient(ShopStatePayload.TYPE, ShopStatePayload.STREAM_CODEC, MicraDroneClient::handleShopState);
        registrar.playToClient(ScriptSourcePayload.TYPE, ScriptSourcePayload.STREAM_CODEC, MicraDroneClient::handleScriptSource);
        registrar.playToClient(DebugStatePayload.TYPE, DebugStatePayload.STREAM_CODEC, MicraDroneClient::handleDebugState);
    }

    /**
     * How far (squared) a player may act on a controller, corner marker, or enchanting table
     * through one of this mod's payloads. Every payload below carries a client-chosen BlockPos, and
     * on a dedicated server nothing about that position is trustworthy - without this check a
     * modified client could run scripts on, spend the points of, or eject the scroll from any
     * controller in the world it knew the coordinates of. 12 blocks is well past vanilla's own ~4.5
     * block reach on purpose: a screen stays open across a piston push or a teleport, so the bound
     * only has to be tight enough to keep a player near the block they opened.
     */
    private static final double MAX_INTERACTION_DISTANCE_SQ = 144.0;

    /** True if {@code player} is close enough to legitimately be acting on {@code pos}. */
    private static boolean isInReach(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                <= MAX_INTERACTION_DISTANCE_SQ;
    }

    // Payload handlers run on the main thread by default (PayloadRegistrar), so it's safe to touch
    // the BlockEntity directly here. They all resolve the block through the SENDER's own level, so
    // a payload can never reach into another dimension; isInReach bounds it within that level.
    private static void handleRunScript(RunScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.startScript(serverPlayer, payload.scriptName());
        }
    }

    private static void handleStopScript(StopScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.stopScript(serverPlayer);
        }
    }

    private static void handleRequestLog(RequestLogPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.sendLogSnapshotTo(serverPlayer);
        }
    }

    private static void handlePurchaseUnlock(PurchaseUnlockPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.purchaseUnlock(serverPlayer, payload.unlockId());
        }
    }

    // payload.pos() is either a controller (opened via the IDE's Shop button) or a corner marker
    // the player right-clicked directly - same dual-resolution idiom as handleStopViewing below. The
    // reach check is against whichever position was sent; a marker's controller is legitimately up
    // to MAX_MARKER_SCAN_DISTANCE further away, same allowance findByCornerMarker itself uses.
    private static void handleRequestShopState(RequestShopStatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer) || !isInReach(serverPlayer, payload.pos())) {
            return;
        }
        Level level = serverPlayer.level();
        Optional<DroneControllerBlockEntity> target =
                level.getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be
                        ? Optional.of(be)
                        : DroneControllerBlockEntity.findByCornerMarker(level, payload.pos());
        // If the player opened this by right-clicking a specific marker directly, show THAT marker's
        // own id - not whichever marker the resolved controller happens to be paired with right now.
        // Those can differ (a second, not-yet-paired marker's reverse scan can resolve back to a
        // DIFFERENT controller's own paired marker) - real-machine report: a freshly placed second
        // marker showed the first marker's id because of exactly this mismatch.
        String clickedMarkerId = level.getBlockEntity(payload.pos()) instanceof CornerMarkerBlockEntity marker
                ? marker.displayId()
                : null;
        // Registered as a viewer so someone else's purchase updates this Shop screen too.
        target.ifPresent(be -> {
            be.addViewer(serverPlayer);
            be.sendShopStateTo(serverPlayer, clickedMarkerId);
        });
    }

    // Any controller screen closing: stop pushing that controller's updates to this player. The
    // Shop screen sends its corner marker's position, so both lookups are tried. Deliberately the
    // one handler with no reach check: unregistering a viewer only ever removes the sender's own
    // subscription, and refusing it because they drifted out of range would leak that subscription
    // for the rest of the session - exactly what this payload exists to prevent.
    private static void handleStopViewing(StopViewingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.removeViewer(serverPlayer);
            return;
        }
        DroneControllerBlockEntity.findByCornerMarker(serverPlayer.level(), payload.pos())
                .ifPresent(be -> be.removeViewer(serverPlayer));
    }

    // IdeScreen opening (issue #6): fetch the selected script's source for the editor.
    private static void handleRequestScriptSource(RequestScriptSourcePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.sendScriptSource(serverPlayer, payload.scriptName());
        }
    }

    // IdeScreen's Save button (issue #6): write the edited source back to the script folder.
    private static void handleSaveScript(SaveScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.saveScript(serverPlayer, payload.scriptName(), payload.source());
        }
    }

    // IDE's script list (GUI reduction follow-up): clicking an entry just selects it.
    private static void handleSelectScript(SelectScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.selectScript(serverPlayer, payload.scriptId());
        }
    }

    // Enchanting-table inscription (issue #8): re-validates and writes a catalog sample onto the
    // sender's blank scroll - all real logic lives in ScrollEnchanter.
    private static void handleEnchantScroll(EnchantScrollPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer && isInReach(serverPlayer, payload.tablePos())) {
            ScrollEnchanter.enchant(serverPlayer, payload.tablePos(), payload.sampleIndex(),
                    payload.bookshelfOffsetIndex(), payload.copySlot());
        }
    }

    // IDE debugger (issue #6): gutter clicks replace the whole breakpoint set.
    private static void handleSetBreakpoints(SetBreakpointsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.setBreakpoints(serverPlayer, Set.copyOf(payload.lines()));
        }
    }

    // IDE debugger (issue #6): Pause/Resume/Step/Step Out buttons.
    private static void handleDebugCommand(DebugCommandPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && isInReach(serverPlayer, payload.pos())
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.debugCommand(serverPlayer, payload.command());
        }
    }
}
