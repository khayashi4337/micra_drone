package io.github.khayashi4337.micradrone.build.parts;

/** The mod version range a part needs (e.g. create [6.0.10,6.1.0)); outside it the part is disabled (D-13). */
public record VersionRange(String modId, String mavenRange) {
    private static final String UNRESTRICTED = "";

    public static final VersionRange ALWAYS = new VersionRange(UNRESTRICTED, UNRESTRICTED);
}
