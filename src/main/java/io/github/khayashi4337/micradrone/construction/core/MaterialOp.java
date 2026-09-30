package io.github.khayashi4337.micradrone.construction.core;

/**
 * The four material operations of a job (04 F-7). CHARGE and RECLAIM take items from the owner (a placement's cost, cut
 * ground taken back when it is put back); YIELD and RETURN give items to the owner (cut ground, a removal's refund).
 */
public enum MaterialOp {
    CHARGE(true), YIELD(false), RECLAIM(true), RETURN(false);

    private final boolean takes;

    MaterialOp(boolean takes) {
        this.takes = takes;
    }

    public boolean takesFromOwner() {
        return takes;
    }
}
