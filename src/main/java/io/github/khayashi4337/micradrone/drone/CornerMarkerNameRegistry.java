package io.github.khayashi4337.micradrone.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.khayashi4337.micradrone.drone.CornerMarkerNameLedger.MarkerPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists a {@link CornerMarkerNameLedger} to the overworld's data storage - one world-wide
 * namespace for Corner Marker friendly names regardless of which dimension a marker sits in
 * (matches how vanilla's own map-id bookkeeping, e.g. {@code MapIndex}, anchors to the overworld
 * rather than per-dimension). {@code CornerMarkerBlock#setPlacedBy}/{@code #onRemove} are the only
 * callers - a marker's {@code BlockEntity} itself never touches this, matching 林さんの「マーカ側には
 * コントローラを認識しなくていい」stance generalized to "the marker doesn't know about the registry
 * either" - claiming/releasing is the block class's job, triggered by world events.
 */
public final class CornerMarkerNameRegistry extends SavedData {
    private static final String ID = "micradrone_corner_marker_names";

    private CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();

    public static SavedData.Factory<CornerMarkerNameRegistry> factory() {
        return new SavedData.Factory<>(CornerMarkerNameRegistry::new, CornerMarkerNameRegistry::load);
    }

    public static CornerMarkerNameRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), ID);
    }

    public boolean tryClaim(String name, BlockPos claimant) {
        boolean claimed = ledger.tryClaim(name, toMarkerPos(claimant));
        if (claimed) {
            setDirty();
        }
        return claimed;
    }

    public void release(String name, BlockPos owner) {
        ledger.release(name, toMarkerPos(owner));
        setDirty();
    }

    /** Where {@code name} currently points, if anyone has claimed it - see pair_with()/get_plot_id(). */
    public Optional<BlockPos> resolve(String name) {
        return ledger.ownerOf(name).map(CornerMarkerNameRegistry::toBlockPos);
    }

    private static MarkerPos toMarkerPos(BlockPos pos) {
        return new MarkerPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos toBlockPos(MarkerPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static CornerMarkerNameRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        CornerMarkerNameRegistry registry = new CornerMarkerNameRegistry();
        Map<String, MarkerPos> entries = new HashMap<>();
        CompoundTag names = tag.getCompound("Names");
        for (String name : names.getAllKeys()) {
            NbtUtils.readBlockPos(names, name).ifPresent(pos -> entries.put(name, toMarkerPos(pos)));
        }
        registry.ledger = CornerMarkerNameLedger.fromMap(entries);
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag names = new CompoundTag();
        ledger.asMap().forEach((name, pos) -> names.put(name, NbtUtils.writeBlockPos(toBlockPos(pos))));
        tag.put("Names", names);
        return tag;
    }
}
