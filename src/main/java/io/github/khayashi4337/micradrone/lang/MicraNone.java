package io.github.khayashi4337.micradrone.lang;

/** Singleton runtime value for the language's None literal. */
public final class MicraNone {
    public static final MicraNone INSTANCE = new MicraNone();

    private MicraNone() {}

    @Override
    public String toString() {
        return "None";
    }
}
