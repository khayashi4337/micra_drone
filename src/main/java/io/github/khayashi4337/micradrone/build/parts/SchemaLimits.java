package io.github.khayashi4337.micradrone.build.parts;

/** Size limits measured by spike S-1 (docs/investigations/spk_s1_json_schema_limits.md). */
public final class SchemaLimits {
    /** Compact-JSON schema size that is safe when claude.exe is launched directly (the hard limit is a 32,766-char command line). */
    public static final int CLAUDE_EXE_MAX_SCHEMA_CHARS = 20_000;
    /** The same when going through cmd.exe (the hard limit is an 8,118-char command line). */
    public static final int CMD_EXE_MAX_SCHEMA_CHARS = 5_000;
    /** Room for an enum of ids on the cmd.exe route: count * (id length + overhead) must stay within this. */
    public static final int CMD_EXE_ENUM_BUDGET_CHARS = 4_000;
    /** Each enum entry costs its id plus two quotes (escaped on the command line) and a comma. */
    public static final int ENUM_ENTRY_OVERHEAD = 5;

    private SchemaLimits() {
    }

    public static boolean enumFitsCmdExe(int count, int idLength) {
        return (long) count * (idLength + ENUM_ENTRY_OVERHEAD) <= CMD_EXE_ENUM_BUDGET_CHARS;
    }
}
