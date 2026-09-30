package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.ConstructionJob;
import io.github.khayashi4337.micradrone.construction.core.JobWorld;
import io.github.khayashi4337.micradrone.construction.core.MaterialPolicy;
import io.github.khayashi4337.micradrone.construction.core.MaterialPort;
import io.github.khayashi4337.micradrone.construction.core.Move;
import io.github.khayashi4337.micradrone.construction.core.PlaceResult;
import io.github.khayashi4337.micradrone.construction.core.Stock;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import io.github.khayashi4337.micradrone.construction.core.WorldPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@link JobWorld} of a live server (F-1: everything here runs on the main thread): dimension ids resolve
 * through {@link MinecraftServer#getLevel}, reads/writes go through {@link ServerWorldPort} so the placement
 * guard sees the owner, and materials follow the job's policy. Survival inventories are Task 27's
 * {@code InventoryMaterials}; until then a consuming job holds nothing and pauses MATERIALS_MISSING (F-7).
 */
final class RuntimeJobWorld implements JobWorld {
    /** A port holding nothing: no stocks, applies nothing, persists nothing (F-7, survival until Task 27). */
    static final MaterialPort NO_MATERIALS = new MaterialPort() {
        @Override
        public List<Stock> stocks(Collection<String> itemIds) {
            return List.of();
        }

        @Override
        public Optional<String> giveTarget(List<ItemCount> items) {
            return Optional.of(INVENTORY);
        }

        @Override
        public boolean apply(List<Move> moves) {
            return false;
        }

        @Override
        public void persist(long tx) {
        }

        @Override
        public long durableTx() {
            return NO_TX;
        }
    };

    /**
     * A port for a dimension id the server does not have: reads report unloaded (the job pauses
     * CHUNK_UNLOADED instead of crashing on a removed dimension), writes are refused, settle is a no-op.
     */
    private static final WorldPort MISSING_WORLD = new WorldPort() {
        @Override
        public WorldCell read(IntPos pos) {
            return WorldCell.unloaded();
        }

        @Override
        public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
            return PlaceResult.DENIED;
        }

        @Override
        public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
            return PlaceResult.DENIED;
        }

        @Override
        public void settle(List<IntPos> positions) {
        }
    };

    private final MinecraftServer server;
    private final PlacementGuard guard;

    RuntimeJobWorld(MinecraftServer server, PlacementGuard guard) {
        this.server = server;
        this.guard = guard;
    }

    @Override
    public WorldPort world(String dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        return level == null ? MISSING_WORLD : new ServerWorldPort(level, guard);
    }

    @Override
    public MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId) {
        return policy == MaterialPolicy.CREATIVE_FREE ? MaterialPort.FREE : NO_MATERIALS;
    }

    @Override
    public boolean ownerOnline(UUID owner) {
        return server.getPlayerList().getPlayer(owner) != null;
    }

    @Override
    public boolean mayRunWithoutOwner(ConstructionJob job) {
        return false;
    }
}
