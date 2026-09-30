package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.construction.core.BudgetConfig;
import io.github.khayashi4337.micradrone.construction.core.ClaimBook;
import io.github.khayashi4337.micradrone.construction.core.MaterialPolicy;
import io.github.khayashi4337.micradrone.construction.core.SafetyLimits;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

/**
 * The server config of the construction runtime (04 F-15's {@code micradrone-server.toml}): safety limits
 * (F-5, with a relaxed operator set), the material policy (F-7), the construction budget (F-2), submission
 * permissions (F-4(d)), offline behaviour (F-13) and the claims cap (F-4). The getters convert the raw values
 * into the pure core's records at the adapter edge, so the core never sees the config itself.
 */
public final class ConstructionConfig {
    /** materials.policy: AUTO lets the approver's game mode decide (F-7); the others force one policy for all. */
    public enum MaterialPolicyOption {
        AUTO, CREATIVE_FREE, SURVIVAL_CONSUME
    }

    /** permissions.level: which players may submit plans at all (F-4(d); ALL by default, large jobs still need OP). */
    public enum PermissionLevel {
        ALL, OP
    }

    public static final ModConfigSpec SPEC;

    private static final int MAX_INT = Integer.MAX_VALUE;

    private static final IntValue SAFETY_MAX_PLACEMENTS;
    private static final IntValue SAFETY_MAX_SIZE_X;
    private static final IntValue SAFETY_MAX_SIZE_Y;
    private static final IntValue SAFETY_MAX_SIZE_Z;
    private static final IntValue SAFETY_OP_MAX_PLACEMENTS;
    private static final IntValue SAFETY_OP_MAX_SIZE_X;
    private static final IntValue SAFETY_OP_MAX_SIZE_Y;
    private static final IntValue SAFETY_OP_MAX_SIZE_Z;
    private static final EnumValue<MaterialPolicyOption> MATERIALS_POLICY;
    private static final IntValue BUDGET_MAX_RUNNING_JOBS;
    private static final IntValue BUDGET_MAX_PLACEMENTS_PER_TICK;
    private static final IntValue BUDGET_FAST_PLACEMENTS_PER_TICK;
    private static final BooleanValue BUDGET_FAST_STRUCTURE;
    private static final DoubleValue BUDGET_SLOWDOWN_ABOVE_MSPT;
    private static final DoubleValue BUDGET_RECOVER_BELOW_MSPT;
    private static final EnumValue<PermissionLevel> PERMISSIONS_LEVEL;
    private static final IntValue PERMISSIONS_LARGE_JOB_PLACEMENTS;
    private static final BooleanValue CHUNKS_CONTINUE_WHILE_OFFLINE;
    private static final IntValue CLAIMS_MAX_PER_OWNER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("safety");
        SAFETY_MAX_PLACEMENTS = b.defineInRange("maxPlacements", SafetyLimits.DEFAULT_MAX_PLACEMENTS, 1, MAX_INT);
        SAFETY_MAX_SIZE_X = b.defineInRange("maxSizeX", SafetyLimits.DEFAULT_MAX_SIZE_X, 1, MAX_INT);
        SAFETY_MAX_SIZE_Y = b.defineInRange("maxSizeY", SafetyLimits.DEFAULT_MAX_SIZE_Y, 1, MAX_INT);
        SAFETY_MAX_SIZE_Z = b.defineInRange("maxSizeZ", SafetyLimits.DEFAULT_MAX_SIZE_Z, 1, MAX_INT);
        SAFETY_OP_MAX_PLACEMENTS = b.defineInRange("opMaxPlacements", SafetyLimits.DEFAULT_MAX_PLACEMENTS, 1, MAX_INT);
        SAFETY_OP_MAX_SIZE_X = b.defineInRange("opMaxSizeX", SafetyLimits.DEFAULT_MAX_SIZE_X, 1, MAX_INT);
        SAFETY_OP_MAX_SIZE_Y = b.defineInRange("opMaxSizeY", SafetyLimits.DEFAULT_MAX_SIZE_Y, 1, MAX_INT);
        SAFETY_OP_MAX_SIZE_Z = b.defineInRange("opMaxSizeZ", SafetyLimits.DEFAULT_MAX_SIZE_Z, 1, MAX_INT);
        b.pop();

        b.push("materials");
        MATERIALS_POLICY = b.defineEnum("policy", MaterialPolicyOption.AUTO);
        b.pop();

