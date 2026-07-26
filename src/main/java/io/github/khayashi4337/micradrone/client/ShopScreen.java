package io.github.khayashi4337.micradrone.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.drone.UnlockShop;
import io.github.khayashi4337.micradrone.drone.net.PurchaseUnlockPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestShopStatePayload;
import io.github.khayashi4337.micradrone.drone.net.StopViewingPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opened either by right-clicking a Corner Marker, or from the IDE's Shop button (issue: shop entry
 * point consolidation - 林さん wanted a low-friction way in from the controller too, since visiting
 * the marker just for this felt like an extra trip). Spends a plot's points on new crop unlocks (see
 * {@link UnlockShop#CATALOG}). Either way this screen opens keyed by whatever position it was given
 * - a marker doesn't know its paired controller's position (resolved server-side via a reverse scan,
 * {@code DroneControllerBlockEntity#findByCornerMarker}), while a controller position resolves
 * directly - both are tried server-side (same dual-resolution idiom {@code handleStopViewing} already
 * used). The real controller position is learned from the first {@code ShopStatePayload} response and
 * used for every purchase afterward, so this distinction only matters for the opening request.
 * Client-only, so no logic here is unit-testable - see MicraDroneClient's note on manual verification.
 */
public class ShopScreen extends Screen {
    private static final int WIDTH = 240;

    /** Whatever position this screen was opened with - a corner marker OR a controller, see class doc. */
    private final BlockPos openPos;
    /** Learned from the server's first response; null (and purchases disabled) until then. */
    private BlockPos controllerPos;
    private Set<String> unlockedCrops = Set.of();
    private Map<String, Long> pointsByCrop = Map.of();
    private List<Component> pointsLines = List.of();
    private Component statusLine = Component.translatable("gui.micradrone.shop_screen.connecting");
    /** What get_plot_id() would return for this plot (林さんの実機フィードバック: マーカーを見ただけでは分からなかった). Empty until the first response, or if no marker is paired. */
    private String plotId = "";

    public ShopScreen(BlockPos openPos) {
        super(Component.translatable("gui.micradrone.shop_screen.title"));
        this.openPos = openPos;
    }

    @Override
    protected void init() {
        rebuild();
        PacketDistributor.sendToServer(new RequestShopStatePayload(openPos));
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
    public void updateShopState(BlockPos sourcePos, Set<String> unlockedCrops, Map<String, Long> pointsByCrop, String plotId) {
        if (controllerPos != null && !sourcePos.equals(controllerPos)) {
            return;
        }
        controllerPos = sourcePos;
        this.unlockedCrops = unlockedCrops;
        this.pointsByCrop = pointsByCrop;
        this.plotId = plotId;
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
        if (!plotId.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.micradrone.shop_screen.plot_id", plotId),
                    this.width / 2, y, 0xC0C0C0);
            y += 10;
        }
        for (Component line : pointsLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += 10;
        }
    }

    /**
     * Tells the server to stop pushing shop-state updates to us. Sends the same position this screen
     * opened with - the server resolves it back to the controller exactly as
     * {@code RequestShopStatePayload} did.
     */
    @Override
    public void removed() {
        PacketDistributor.sendToServer(new StopViewingPayload(openPos));
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
