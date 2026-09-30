package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.LocalPos;

/** A building's frame: its origin and box (u in [0,width-1], w in [0,depth-1]) and its floors. */
public record StructureInfo(String id, LocalPos origin, int width, int depth, int floors, int floorHeight) {
    public int totalHeight() {
        return floors * floorHeight;
    }
}
