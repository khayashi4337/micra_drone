package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.SiteClaim;
import io.github.khayashi4337.micradrone.construction.core.SourcePolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The supply containers of a claim (Task 27a): every chest - a trapped one included - and every barrel
 * whose position lies in the claim's {@code worldBox} is a supply source; nothing is registered, and a
 * drone controller or any other container is ignored. Scans look only at already-loaded chunks (an
 * unloaded chunk's chests simply do not count) and run at most once per
 * {@link #SUPPLY_SCAN_INTERVAL_TICKS} per claim; between scans the found set is kept. A double chest
 * counts once: its source position is the smaller of its two halves in x, y, z order.
 */
final class SupplyChests {
    /** Two seconds of vanilla ticks between a claim's scans; a chest placed mid-run is found late, never never. */
    static final long SUPPLY_SCAN_INTERVAL_TICKS = 40L;

    /** What one scan found: the box it covered, the tick it ran at, and the source positions it saw. */
    private record Scan(Box box, long tick, List<IntPos> positions) {
    }

    private final MinecraftServer server;
    private final Map<String, Scan> scans = new HashMap<>();

    SupplyChests(MinecraftServer server) {
        this.server = server;
    }

    /**
     * The claim's supply source positions in coordinate order. The last scan is kept until the box
     * changes or the interval runs out; a dimension the server does not have reports an empty set.
     */
    List<IntPos> positions(SiteClaim claim) {
        Scan s = scans.get(claim.claimId());
        long now = server.getTickCount();
        if (s != null && s.box().equals(claim.worldBox()) && now - s.tick() < SUPPLY_SCAN_INTERVAL_TICKS) {
            return s.positions();
        }
        ServerLevel level = level(claim.dimension());
        List<IntPos> found = level == null ? List.of() : scanBox(level, claim.worldBox());
        scans.put(claim.claimId(), new Scan(claim.worldBox(), now, found));
        return found;
    }

    /** The container a source position still names, or null when it is gone or its chunk unloaded. */
    static Container containerAt(ServerLevel level, IntPos pos) {
        BlockPos p = ServerWorldPort.toBlockPos(pos);
        ChunkAccess chunk = loadedChunk(level, p.getX(), p.getZ());
        if (chunk == null) {
            return null;
        }
        BlockEntity be = chunk.getBlockEntity(p);
        if (be instanceof BarrelBlockEntity barrel) {
            return barrel;
        }
        if (!(be instanceof ChestBlockEntity chest)) {
            return null;
        }
        BlockState state = chunk.getBlockState(p);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            // a block entity without its chest block is a leftover, not a container
            return null;
        }
        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            // reading the other half would load its chunk; an unloaded half means this half is alone now
            BlockPos other = p.relative(ChestBlock.getConnectedDirection(state));
            if (loadedChunk(level, other.getX(), other.getZ()) == null) {
                return chest;
            }
        }
        return ChestBlock.getContainer(chestBlock, state, level, p, false);
    }

    /** The level of a dimension id, or null when the server has none (a removed dimension supplies nothing). */
    private ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
    }

    /** Every supply source position inside the box, coordinate order, double chests deduplicated. */
    private static List<IntPos> scanBox(ServerLevel level, Box box) {
        TreeSet<IntPos> found = new TreeSet<>(SourcePolicy.POSITION_ORDER);
        int cx0 = SectionPos.blockToSectionCoord(box.minA());
        int cx1 = SectionPos.blockToSectionCoord(box.maxA());
        int cz0 = SectionPos.blockToSectionCoord(box.minC());
        int cz1 = SectionPos.blockToSectionCoord(box.maxC());
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos p : chunk.getBlockEntitiesPos()) {
                    if (!box.contains(p.getX(), p.getY(), p.getZ())) {
                        continue;
                    }
                    IntPos source = sourcePosition(chunk, p);
                    if (source != null) {
                        found.add(source);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The source id's position of the block entity at {@code p}: barrels and chests (trapped included)
     * only; for a double-chest half the smaller of the two positions, so both halves name one source.
     */
    private static IntPos sourcePosition(ChunkAccess chunk, BlockPos p) {
        BlockEntity be = chunk.getBlockEntity(p);
        if (be instanceof BarrelBlockEntity) {
            return toInt(p);
        }
        if (!(be instanceof ChestBlockEntity)) {
            return null;
        }
        BlockState state = chunk.getBlockState(p);
        IntPos here = toInt(p);
        if (!(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return here;
        }
        IntPos other = toInt(p.relative(ChestBlock.getConnectedDirection(state)));
        return SourcePolicy.POSITION_ORDER.compare(here, other) <= 0 ? here : other;
    }

    /** The chunk holding (x, z) only when it is already fully loaded - a scan never forces a chunk up. */
    private static ChunkAccess loadedChunk(ServerLevel level, int x, int z) {
        return level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z),
                ChunkStatus.FULL, false);
    }

    private static IntPos toInt(BlockPos p) {
        return new IntPos(p.getX(), p.getY(), p.getZ());
    }
}
