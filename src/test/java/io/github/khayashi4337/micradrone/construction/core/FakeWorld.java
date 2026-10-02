package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** An in-memory world for the pure core's tests. Unset positions are air. Every write is logged in order. */
public final class FakeWorld implements WorldPort {
    /** Runs after every successful write: lets a test play the player who breaks or swaps a block right after. */
    public BiConsumer<IntPos, BlockSpec> afterWrite = (pos, block) -> {
    };
    private final Map<IntPos, WorldCell> cells = new HashMap<>();
    private final Set<IntPos> unloaded = new HashSet<>();
    private final Set<IntPos> denied = new HashSet<>();
    /** Positions where an entity (a player, a mob, a boat…) stands: a colliding write there comes back refused. */
    private final Set<IntPos> occupied = new HashSet<>();
    private final Map<IntPos, Integer> containerItems = new HashMap<>();
    /** Blocks the fake treats as block entities: block id -> block entity type (D-25 container tests). */
    private final Map<String, String> blockEntityTypes = new HashMap<>();
    public final List<String> log = new ArrayList<>();

    /** Registers a block id as carrying a block entity of the given type; place/restore keep it on the cell. */
    public FakeWorld blockEntity(String blockId, String blockEntityType) {
        blockEntityTypes.put(blockId, blockEntityType);
        return this;
    }

    public FakeWorld set(IntPos pos, WorldCell cell) {
        cells.put(pos, cell);
        return this;
    }

    public FakeWorld setBlock(IntPos pos, BlockSpec block, CellTrait... traits) {
        return set(pos, WorldCell.of(block, traits));
    }

    public void unload(IntPos pos) {
        unloaded.add(pos);
    }

    public void load(IntPos pos) {
        unloaded.remove(pos);
    }

    public void deny(IntPos pos) {
        denied.add(pos);
    }

    /** Test setup: an entity stands at pos, so a block with a collision shape cannot be written there. */
    public void occupy(IntPos pos) {
        occupied.add(pos);
    }

    /** The entity at pos stepped aside. */
    public void leave(IntPos pos) {
        occupied.remove(pos);
    }

    public void putItems(IntPos pos, int count) {
        containerItems.put(pos, count);
        WorldCell cell = cells.get(pos);
        if (cell != null && cell.loaded() && cell.observed().hasBlockEntity()) {
            Set<CellTrait> traits = EnumSet.noneOf(CellTrait.class);
            traits.addAll(cell.traits());
            if (count == 0) {
                traits.add(CellTrait.EMPTY_CONTAINER);
            } else {
                traits.remove(CellTrait.EMPTY_CONTAINER);
            }
            cells.put(pos, new WorldCell(true, cell.observed(), traits));
        }
    }

    public BlockSpec blockAt(IntPos pos) {
        return read(pos).block();
    }

    @Override
    public WorldCell read(IntPos pos) {
        if (unloaded.contains(pos)) {
            return WorldCell.unloaded();
        }
        return cells.getOrDefault(pos, WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
    }

    @Override
    public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
        return write("place", pos, block);
    }

    @Override
    public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
        if (!denied.contains(pos) && !unloaded.contains(pos) && !entityBlocks(pos, block) && dropContentsFirst) {
            int n = containerItems.getOrDefault(pos, 0);
            containerItems.remove(pos);
            log.add("drop " + pos.x() + "," + pos.y() + "," + pos.z() + " " + n);
        }
        return write("restore", pos, block);
    }

    public int itemsIn(IntPos pos) {
        return containerItems.getOrDefault(pos, 0);
    }

    @Override
    public void settle(List<IntPos> positions) {
        StringBuilder sb = new StringBuilder("settle");
        for (IntPos p : positions) {
            sb.append(' ').append(p.x()).append(',').append(p.y()).append(',').append(p.z());
        }
        log.add(sb.toString());
    }

    private PlaceResult write(String what, IntPos pos, BlockSpec block) {
        if (unloaded.contains(pos)) {
            throw new IllegalStateException("writing into an unloaded chunk at " + pos);
        }
        if (denied.contains(pos)) {
            log.add("denied " + pos.x() + "," + pos.y() + "," + pos.z());
            return PlaceResult.DENIED;
        }
        if (entityBlocks(pos, block)) {
            log.add("blocked " + pos.x() + "," + pos.y() + "," + pos.z());
            return PlaceResult.BLOCKED_BY_ENTITY;
        }
        cells.put(pos, storedCell(pos, block));
        log.add(what + " " + pos.x() + "," + pos.y() + "," + pos.z() + " " + block);
        afterWrite.accept(pos, block);
        return PlaceResult.PLACED;
    }

    /**
     * Vanilla's placement check (Level.isUnobstructed): an entity overlapping the state's collision shape blocks the
     * write; air and other states without a collision shape always pass.
     */
    private boolean entityBlocks(IntPos pos, BlockSpec block) {
        return occupied.contains(pos) && !block.isAir();
    }

    /**
     * The cell a write leaves behind. A catalogued block id keeps its block entity, marked EMPTY_CONTAINER while the
     * position holds no items; anything else is the plain block it always was.
     */
    private WorldCell storedCell(IntPos pos, BlockSpec block) {
        if (block.isAir()) {
            return WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE);
        }
        String type = blockEntityTypes.get(block.blockId());
        if (type == null) {
            return WorldCell.of(block);
        }
        return containerItems.getOrDefault(pos, 0) == 0
                ? WorldCell.withBlockEntity(block, type, CellTrait.EMPTY_CONTAINER)
                : WorldCell.withBlockEntity(block, type);
    }
}
