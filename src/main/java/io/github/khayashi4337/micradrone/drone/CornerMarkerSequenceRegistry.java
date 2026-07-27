package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Hands out sequential, world-wide-unique numbers to newly-placed Corner Markers (林さんの実機
 * フィードバック: 自動生成IDがUUID断片だとスクリプトに書き写すのに実用的でない - "1", "2", "3" のような
 * 連番の方が読み書きしやすい). Mirrors vanilla's own map-id counter
 * ({@code net.minecraft.world.level.saveddata.maps.MapIndex#getFreeAuxValueForMap}, decompiled and
 * confirmed) - a simple incrementing counter anchored to the overworld's data storage, same idiom
 * {@link CornerMarkerNameRegistry} already uses for name uniqueness.
 */
public final class CornerMarkerSequenceRegistry extends SavedData {
    private static final String ID = "micradrone_corner_marker_sequence";

    /** Numbers start at 1 - more natural for a player to see/type than 0. */
    private int nextNumber = 1;

    public static SavedData.Factory<CornerMarkerSequenceRegistry> factory() {
        return new SavedData.Factory<>(CornerMarkerSequenceRegistry::new, CornerMarkerSequenceRegistry::load);
    }

    public static CornerMarkerSequenceRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), ID);
    }

    /** Hands out the next free number and advances the counter - never repeats within a save. */
    public int nextNumber() {
        int assigned = nextNumber;
        nextNumber++;
        setDirty();
        return assigned;
    }

    private static CornerMarkerSequenceRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        CornerMarkerSequenceRegistry registry = new CornerMarkerSequenceRegistry();
        int loaded = tag.getInt("NextNumber");
        registry.nextNumber = loaded >= 1 ? loaded : 1;
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextNumber", nextNumber);
        return tag;
    }
}
