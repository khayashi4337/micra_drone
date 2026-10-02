package io.github.khayashi4337.micradrone.construction;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.verify.CompareScope;
import io.github.khayashi4337.micradrone.build.verify.SnapshotDiff;
import io.github.khayashi4337.micradrone.build.verify.SparseSnapshot;
import io.github.khayashi4337.micradrone.build.verify.VolatileProps;
import io.github.khayashi4337.micradrone.construction.core.ApproveArgs;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.CommandAccess;
import io.github.khayashi4337.micradrone.construction.core.ControlResult;
import io.github.khayashi4337.micradrone.construction.core.JobRecord;
import io.github.khayashi4337.micradrone.construction.core.JobStatus;
import io.github.khayashi4337.micradrone.construction.core.MessageKey;
import io.github.khayashi4337.micradrone.construction.core.PlanFileOpener;
import io.github.khayashi4337.micradrone.construction.core.PlanFileReader;
import io.github.khayashi4337.micradrone.construction.core.PlanSource;
import io.github.khayashi4337.micradrone.construction.core.PlanSubmission;
import io.github.khayashi4337.micradrone.construction.core.RecoveryChoice;
import io.github.khayashi4337.micradrone.construction.core.SiteRelocation;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The debug commands of the construction runtime (Task 17, F-18/F-22): {@code /micradrone build submit},
 * {@code submit-here}, {@code approve}, {@code status}, {@code list}, {@code cancel}, {@code resume}, {@code check}
 * and {@code verify}. Every decision lives in the pure core or in {@link ConstructionRuntime}; this class only reads
 * files, translates arguments and relays the runtime's own messages (D-1: the adapter never places a block).
 * Registered from {@link ConstructionRuntime.Events} on {@code RegisterCommandsEvent}.
 */
