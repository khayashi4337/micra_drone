package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import io.github.khayashi4337.micradrone.construction.core.ApprovalDecision;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.Confirmations;
import io.github.khayashi4337.micradrone.construction.core.ControlResult;
import io.github.khayashi4337.micradrone.construction.core.JobStatus;
import io.github.khayashi4337.micradrone.construction.core.OfferView;
import io.github.khayashi4337.micradrone.construction.core.PlanChunkCheck;
import io.github.khayashi4337.micradrone.construction.core.PlanFileReader;
import io.github.khayashi4337.micradrone.construction.core.PlanSubmission;
import io.github.khayashi4337.micradrone.construction.core.ProgressView;
import io.github.khayashi4337.micradrone.construction.core.SubmitOutcome;
import io.github.khayashi4337.micradrone.construction.net.BuildApprovePayload;
import io.github.khayashi4337.micradrone.construction.net.BuildCancelPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildOfferPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildPlanPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildProgressPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildRollbackPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's end of the build channel (M2): the three C2S handlers and the two S2C pushers.
 * Handlers run on the server main thread (the PayloadRegistrar default), so they may touch the
 * runtime directly; the checks a command would do ({@code maySubmit}, the plan reader, the
 * submit-here relocation) are the very same code paths the commands use - nothing is re-derived
 * here. The adapter stays thin: every decision lives in the core or in {@link ConstructionRuntime}.
 */
public final class BuildNetwork {
    private BuildNetwork() {
    }

    /**
     * A plan upload. Only a player with submit permission counts (same gate as the build commands);
     * a bad envelope or an unreadable plan is answered with a REJECTED offer without submitting, so
     * the client's AI gets the technical message it needs to fix its JSON.
     */
    public static void handlePlan(BuildPlanPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !BuildCommands.maySubmit(player)) {
            return;
        }
        ConstructionRuntime runtime = runtimeOf(player);
        if (runtime == null) {
            return;
        }
        Optional<String> bad = PlanChunkCheck.check(payload.transferId(), payload.index(), payload.count());
        if (bad.isPresent()) {
            // an upload shape the MVP cannot serve: a technical line, not a child-facing one
            sendOffer(player, OfferView.rejectedTree(List.of(new OfferView.Entry(null, bad.get()))));
            return;
        }
        PlanFileReader.Result result = PlanFileReader.read(payload.chunk(), BuildingParts.registry());
        if (result.error() != null) {
            sendOffer(player, OfferView.rejectedTree(
                    List.of(new OfferView.Entry(ChildMessages.SUBMIT_BAD_SOURCE, result.error()))));
            return;
        }
        if (!result.issues().isEmpty()) {
            List<OfferView.Entry> entries = new ArrayList<>(result.issues().size());
            for (Issue issue : result.issues()) {
                entries.add(OfferView.Entry.of(issue));
            }
            sendOffer(player, OfferView.rejectedTree(entries));
            return;
        }
        PlanSubmission submission = result.submission();
        if (payload.here()) {
            submission = BuildCommands.relocatedSubmission(player, submission);
            if (submission == null) {
                return;
            }
        }
        // M2b: a submission over the panel moves the owner's chat to the quiet view
        runtime.markPanel(player.getUUID());
        runtime.submit(player, submission);
    }

    /**
     * The owner's answer to an offer: the desk's own checks decide, a rejection comes back as a
     * REJECTED offer carrying the refusal's child key and blocking issues, an approval starts the
     * job and answers with its first progress document.
     */
    public static void handleApprove(BuildApprovePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !BuildCommands.maySubmit(player)) {
            return;
        }
        ConstructionRuntime runtime = runtimeOf(player);
        if (runtime == null) {
            return;
        }
        ApprovalDecision decision = runtime.approve(player, payload.hash(),
                new Confirmations(payload.confirmTerraform(), payload.confirmDestructive()), List.of());
        if (decision instanceof ApprovalDecision.Rejected rejected) {
            List<OfferView.Entry> entries = new ArrayList<>(rejected.blocking().size() + 1);
            entries.add(new OfferView.Entry(ChildMessages.rejection(rejected.reason()), rejected.reason().name()));
            for (Issue issue : rejected.blocking()) {
                entries.add(OfferView.Entry.of(issue));
            }
            sendOffer(player, OfferView.rejectedTree(entries));
        } else if (decision instanceof ApprovalDecision.Approved approved) {
            runtime.jobs().status(approved.job().jobId())
                    .ifPresent(status -> sendProgress(player, status));
        }
    }

    /** A cancel request: the job service itself decides owner-or-operator (D-12), like the command. */
    public static void handleCancel(BuildCancelPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ConstructionRuntime runtime = runtimeOf(player);
        if (runtime == null) {
            return;
        }
        runtime.cancel(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS), payload.jobId());
    }

    /**
     * A rollback ask from the panel's もとにもどす button (M5): the same submit gate as the other
     * build packets, then the runtime's confirmed rollback - owner-or-operator is judged by
     * {@code JobService.rollback} itself (D-12). A refusal comes back as a bare REJECTED offer so
     * the client flow can show its refusal line; the panel mark keeps the command machinery
     * (the claim's status line) out of the child's chat.
     */
    public static void handleRollback(BuildRollbackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !BuildCommands.maySubmit(player)) {
            return;
        }
        ConstructionRuntime runtime = runtimeOf(player);
        if (runtime == null) {
            return;
        }
        // the button counts as a panel ask: the rollback's own lines keep the quiet view
        runtime.markPanel(player.getUUID());
        ControlResult result = runtime.rollback(player.getUUID(),
                player.hasPermissions(Commands.LEVEL_GAMEMASTERS), payload.claimId(), true);
        if (result != ControlResult.OK) {
            sendOffer(player, OfferView.rejectedTree(List.of()));
        }
    }

    /**
     * The offer push: called by {@link ConstructionRuntime} on the tick a submission leaves WORKING
     * for OFFERED or FAILED (once per outcome - each outcome object is written once).
     */
    public static void pushOffer(MinecraftServer server, UUID owner, SubmitOutcome outcome, int blocks) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            sendOffer(player, OfferView.tree(outcome, blocks));
        }
    }

    /** The progress push: on state changes, and once a second while a job is RUNNING. */
    public static void pushProgress(MinecraftServer server, UUID owner, JobStatus status) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            sendProgress(player, status);
        }
    }

    private static void sendOffer(ServerPlayer player, Map<String, Object> offerTree) {
        PacketDistributor.sendToPlayer(player, new BuildOfferPayload(MiniJson.write(offerTree)));
    }

    private static void sendProgress(ServerPlayer player, JobStatus status) {
        PacketDistributor.sendToPlayer(player, new BuildProgressPayload(MiniJson.write(ProgressView.tree(status))));
    }

    private static ConstructionRuntime runtimeOf(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? null : ConstructionRuntime.of(server).orElse(null);
    }
}
