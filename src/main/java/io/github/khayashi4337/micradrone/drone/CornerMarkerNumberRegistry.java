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
 * World-wide "auto-assigned sequence number (as a string) -&gt; position" index, the counterpart to
 * {@link CornerMarkerNameRegistry} for markers that were never given a friendly name. Needed for
 * pair_with(id)/set_output(id) to resolve ANY marker's {@link CornerMarkerBlockEntity#displayId} to a
 * position, not just named ones - a number alone (unlike a name) was never previously looked up
 * globally, only read back off the marker's own BlockEntity by whoever already stood next to it.
 * Reuses {@link CornerMarkerNameLedger} as-is (a plain string-&gt;position ledger with claim/release) -
 * a number is just a string key here, and unlike names, claims never fail (a sequence number is
 * unique by construction, see {@link CornerMarkerSequenceRegistry}), so there is no rename-conflict
 * case to report to the player.
 */
public final class CornerMarkerNumberRegistry extends SavedData {
    private static final String ID = "micradrone_corner_marker_numbers";

    private CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();

    public static SavedData.Factory<CornerMarkerNumberRegistry> factory() {
        return new SavedData.Factory<>(CornerMarkerNumberRegistry::new, CornerMarkerNumberRegistry::load);
    }

    public static CornerMarkerNumberRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), ID);
    }

    /** Claims {@code number} for {@code pos} - always succeeds (numbers are unique by construction). */
    public void claim(int number, BlockPos pos) {
        ledger.tryClaim(String.valueOf(number), toMarkerPos(pos));
        setDirty();
    }

    public void release(int number, BlockPos owner) {
        ledger.release(String.valueOf(number), toMarkerPos(owner));
        setDirty();
    }

    /** Where {@code number} currently points, if it has ever been assigned to a placed marker. */
    public Optional<BlockPos> resolve(int number) {
        return ledger.ownerOf(String.valueOf(number)).map(CornerMarkerNumberRegistry::toBlockPos);
    }

    private static MarkerPos toMarkerPos(BlockPos pos) {
        return new MarkerPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos toBlockPos(MarkerPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static CornerMarkerNumberRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        CornerMarkerNumberRegistry registry = new CornerMarkerNumberRegistry();
        Map<String, MarkerPos> entries = new HashMap<>();
        CompoundTag numbers = tag.getCompound("Numbers");
        for (String number : numbers.getAllKeys()) {
            NbtUtils.readBlockPos(numbers, number).ifPresent(pos -> entries.put(number, toMarkerPos(pos)));
        }
        registry.ledger = CornerMarkerNameLedger.fromMap(entries);
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag numbers = new CompoundTag();
        ledger.asMap().forEach((number, pos) -> numbers.put(number, NbtUtils.writeBlockPos(toBlockPos(pos))));
        tag.put("Numbers", numbers);
        return tag;
    }
}
