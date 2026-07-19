package io.github.khayashi4337.micradrone.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.drone.UnlockShop;
import io.github.khayashi4337.micradrone.drone.net.PurchaseUnlockPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestShopStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opened by right-clicking a Corner Marker. Spends a plot's points on new crop unlocks (see
 * {@link UnlockShop#CATALOG}). The marker itself doesn't know its paired controller's position -
 * that's resolved server-side (see DroneControllerBlockEntity#findByCornerMarker) - so this screen
 * opens keyed by the marker's position and learns the real controller position from the first
 * {@code ShopStatePayload} response, using that for every purchase afterward.
 * Client-only, so no logic here is unit-testable - see MicraDroneClient's note on manual verification.
 */
public class ShopScreen extends Screen {
    private static final int WIDTH = 240;

    private final BlockPos markerPos;
    /** Learned from the server's first response; null (and purchases disabled) until then. */
    private BlockPos controllerPos;
    private Set<String> unlockedCrops = Set.of();
    private Map<String, Long> pointsByCrop = Map.of();
    private List<Component> pointsLines = List.of();
    private Component statusLine = Component.translatable("gui.micradrone.shop_screen.connecting");

    public ShopScreen(BlockPos markerPos) {
        super(Component.translatable("gui.micradrone.shop_screen.title"));
        this.markerPos = markerPos;
    }

    @Override
    protected void init() {
        rebuild();
        PacketDistributor.sendToServer(new RequestShopStatePayload(markerPos));
    }

    private void rebuild() {
        clearWidgets();
        int left = (this.width - WIDTH) / 2;
        int y = 40;
        for (UnlockShop.Unlock unlock : UnlockShop.CATALOG) {
            boolean owned = unlockedCrops.contains(unlock.id());
            String costText = unlock.cost().entrySet().stream()
                    .map(e -> e.getValue() + " " + displayName(e.getKey()))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            Component label = owned
                    ? Component.translatable("gui.micradrone.shop_screen.owned", displayName(unlock.id()))
                    : Component.translatable("gui.micradrone.shop_screen.buy", displayName(unlock.id()), costText);
            addRenderableWidget(Button.builder(label, b -> buy(unlock.id()))
                    .bounds(left, y, WIDTH, 20)
                    .build());
            y += 24;
        }
    }

    private void buy(String unlockId) {
        if (controllerPos != null) {
            PacketDistributor.sendToServer(new PurchaseUnlockPayload(controllerPos, unlockId));
        }
    }

    private static String displayName(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Called from {@code MicraDroneClient} when a ShopStatePayload arrives. The first response (while
     * controllerPos is still null) is accepted unconditionally and its source position remembered as
     * the resolved controller - this screen only ever has one outstanding request in flight for
     * itself, so there's no ambiguity about what an incoming reply refers to.
     */
    public void updateShopState(BlockPos sourcePos, Set<String> unlockedCrops, Map<String, Long> pointsByCrop) {
        if (controllerPos != null && !sourcePos.equals(controllerPos)) {
            return;
        }
        controllerPos = sourcePos;
        this.unlockedCrops = unlockedCrops;
        this.pointsByCrop = pointsByCrop;
        statusLine = Component.empty();

        pointsLines = new TreeMap<>(pointsByCrop).entrySet().stream()
                .map(e -> (Component) Component.literal(displayName(e.getKey()) + ": " + e.getValue()))
                .toList();

        rebuild();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int y = 4;
        if (!statusLine.getString().isEmpty()) {
            guiGraphics.drawCenteredString(this.font, statusLine, this.width / 2, y, 0xFFFFFF);
            y += 10;
        }
        for (Component line : pointsLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
