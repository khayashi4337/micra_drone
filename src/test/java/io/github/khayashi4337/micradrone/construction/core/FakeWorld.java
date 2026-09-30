package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
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
    private final Map<IntPos, Integer> containerItems = new HashMap<>();
    public final List<String> log = new ArrayList<>();

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

    public void putItems(IntPos pos, int count) {
        containerItems.put(pos, count);
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
        if (!denied.contains(pos) && !unloaded.contains(pos) && dropContentsFirst) {
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
        cells.put(pos, block.isAir() ? WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE) : WorldCell.of(block));
        log.add(what + " " + pos.x() + "," + pos.y() + "," + pos.z() + " " + block);
        afterWrite.accept(pos, block);
        return PlaceResult.PLACED;
    }
}
