package io.github.khayashi4337.micradrone.client;

import java.util.Map;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.drone.net.FillScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opened by right-clicking a Drone Controller while holding a blank {@code ScriptScrollItem} (see
 * that class): picks one of the controller's saved scripts (samples included, since a fresh
 * controller's folder is auto-seeded with them) to write onto the scroll, so testing or carrying a
 * known-good script doesn't require typing one by hand in the book-and-quill GUI first (GitHub
 * issue #1). Modeled on {@code ShopScreen}'s plain-button-list layout - the list is expected to stay
 * short, so no scrolling widget is needed.
 * Client-only, so no logic here is unit-testable - see MicraDroneClient's note on manual verification.
 */
public class ScrollPickScreen extends Screen {
    private static final int WIDTH = 240;

    private final BlockPos controllerPos;
    /** Which hand the scroll was held in when this screen was opened - see {@code FillScrollPayload}. */
    private final boolean mainHand;
    private Map<String, String> scriptDescriptions = Map.of();

    public ScrollPickScreen(BlockPos controllerPos, boolean mainHand) {
        super(Component.translatable("gui.micradrone.scroll_pick_screen.title"));
        this.controllerPos = controllerPos;
        this.mainHand = mainHand;
    }

    @Override
    protected void init() {
        rebuild();
        PacketDistributor.sendToServer(new RequestLogPayload(controllerPos));
    }

    private void rebuild() {
        clearWidgets();
        int left = (this.width - WIDTH) / 2;
        int y = 40;
        for (String scriptName : new TreeMap<>(scriptDescriptions).keySet()) {
            addRenderableWidget(Button.builder(Component.literal(scriptName), b -> pick(scriptName))
                    .bounds(left, y, WIDTH, 20)
                    .build());
            y += 24;
        }
    }

    private void pick(String scriptName) {
        PacketDistributor.sendToServer(new FillScrollPayload(controllerPos, mainHand, scriptName));
        onClose();
    }

    /** Called from {@code MicraDroneClient} when a DroneLogPayload arrives for this controller. */
    public void updateScriptList(BlockPos sourcePos, Map<String, String> scriptDescriptions) {
        if (!sourcePos.equals(controllerPos) || scriptDescriptions.isEmpty()) {
            return;
        }
        this.scriptDescriptions = scriptDescriptions;
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
