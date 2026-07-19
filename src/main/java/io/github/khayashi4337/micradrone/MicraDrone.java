package io.github.khayashi4337.micradrone;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.khayashi4337.micradrone.drone.DroneControllerBlock;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
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

    // Placed at the opposite diagonal corner from a drone_controller to size its (square) plot.
    // Plain block, no BlockEntity: the controller scans the 4 diagonals for it, see
    // DroneControllerBlockEntity#scanForCornerMarker.
    public static final DeferredBlock<net.minecraft.world.level.block.Block> CORNER_MARKER_BLOCK =
            BLOCKS.registerSimpleBlock("corner_marker", BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0f));
    public static final DeferredItem<BlockItem> CORNER_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem("corner_marker", CORNER_MARKER_BLOCK);

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
        registrar.playToClient(DroneLogPayload.TYPE, DroneLogPayload.STREAM_CODEC, MicraDroneClient::handleDroneLog);
    }

    // Payload handlers run on the main thread by default (PayloadRegistrar), so it's safe to touch
    // the BlockEntity directly here.
    private static void handleRunScript(RunScriptPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer
                && serverPlayer.level().getBlockEntity(payload.pos()) instanceof DroneControllerBlockEntity be) {
            be.startScript(serverPlayer);
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
}
