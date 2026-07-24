package io.github.khayashi4337.micradrone;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.khayashi4337.micradrone.drone.CornerMarkerBlock;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlock;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import io.github.khayashi4337.micradrone.drone.GiantPumpkinBlock;
import io.github.khayashi4337.micradrone.drone.ScriptScrollContent;
import io.github.khayashi4337.micradrone.drone.ScriptScrollItem;
import io.github.khayashi4337.micradrone.drone.ScrollEnchanter;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.EjectScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.EnchantScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.FillScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.PurchaseUnlockPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RequestShopStatePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.RunScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.SetBreakpointsPayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
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
    // doubles as the unlock shop's entry point (right-click, see CornerMarkerBlock). No BlockEntity:
    // the controller scans the 4 diagonals for it (and vice versa for the shop), see
    // DroneControllerBlockEntity#scanForCornerMarker/#findByCornerMarker.
    public static final DeferredBlock<CornerMarkerBlock> CORNER_MARKER_BLOCK = BLOCKS.registerBlock(
            "corner_marker", CornerMarkerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0f));
    public static final DeferredItem<BlockItem> CORNER_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem("corner_marker", CORNER_MARKER_BLOCK);

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
        registrar.playToServer(FillScrollPayload.TYPE, FillScrollPayload.STREAM_CODEC, MicraDrone::handleFillScroll);
        registrar.playToServer(RunScrollPayload.TYPE, RunScrollPayload.STREAM_CODEC, MicraDrone::handleRunScroll);
        registrar.playToServer(EjectScrollPayload.TYPE, EjectScrollPayload.STREAM_CODEC, MicraDrone::handleEjectScroll);
        registrar.playToServer(EnchantScrollPayload.TYPE, EnchantScrollPayload.STREAM_CODEC, MicraDrone::handleEnchantScroll);
        registrar.playToServer(RequestScriptSourcePayload.TYPE, RequestScriptSourcePayload.STREAM_CODEC, MicraDrone::handleRequestScriptSource);
        registrar.playToServer(SaveScriptPayload.TYPE, SaveScriptPayload.STREAM_CODEC, MicraDrone::handleSaveScript);
        registrar.playToServer(SetBreakpointsPayload.TYPE, SetBreakpointsPayload.STREAM_CODEC, MicraDrone::handleSetBreakpoints);
        registrar.playToServer(DebugCommandPayload.TYPE, DebugCommandPayload.STREAM_CODEC, MicraDrone::handleDebugCommand);
        registrar.playToClient(DroneLogPayload.TYPE, DroneLogPayload.STREAM_CODEC, MicraDroneClient::handleDroneLog);
        registrar.playToClient(ShopStatePayload.TYPE, ShopStatePayload.STREAM_CODEC, MicraDroneClient::handleShopState);
        registrar.playToClient(ScriptSourcePayload.TYPE, ScriptSourcePayload.STREAM_CODEC, MicraDroneClient::handleScriptSource);
        registrar.playToClient(DebugStatePayload.TYPE, DebugStatePayload.STREAM_CODEC, MicraDroneClient::handleDebugState);
    }

    // Payload handlers run on the main thread by default (PayloadRegistrar), so it's safe to touch
    // the BlockEntity directly here.
    private static void handleRunScript(RunScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.startScript(serverPlayer, payload.scriptName());
        }
    }

    private static void handleStopScript(StopScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.stopScript(serverPlayer);
        }
    }

    private static void handleRequestLog(RequestLogPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.sendLogSnapshotTo(serverPlayer);
        }
    }

    private static void handlePurchaseUnlock(PurchaseUnlockPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.purchaseUnlock(serverPlayer, payload.unlockId());
        }
    }

    // payload.pos() here is the corner marker the player right-clicked, not a controller - see
    // DroneControllerBlockEntity#findByCornerMarker for the reverse-scan that resolves it.
    private static void handleRequestShopState(RequestShopStatePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            DroneControllerBlockEntity.findByCornerMarker(serverPlayer.level(), payload.pos())
                    .ifPresent(be -> be.sendShopStateTo(serverPlayer));
        }
    }

    // Sent by DroneScreen's "Copy Script -> Scroll" button (GitHub issue #1): writes scriptName's
    // source onto the sender's held scroll. Always targets the main hand - see that button's design
    // note on why this no longer offers an off-hand option. Reports failures to the player's chat
    // (purchaseUnlock's existing pattern) since a silent no-op is exactly what caused the confusion
    // that led to this redesign.
    private static void handleFillScroll(FillScrollPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack stack = serverPlayer.getMainHandItem();
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            serverPlayer.sendSystemMessage(Component.literal("[scroll] hold a script scroll in your main hand first"));
            return;
        }
        if (!(serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be)) {
            return;
        }
        be.loadScriptSource(payload.scriptName()).ifPresent(source -> {
            List<Filterable<String>> pages = ScriptScrollContent.splitIntoPages(source, WritableBookContent.PAGE_EDIT_LENGTH)
                    .stream().map(Filterable::passThrough).toList();
            stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
            serverPlayer.sendSystemMessage(Component.literal("[scroll] copied '" + payload.scriptName() + "' onto your scroll"));
        });
    }

    // IdeScreen opening (issue #6): fetch the selected script's source for the editor.
    private static void handleRequestScriptSource(RequestScriptSourcePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.sendScriptSource(serverPlayer, payload.scriptName());
        }
    }

    // IdeScreen's Save button (issue #6): write the edited source back to the script folder.
    private static void handleSaveScript(SaveScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.saveScript(serverPlayer, payload.scriptName(), payload.source());
        }
    }

    // Controller slot (issue #7): DroneScreen's Eject button pops the slotted scroll back out.
    private static void handleEjectScroll(EjectScrollPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.ejectScroll(serverPlayer);
        }
    }

    // Enchanting-table inscription (issue #8): re-validates and writes a catalog sample onto the
    // sender's blank scroll - all real logic lives in ScrollEnchanter.
    private static void handleEnchantScroll(EnchantScrollPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            ScrollEnchanter.enchant(serverPlayer, payload.tablePos(), payload.sampleIndex());
        }
    }

    // IDE debugger (issue #6): gutter clicks replace the whole breakpoint set.
    private static void handleSetBreakpoints(SetBreakpointsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.setBreakpoints(serverPlayer, Set.copyOf(payload.lines()));
        }
    }

    // IDE debugger (issue #6): Pause/Resume/Step/Step Out buttons.
    private static void handleDebugCommand(DebugCommandPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.debugCommand(serverPlayer, payload.command());
        }
    }

    // Sent by DroneScreen's "Run Scroll" button (GitHub issue #1): joins the held scroll's pages and
    // runs them on the controller at payload.pos(), same as picking a saved script and hitting Run.
    private static void handleRunScroll(RunScrollPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack stack = serverPlayer.getMainHandItem();
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            serverPlayer.sendSystemMessage(Component.literal("[scroll] hold a script scroll in your main hand first"));
            return;
        }
        WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<String> pages = content.pages().stream().map(Filterable::raw).toList();
        if (ScriptScrollContent.isBlank(pages)) {
            serverPlayer.sendSystemMessage(Component.literal("[scroll] your scroll is blank - write something on it first"));
            return;
        }
        if (!(serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be)) {
            return;
        }
        be.applyScroll(serverPlayer, ScriptScrollContent.joinPages(pages));
    }
}
