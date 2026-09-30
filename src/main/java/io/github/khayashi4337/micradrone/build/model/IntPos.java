package io.github.khayashi4337.micradrone.build.model;

/** A world position without any Minecraft type (the construction adapters convert to BlockPos). */
public record IntPos(int x, int y, int z) {
    public IntPos plus(int dx, int dy, int dz) {
        return new IntPos(x + dx, y + dy, z + dz);
    }
}