        b.push("budget");
        BUDGET_MAX_RUNNING_JOBS = b.defineInRange("maxRunningJobs", BudgetConfig.DEFAULT_MAX_RUNNING_JOBS, 1, MAX_INT);
        BUDGET_MAX_PLACEMENTS_PER_TICK = b.defineInRange("maxPlacementsPerTick", BudgetConfig.DEFAULT_MAX_PLACEMENTS_PER_TICK,
                1, MAX_INT);
        BUDGET_FAST_PLACEMENTS_PER_TICK = b.defineInRange("fastPlacementsPerTick", BudgetConfig.DEFAULT_FAST_PLACEMENTS_PER_TICK,
                1, MAX_INT);
        BUDGET_FAST_STRUCTURE = b.define("fastStructure", true);
        BUDGET_SLOWDOWN_ABOVE_MSPT = b.defineInRange("slowdownAboveMspt", BudgetConfig.SLOWDOWN_ABOVE_MSPT, 0.0, Double.MAX_VALUE);
        BUDGET_RECOVER_BELOW_MSPT = b.defineInRange("recoverBelowMspt", BudgetConfig.RECOVER_BELOW_MSPT, 0.0, Double.MAX_VALUE);
        b.pop();

        b.push("permissions");
        PERMISSIONS_LEVEL = b.defineEnum("level", PermissionLevel.ALL);
        // the default equals the safety cap, so large-job OP gating is off unless configured below it (F-4(d))
        PERMISSIONS_LARGE_JOB_PLACEMENTS = b.defineInRange("largeJobPlacements", SafetyLimits.DEFAULT_MAX_PLACEMENTS, 1,
                MAX_INT);
        b.pop();

        b.push("chunks");
        CHUNKS_CONTINUE_WHILE_OFFLINE = b.define("continueWhileOffline", false);
        b.pop();

        b.push("claims");
        CLAIMS_MAX_PER_OWNER = b.defineInRange("maxPerOwner", ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER, 0, MAX_INT);
        b.pop();

        SPEC = b.build();
    }

    private ConstructionConfig() {
    }

    /** The construction budget; the drone cadence and slowdown factor are design constants, not config. */
    public static BudgetConfig budget() {
        return new BudgetConfig(BUDGET_MAX_RUNNING_JOBS.get(), BudgetConfig.DEFAULT_MAX_JOBS_PER_OWNER,
                BUDGET_MAX_PLACEMENTS_PER_TICK.get(), BUDGET_FAST_PLACEMENTS_PER_TICK.get(),
                BudgetConfig.DRONE_INTERVAL_TICKS, BudgetConfig.PLACEMENTS_PER_DRONE, BudgetConfig.MIN_DRONES,
                BudgetConfig.MAX_DRONES, BUDGET_SLOWDOWN_ABOVE_MSPT.get(), BUDGET_RECOVER_BELOW_MSPT.get(),
                BudgetConfig.SLOWDOWN_FACTOR);
    }

    /**
     * The safety limits for {@code level} (its own build height bounds), relaxed for operators when {@code op}
     * is set (F-5's "OPは設定で緩和").
     */
    public static SafetyLimits limits(ServerLevel level, boolean op) {
        return new SafetyLimits(
                (op ? SAFETY_OP_MAX_PLACEMENTS : SAFETY_MAX_PLACEMENTS).get(),
                (op ? SAFETY_OP_MAX_SIZE_X : SAFETY_MAX_SIZE_X).get(),
                (op ? SAFETY_OP_MAX_SIZE_Y : SAFETY_MAX_SIZE_Y).get(),
                (op ? SAFETY_OP_MAX_SIZE_Z : SAFETY_MAX_SIZE_Z).get(),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    /** The material policy the config forces, or null for AUTO (the approver's game mode decides, F-7). */
    public static MaterialPolicy forcedMaterialPolicy() {
        return switch (MATERIALS_POLICY.get()) {
            case AUTO -> null;
            case CREATIVE_FREE -> MaterialPolicy.CREATIVE_FREE;
            case SURVIVAL_CONSUME -> MaterialPolicy.SURVIVAL_CONSUME;
        };
    }

    /** Whether the structure phases place at the fast per-tick rate instead of the drone cadence (F-2). */
    public static boolean fastStructure() {
        return BUDGET_FAST_STRUCTURE.get();
    }

    /** Who may submit plans (F-4(d)); enforced where submissions enter (commands/devkit). */
    public static PermissionLevel permissionLevel() {
        return PERMISSIONS_LEVEL.get();
    }

    /** Placements at or above which only an operator may approve (default equals the cap: effectively off). */
    public static int largeJobPlacements() {
        return PERMISSIONS_LARGE_JOB_PLACEMENTS.getAsInt();
    }

    /** Whether a job may run while its owner is offline (F-13); the adapter still decides per job. */
    public static boolean continueWhileOffline() {
        return CHUNKS_CONTINUE_WHILE_OFFLINE.get();
    }

    /** How many claims one owner may hold (04 F-4, default 8). */
    public static int claimsMaxPerOwner() {
        return CLAIMS_MAX_PER_OWNER.getAsInt();
    }
}