public final class BuildCommands {
    private BuildCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("micradrone").then(Commands.literal("build")
                .then(Commands.literal("submit")
                        .requires(BuildCommands::maySubmit)
                        .then(Commands.argument("source", StringArgumentType.greedyString())
                                .executes(ctx -> submit(ctx, false))))
                .then(Commands.literal("submit-here")
                        .requires(BuildCommands::maySubmit)
                        .then(Commands.argument("source", StringArgumentType.greedyString())
                                .executes(ctx -> submit(ctx, true))))
                .then(Commands.literal("approve")
                        .requires(BuildCommands::maySubmit)
                        .then(Commands.argument("hash", StringArgumentType.word())
                                .executes(ctx -> approve(ctx, ""))
                                .then(Commands.argument("flags", StringArgumentType.greedyString())
                                        .executes(ctx -> approve(ctx, StringArgumentType.getString(ctx, "flags"))))))
                .then(Commands.literal("status")
                        .executes(BuildCommands::statusAll)
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .executes(BuildCommands::status)))
                .then(Commands.literal("list")
                        .executes(BuildCommands::statusAll))
                .then(Commands.literal("cancel")
                        .requires(src -> src.getPlayer() != null)
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .executes(BuildCommands::cancel)))
                .then(Commands.literal("resume")
                        .requires(src -> src.getPlayer() != null)
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .executes(ctx -> resume(ctx, false))
                                .then(Commands.literal("skip-conflicts")
                                        .executes(ctx -> resume(ctx, true)))))
                .then(Commands.literal("check")
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .executes(BuildCommands::check)))
                .then(Commands.literal("verify")
                        .requires(src -> src.getPlayer() != null)
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .executes(BuildCommands::verify)))
                .then(Commands.literal("rollback")
                        .requires(src -> src.getPlayer() != null)
                        .then(Commands.argument("claimId", StringArgumentType.word())
                                .executes(ctx -> rollback(ctx, false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> rollback(ctx, true)))))
                .then(Commands.literal("supply")
                        .then(Commands.literal("list")
                                .then(Commands.argument("claimId", StringArgumentType.word())
                                        .executes(BuildCommands::supplyList)))
                        .then(Commands.literal("inventory")
                                .then(Commands.argument("claimId", StringArgumentType.word())
                                        .then(Commands.argument("on", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        List.of("on", "off"), b))
                                                .executes(BuildCommands::supplyInventory)))))
                .then(Commands.literal("recover")
                        .requires(src -> src.getPlayer() != null)
                        .then(Commands.argument("jobId", StringArgumentType.word())
                                .then(Commands.literal("adopt")
                                        .executes(ctx -> recover(ctx, RecoveryChoice.ADOPT)))
                                .then(Commands.literal("discard")
                                        .executes(ctx -> recover(ctx, RecoveryChoice.DISCARD)))
                                .then(Commands.literal("repair")
                                        .executes(ctx -> recover(ctx, RecoveryChoice.REPAIR)))
                                .then(Commands.literal("fail")
                                        .executes(ctx -> recover(ctx, RecoveryChoice.FAIL)))))));
    }

    /** permissions.level of micradrone-server.toml (F-4(d)): ALL opens submit/approve to every player. */
    private static boolean maySubmit(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && maySubmit(player);
    }

    /** The same submit gate for a payload's player ({@link BuildNetwork}); a non-player never passes. */
    static boolean maySubmit(ServerPlayer player) {
        int level = ConstructionConfig.permissionLevel() == ConstructionConfig.PermissionLevel.OP
                ? Commands.LEVEL_GAMEMASTERS : Commands.LEVEL_ALL;
        return player.hasPermissions(level);
    }

    private static int submit(CommandContext<CommandSourceStack> ctx, boolean relocate) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        MinecraftServer server = source.getServer();
        ConstructionRuntime runtime = ConstructionRuntime.of(server).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        // M2b: a submission over a command returns the owner's chat to the command lines
        runtime.markCommand(player.getUUID());
        String json = readSource(player, server, StringArgumentType.getString(ctx, "source"));
        if (json == null) {
            return 0;
        }
        PlanFileReader.Result result = PlanFileReader.read(json, BuildingParts.registry());
        if (result.error() != null) {
            ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, result.error()));
            return 0;
        }
        if (!result.issues().isEmpty()) {
            ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_ISSUES, result.issues().size()));
            ServerMessages.sendIssues(server, player.getUUID(), result.issues());
            return 0;
        }
        PlanSubmission submission = result.submission();
        if (relocate) {
            submission = relocatedSubmission(player, submission);
            if (submission == null) {
                return 0;
            }
        }
        runtime.submit(player, submission);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * submit-here for both entry points (the command and {@link BuildNetwork}'s {@code here} flag):
     * the same plan with its site moved under the player's feet, or null after the refusal line.
     */
    static PlanSubmission relocatedSubmission(ServerPlayer player, PlanSubmission submission) {
        SemanticPlan moved = moveToFeet(player, submission.plan());
        return moved == null ? null : new PlanSubmission(moved, submission.templates(), submission.kind(),
                submission.parentJobId(), submission.claimId());
    }

    /**
     * submit-here (07): the same plan with its site pinned to the block under the player's feet, the horizontal
     * direction the player faces, and the player's dimension. A site-less plan cannot be relocated.
     */
    private static SemanticPlan moveToFeet(ServerPlayer player, SemanticPlan plan) {
        if (plan.site() == null) {
            ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, "the plan has no site to move"));
            return null;
        }
        BlockPos feet = player.blockPosition();
        return SiteRelocation.relocate(plan, player.serverLevel().dimension().location().toString(),
                new IntPos(feet.getX(), feet.getY(), feet.getZ()), Facing.valueOf(player.getDirection().name()));
    }

    /** The plan file's text: a bundled sample from the classpath or a checked file under micradrone/plans (F-22). */
    private static String readSource(ServerPlayer player, MinecraftServer server, String arg) {
        Path plansDir = server.getServerDirectory().resolve(PlanSource.PLANS_DIR).normalize();
        switch (PlanSource.resolve(arg, server.getServerDirectory())) {
            case PlanSource.Invalid bad -> {
                ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, bad.reason()));
                return null;
            }
            case PlanSource.Sample sample -> {
                return readSample(player, sample.resource());
            }
            case PlanSource.FileAt file -> {
                try {
                    return new String(PlanFileOpener.read(plansDir, file.path(), PlanSource.MAX_PLAN_BYTES),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, e.getMessage()));
                    return null;
                }
            }
        }
    }

    private static String readSample(ServerPlayer player, String resource) {
        try (InputStream in = BuildCommands.class.getResourceAsStream(resource)) {
            if (in == null) {
                ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, "missing " + resource));
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length > PlanSource.MAX_PLAN_BYTES) {
                ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, "too large"));
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BAD_SOURCE, e.getMessage()));
            return null;
        }
    }

    private static int approve(CommandContext<CommandSourceStack> ctx, String flags) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        ApproveArgs.Parsed parsed = ApproveArgs.parse(flags);
        if (parsed.error() != null) {
            // a mistyped flag is a usage error like Brigadier's own, not a plan problem
            source.sendFailure(Component.literal(parsed.error()));
            return 0;
        }
        runtime.approve(player, StringArgumentType.getString(ctx, "hash"), parsed.confirmations(), parsed.risks());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The inspection rule of F-4/D-12: the owner or an operator may look at a job, and anyone else's answer is
     * the same "not found" a missing id gets, so a stranger never learns the job exists at all. A non-player
     * source (the console) carries a null viewer; its operator permission decides.
     */
    private static boolean mayInspect(CommandSourceStack source, UUID owner) {
        ServerPlayer player = source.getPlayer();
        return CommandAccess.mayInspect(player == null ? null : player.getUUID(),
                source.hasPermission(Commands.LEVEL_GAMEMASTERS), owner);
    }

    private static void notFound(CommandSourceStack source) {
        source.sendSystemMessage(ServerMessages.of(MessageKey.of(ChildMessages.control(ControlResult.NOT_FOUND))));
    }

    private static int statusAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("construction runtime is not running"));
            return 0;
        }
        if (runtime.halted()) {
            source.sendSystemMessage(ServerMessages.of(MessageKey.of(ChildMessages.HALTED)));
        }
        List<JobStatus> visible = runtime.jobs().statuses().stream()
                .filter(st -> mayInspect(source, st.owner()))
                .toList();
        if (visible.isEmpty()) {
            source.sendSystemMessage(ServerMessages.of(MessageKey.of(ChildMessages.NO_JOBS)));
            return 0;
        }
        for (JobStatus status : visible) {
            source.sendSystemMessage(ServerMessages.status(status));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("construction runtime is not running"));
            return 0;
        }
        Optional<JobStatus> status = runtime.jobs().status(StringArgumentType.getString(ctx, "jobId"));
        if (status.isEmpty() || !mayInspect(source, status.get().owner())) {
            notFound(source);
            return 0;
        }
        if (runtime.halted()) {
            source.sendSystemMessage(ServerMessages.of(MessageKey.of(ChildMessages.HALTED)));
        }
        source.sendSystemMessage(ServerMessages.status(status.get()));
        return Command.SINGLE_SUCCESS;
    }

    /** Owner-or-operator is judged by {@code JobService.cancel} itself (D-12); the command only relays. */
    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        runtime.cancel(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS),
                StringArgumentType.getString(ctx, "jobId"));
        return Command.SINGLE_SUCCESS;
    }

    private static int resume(CommandContext<CommandSourceStack> ctx, boolean skipConflicts) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        runtime.resume(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS),
                StringArgumentType.getString(ctx, "jobId"), skipConflicts);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The adult/script path to the per-claim inventory switch (Task 27a):
     * {@code supply inventory <claimId> <on|off>}. Owner-or-operator is judged by
     * {@code ConstructionRuntime#setInventoryAllowed} itself (D-12); a non-player source carries
     * {@link Util#NIL_UUID}, so only its operator permission can satisfy the check. The answers are
     * plain literal lines to the command source - they are not child-facing messages.
     */
    private static int supplyInventory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("construction runtime is not running"));
            return 0;
        }
        String on = StringArgumentType.getString(ctx, "on");
        if (!"on".equals(on) && !"off".equals(on)) {
            source.sendFailure(Component.literal("usage: supply inventory <claimId> <on|off>"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        String claimId = StringArgumentType.getString(ctx, "claimId");
        boolean allowed = "on".equals(on);
        ControlResult result = runtime.setInventoryAllowed(
                player == null ? Util.NIL_UUID : player.getUUID(),
                source.hasPermission(Commands.LEVEL_GAMEMASTERS), claimId, allowed);
        if (result != ControlResult.OK) {
            source.sendFailure(Component.literal("supply " + claimId + ": " + result.name().toLowerCase()));
            return 0;
        }
        source.sendSystemMessage(Component.literal("supply " + claimId + ": inventory=" + on));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The claim's supply answer for adults and scripts (Task 27a): the chest/barrel source positions the
     * scan sees in the claim's box, and whether the owner's inventory may be used. The same inspection
     * rule as {@code status} applies - a stranger gets the plain "not found" and learns nothing.
     */
    private static int supplyList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("construction runtime is not running"));
            return 0;
        }
        Optional<ConstructionRuntime.SupplyInfo> info =
                runtime.supplyInfo(StringArgumentType.getString(ctx, "claimId"));
        if (info.isEmpty() || !mayInspect(source, info.get().ownerUuid())) {
            notFound(source);
            return 0;
        }
        ConstructionRuntime.SupplyInfo i = info.get();
        source.sendSystemMessage(Component.literal(
                "supply " + i.claimId() + ": inventory=" + (i.inventoryAllowed() ? "on" : "off")));
        for (IntPos p : i.chests()) {
            source.sendSystemMessage(Component.literal("  chest " + p.x() + "," + p.y() + "," + p.z()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Read-only diff of the world against the manifest up to the job's cursor (07): the answer is the number of
     * deviations, nothing is placed or removed, and positions in an unloaded chunk count as unread, not missing.
     * The snapshot window is capped, so a long manifest is compared in slices of {@link SparseSnapshot#MAX_POSITIONS}
     * placements.
     */
    private static int check(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ConstructionRuntime runtime = ConstructionRuntime.of(server).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("construction runtime is not running"));
            return 0;
        }
        JobRecord record = runtime.jobs().record(StringArgumentType.getString(ctx, "jobId")).orElse(null);
        if (record == null || !mayInspect(source, record.job().ownerUuid())) {
            notFound(source);
            return 0;
        }
        PlacementManifest manifest = record.manifest();
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension())));
        if (level == null) {
            source.sendFailure(Component.literal("no such dimension: " + manifest.dimension()));
            return 0;
        }
        Function<String, Set<String>> volatileOfNode = VolatileProps.of(record.nodeTypes(), BuildingParts.registry());
        Set<String> assembled = runtime.jobs().registry(record.job().claimId()).assemblies().keySet();
        int cursor = record.job().cursor();
        int deviations = 0;
        for (int from = 0; from < cursor; from += SparseSnapshot.MAX_POSITIONS) {
            int to = Math.min(cursor, from + SparseSnapshot.MAX_POSITIONS);
            Map<IntPos, ObservedBlock> blocks = new HashMap<>();
            for (Placement p : manifest.placements()) {
                if (p.index() >= from && p.index() < to) {
                    WorldCell cell = ServerStateReader.read(level, p.pos());
                    if (cell.loaded()) {
                        blocks.put(p.pos(), cell.observed());
                    }
                }
            }
            deviations += SnapshotDiff.compare(manifest, new SparseSnapshot(blocks),
                    new CompareScope.IndexRange(from, to), volatileOfNode, assembled).deviations().size();
        }
        source.sendSystemMessage(ServerMessages.of(MessageKey.of(ChildMessages.CONFLICTS, deviations)));
        return deviations;
    }

    /** Owner-or-operator is judged by {@code JobService.beginVerify} itself (D-12); the command only relays. */
    private static int verify(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        runtime.verify(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS),
                StringArgumentType.getString(ctx, "jobId"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Owner-or-operator is judged by {@code JobService.rollback} itself (D-12); the command only relays.
     * Without {@code confirm} the answer is the preview of how many blocks come out (F-5's confirmation stand-in);
     * with it, the ROLLBACK job runs.
     */
    private static int rollback(CommandContext<CommandSourceStack> ctx, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        runtime.rollback(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS),
                StringArgumentType.getString(ctx, "claimId"), confirm);
        return Command.SINGLE_SUCCESS;
    }

    /** Owner-or-operator is judged by {@code JobService.recover} itself (D-12); the command only relays. */
    private static int recover(CommandContext<CommandSourceStack> ctx, RecoveryChoice choice) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        ConstructionRuntime runtime = ConstructionRuntime.of(source.getServer()).orElse(null);
        if (player == null || runtime == null) {
            return 0;
        }
        runtime.recover(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS),
                StringArgumentType.getString(ctx, "jobId"), choice);
        return Command.SINGLE_SUCCESS;
    }
}
