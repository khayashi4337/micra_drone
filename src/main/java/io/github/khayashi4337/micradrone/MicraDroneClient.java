package io.github.khayashi4337.micradrone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.khayashi4337.micradrone.client.CommandsHelpDoc;
import io.github.khayashi4337.micradrone.client.DroneScreen;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.AllayRenderer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;
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

    /** Called from DroneControllerBlock's client-side useWithoutItem branch. */
    public static void openDroneScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new DroneScreen(pos));
    }

    /** Registered as the DroneLogPayload handler in MicraDrone's RegisterPayloadHandlersEvent listener. */
    public static void handleDroneLog(DroneLogPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof DroneScreen screen) {
            screen.updateLog(payload.pos(), payload.lines());
        }
    }

    /** DroneScreen's "Open Scripts Folder" button: opens <world>/micradrone/scripts/ in the OS file browser. */
    public static void openScriptsFolder() {
        openMicradroneSubfolder("scripts");
    }

    /** DroneScreen's "Help" button: (re)writes the command reference doc, then opens its folder. */
    public static void openHelpFolder() {
        Path docsDir = micradroneSubfolder("docs");
        if (docsDir == null) {
            return;
        }
        try {
            Files.createDirectories(docsDir);
            Files.writeString(docsDir.resolve("commands.txt"), CommandsHelpDoc.CONTENT);
        } catch (IOException e) {
            MicraDrone.LOGGER.error("could not write commands.txt to {}", docsDir, e);
            return;
        }
        Util.getPlatform().openPath(docsDir);
    }

    private static void openMicradroneSubfolder(String name) {
        Path dir = micradroneSubfolder(name);
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MicraDrone.LOGGER.error("could not create {}", dir, e);
            return;
        }
        Util.getPlatform().openPath(dir);
    }

    /**
     * Resolves a subfolder under the current world's micradrone/ directory, or null if there is no
     * local world to resolve it against (e.g. connected to a remote multiplayer server - this MVP is
     * singleplayer/local-focused only, see the project's decision table).
     */
    private static Path micradroneSubfolder(String name) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return null;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("micradrone").resolve(name);
    }
}
