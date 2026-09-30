package io.github.khayashi4337.micradrone.build.model;

/** A position in a {@link BuildFrame}: u is right, v is up, w is forward. */
public record LocalPos(int u, int v, int w) {
    public LocalPos plus(int du, int dv, int dw) {
        return new LocalPos(u + du, v + dv, w + dw);
    }
}
