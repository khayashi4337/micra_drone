package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Standalone identity for a Corner Marker (林さんの「IDで管理」構想) - future-proofing so a marker can
 * be referenced beyond just "whatever's diagonally scanned from a controller" (e.g. a future
 * building/construction mode reading scripted references to specific markers), without the marker
 * itself needing to know which controller or system is using it. Two independent identifiers:
 * <ul>
 *   <li>{@link #sequenceNumber}: a small integer assigned once, the first time the marker is placed
 *       (see {@code CornerMarkerBlock#setPlacedBy}, which draws it from
 *       {@link CornerMarkerSequenceRegistry} - world-wide unique by construction, no collision check
 *       needed). Unlike the UUID this class used to carry, a plain number like "3" is something a
 *       player can actually read off the screen and type into a script (林さんの実機フィードバック:
 *       UUID断片は実用的でなかった). Round-trips through {@code DataComponents.CUSTOM_DATA} on the
 *       dropped item (see {@link #applyImplicitComponents}/{@link #collectImplicitComponents}), so
 *       breaking and replacing the SAME marker item keeps its number - only a genuinely new marker
 *       (crafted fresh, never placed before) gets a new one.</li>
 *   <li>{@link #friendlyName}: an optional player-chosen name, via the exact same anvil-rename-
 *       before-placing route {@code DroneControllerBlockEntity}'s alias uses (the item's
 *       CUSTOM_NAME data component lands here through {@link #applyImplicitComponents}). World-wide
 *       unique - enforced in {@code CornerMarkerBlock#setPlacedBy} via
 *       {@link CornerMarkerNameRegistry}, since this class alone has no way to see other markers.
 *       Clearing or editing the name: break the marker (it drops carrying its current name, see
 *       {@link #collectImplicitComponents}), rename the item in an anvil (blank clears it), place
 *       again.</li>
 * </ul>
 */
public class CornerMarkerBlockEntity extends BlockEntity {
    private static final String SEQUENCE_KEY = "MicradroneMarkerSequence";

    /** -1 = not yet assigned (a freshly crafted item that has never been placed before). */
    private int sequenceNumber = -1;
    private String friendlyName = "";

    public CornerMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(MicraDrone.CORNER_MARKER_BLOCK_ENTITY.get(), pos, state);
    }

    public String friendlyName() {
        return friendlyName;
    }

    /** Short, human-typeable form for scripts/chat: the friendly name if set, else the auto-assigned sequence number. */
    public String displayId() {
        return friendlyName.isEmpty() ? String.valueOf(sequenceNumber) : friendlyName;
    }

    /** True once this marker has been placed at least once and thus already owns a sequence number. */
    boolean hasSequenceNumber() {
        return sequenceNumber >= 1;
    }

    /** Called only by {@code CornerMarkerBlock#setPlacedBy}, exactly once per marker, the first time it's placed. */
    void assignSequenceNumber(int number) {
        sequenceNumber = number;
        setChanged();
    }

    /** Called only by {@code CornerMarkerBlock#setPlacedBy} when the name lost a world-wide uniqueness claim. */
    void clearFriendlyName() {
        friendlyName = "";
        setChanged();
    }

    /**
     * Lazily migrates a marker that reached the world without a sequence number: either a genuinely
     * corrupted state, or - the case actually hit - a 0.0.7-era marker saved back when this class
     * stored a UUID under a different NBT key ("Id", not "SequenceNumber"). {@link CompoundTag#getInt}
     * silently reads a missing key as 0, so {@link #loadAdditional} was leaving those markers stuck
     * showing id "0" forever, since {@code CornerMarkerBlock#setPlacedBy} (the only other place that
     * assigns one) never re-runs for a block that's already standing in the world. Called from every
     * path that actually reads a placed marker's id, so the fix applies the first time anyone looks
     * (seb's PR #20 round-2 review caught this).
     */
    public void ensureSequenceNumber(ServerLevel level) {
        if (!hasSequenceNumber()) {
            assignSequenceNumber(CornerMarkerSequenceRegistry.get(level).nextNumber());
        }
    }

    /**
     * Re-scans for the Corner Marker paired with {@code controllerPos} (same diagonal search
     * {@code DroneControllerBlockEntity#scanForCornerMarker} uses - always re-resolved rather than
     * trusting cached grid state, matching {@code ScriptChestLibrary}'s "stale position fails
     * loudly" idiom) and reads its {@link #displayId}. Empty string if no marker is currently
     * paired. Shared by {@code get_plot_id()} ({@code LiveFarmBlockAccess}) and the Shop screen
     * ({@code DroneControllerBlockEntity#sendShopStateTo}) so both show the exact same id.
     */
    public static String findDisplayId(ServerLevel level, BlockPos controllerPos) {
        return ScriptChestLibrary.findMarkerOffset(level, controllerPos)
                .flatMap(o -> at(level, controllerPos.offset(o[0], o[1], o[2])))
                .map(CornerMarkerBlockEntity::displayId)
                .orElse("");
    }

    private static Optional<CornerMarkerBlockEntity> at(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CornerMarkerBlockEntity be) {
            be.ensureSequenceNumber(level);
            return Optional.of(be);
        }
        return Optional.empty();
    }

    /**
     * The anvil-rename route (matches {@code DroneControllerBlockEntity}'s alias exactly): a marker
     * ITEM renamed in an anvil carries a CUSTOM_NAME component, which lands here when the block is
     * placed - the same mechanism vanilla chests use for their names. The sequence number rides
     * along on CUSTOM_DATA (no built-in component fits a bare int), so a marker that already has one
     * keeps it across break/pickup/replace.
     */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        Component name = componentInput.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            friendlyName = name.getString();
        }
        CustomData customData = componentInput.get(DataComponents.CUSTOM_DATA);
        if (customData != null && customData.contains(SEQUENCE_KEY)) {
            sequenceNumber = customData.copyTag().getInt(SEQUENCE_KEY);
        }
    }

    /** The reverse of {@link #applyImplicitComponents}, so breaking a marker carries its name and number back onto the dropped item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!friendlyName.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(friendlyName));
        }
        if (sequenceNumber >= 1) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SEQUENCE_KEY, sequenceNumber);
            components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        sequenceNumber = tag.getInt("SequenceNumber");
        friendlyName = tag.getString("FriendlyName");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("SequenceNumber", sequenceNumber);
        tag.putString("FriendlyName", friendlyName);
    }
}
