package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.ClaimBook;
import io.github.khayashi4337.micradrone.construction.core.ConstructionJob;
import io.github.khayashi4337.micradrone.construction.core.JobWorld;
import io.github.khayashi4337.micradrone.construction.core.MaterialPolicy;
import io.github.khayashi4337.micradrone.construction.core.MaterialPort;
import io.github.khayashi4337.micradrone.construction.core.Move;
import io.github.khayashi4337.micradrone.construction.core.PlaceResult;
import io.github.khayashi4337.micradrone.construction.core.Stock;
import io.github.khayashi4337.micradrone.construction.core.SupplySettingsBook;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import io.github.khayashi4337.micradrone.construction.core.WorldPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@link JobWorld} of a live server (F-1: everything here runs on the main thread): dimension ids resolve
 * through {@link MinecraftServer#getLevel}, reads/writes go through {@link ServerWorldPort} so the placement
 * guard sees the owner, and materials follow the job's policy. Survival jobs get Task 27a's
 * {@link InventoryMaterials}: the claim's own supply chests and barrels, plus the owner's inventory only
 * while the claim's switch allows it (F-7).
 */
final class RuntimeJobWorld implements JobWorld {
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
    private final ClaimBook claims;
    private final SupplySettingsBook supply;
    private final SupplyChests chests;

    RuntimeJobWorld(MinecraftServer server, PlacementGuard guard, ClaimBook claims, SupplySettingsBook supply) {
        this.server = server;
        this.guard = guard;
        this.claims = claims;
        this.supply = supply;
        this.chests = new SupplyChests(server);
    }

    /** The shared supply scan (for the adult-facing list command); one cache serves every job's port. */
    SupplyChests chests() {
        return chests;
    }

    @Override
    public WorldPort world(String dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        return level == null ? MISSING_WORLD : new ServerWorldPort(level, guard);
    }

    @Override
    public MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId) {
        return policy == MaterialPolicy.CREATIVE_FREE ? MaterialPort.FREE
                : new InventoryMaterials(server, owner, claims.find(claimId).orElse(null), chests, supply);
    }

    @Override
    public boolean ownerOnline(UUID owner) {
        return server.getPlayerList().getPlayer(owner) != null;
    }

    @Override
    public boolean mayRunWithoutOwner(ConstructionJob job) {
        return false;
    }

    /**
     * The {@link JobWorld} of start-up recovery and of the recover command (Task 25). It differs from the live
     * world in the three places evidence is gathered: reads force-load the chunk they need (a position the world
     * saved but has not loaded must not look unwritten), every write is refused (recovery never changes the world),
     * and the owner's durable transaction id is read from the saved playerdata file - the owner may be offline.
     */
    static JobWorld recovery(MinecraftServer server) {
        return new JobWorld() {
            @Override
            public WorldPort world(String dimension) {
                ServerLevel level = server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
                return level == null ? MISSING_WORLD : new RecoveryWorldPort(level);
            }

            @Override
            public MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId) {
                return policy == MaterialPolicy.CREATIVE_FREE ? MaterialPort.FREE : new RecoveryMaterials(server, owner);
            }

            @Override
            public boolean ownerOnline(UUID owner) {
                return server.getPlayerList().getPlayer(owner) != null;
            }

            @Override
            public boolean mayRunWithoutOwner(ConstructionJob job) {
                return false;
            }
        };
    }

    /** Reads only: the chunk is brought up before the read, every world change is denied. */
    private static final class RecoveryWorldPort implements WorldPort {
        private final ServerLevel level;

        RecoveryWorldPort(ServerLevel level) {
            this.level = level;
        }

        @Override
        public WorldCell read(IntPos pos) {
            try {
                // a saved block in an unloaded chunk must still count as present
                level.getChunk(SectionPos.blockToSectionCoord(pos.x()), SectionPos.blockToSectionCoord(pos.z()));
            } catch (RuntimeException e) {
                // an unreachable chunk reads as unloaded; the evidence then cannot decide and the owner is asked
            }
            return ServerStateReader.read(level, pos);
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
    }

    /**
     * The owner's materials as the saved files prove them: only {@link #durableTx} carries information for
     * recovery, everything else is denied.
     */
    private static final class RecoveryMaterials implements MaterialPort {
        private final long savedTx;

        RecoveryMaterials(MinecraftServer server, UUID owner) {
            // the same read InventoryMaterials makes: a missing or torn file proves no transaction
            this.savedTx = InventoryMaterials.readTx(InventoryMaterials.playerFile(server, owner));
        }

        @Override
        public List<Stock> stocks(Collection<String> itemIds) {
            return List.of();
        }

        @Override
        public Optional<String> giveTarget(List<ItemCount> items) {
            return Optional.empty();
        }

        @Override
        public boolean apply(List<Move> moves) {
            return false;
        }

        @Override
        public void persist(long tx) {
            throw new UnsupportedOperationException("recovery never saves an owner's inventory");
        }

        @Override
        public long durableTx() {
            return savedTx;
        }
    }
}
