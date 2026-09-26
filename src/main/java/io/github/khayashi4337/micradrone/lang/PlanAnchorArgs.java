package io.github.khayashi4337.micradrone.lang;

/**
 * Where a part goes, as a construction script wrote it: an absolute local position, a spot on another part's
 * surface, or a named slot. Fields that the kind does not use hold 0/false/null.
 */
public record PlanAnchorArgs(Kind kind, int u, int v, int w, int turns, boolean mirror, String target, String side, String slot) {
    public enum Kind { ABSOLUTE, SURFACE, SLOT }

    public static PlanAnchorArgs absolute(int u, int v, int w, int turns, boolean mirror) {
        return new PlanAnchorArgs(Kind.ABSOLUTE, u, v, w, turns, mirror, null, null, null);
    }

    public static PlanAnchorArgs surface(String target, String side, int u, int v) {
        return new PlanAnchorArgs(Kind.SURFACE, u, v, 0, 0, false, target, side, null);
    }

    public static PlanAnchorArgs slot(String slot, int turns, boolean mirror) {
        return new PlanAnchorArgs(Kind.SLOT, 0, 0, 0, turns, mirror, null, null, slot);
    }
}
