package io.github.khayashi4337.micradrone.drone;

import java.util.UUID;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Standalone identity for a Corner Marker (林さんの「IDで管理」構想) - future-proofing so a marker can
 * be referenced beyond just "whatever's diagonally scanned from a controller" (e.g. a future
 * building/construction mode reading scripted references to specific markers), without the marker
 * itself needing to know which controller or system is using it. Two independent identifiers:
 * <ul>
 *   <li>{@link #id}: a UUID assigned fresh on every placement (constructor default), overwritten by
 *       {@link #loadAdditional} when this is actually a previously-placed marker being loaded from
 *       disk. Deliberately NOT preserved across break/pickup - nothing depends on long-term UUID
 *       stability yet, so each placement is simply a new identity.</li>
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
    private UUID id = UUID.randomUUID();
    private String friendlyName = "";

    public CornerMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(MicraDrone.CORNER_MARKER_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID id() {
        return id;
    }

    public String friendlyName() {
        return friendlyName;
    }

    /** Short, human-typeable form for scripts/chat: the friendly name if set, else the id's first 8 hex chars. */
    public String displayId() {
        return friendlyName.isEmpty() ? id.toString().substring(0, 8) : friendlyName;
    }

    /** Called only by {@code CornerMarkerBlock#setPlacedBy} when the name lost a world-wide uniqueness claim. */
    void clearFriendlyName() {
        friendlyName = "";
        setChanged();
    }

    /**
     * The anvil-rename route (matches {@code DroneControllerBlockEntity}'s alias exactly): a marker
     * ITEM renamed in an anvil carries a CUSTOM_NAME component, which lands here when the block is
     * placed - the same mechanism vanilla chests use for their names.
     */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        Component name = componentInput.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            friendlyName = name.getString();
        }
    }

    /** The reverse of {@link #applyImplicitComponents}, so breaking a named marker carries the name back onto the dropped item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!friendlyName.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(friendlyName));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("Id")) {
            id = tag.getUUID("Id");
        }
        friendlyName = tag.getString("FriendlyName");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("Id", id);
        tag.putString("FriendlyName", friendlyName);
    }
}
