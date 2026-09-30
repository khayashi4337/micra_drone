package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.construction.core.ApprovalRejection;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.JobStatus;
import io.github.khayashi4337.micradrone.construction.core.MessageKey;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The adapter edge of the child-facing texts (F-18): every line is built from a {@link MessageKey} the pure
 * core selected, translated by {@link Component#translatable}. Issue codes and ids never reach a child's chat;
 * they are written to the server log (and shown by the debug status command).
 */
public final class ServerMessages {
    private ServerMessages() {
    }

    /** Translates a core {@link MessageKey}; its arguments are already plain strings. */
    public static Component of(MessageKey key) {
        return Component.translatable(key.key(), key.args().toArray());
    }

    /** One issue as the child sees it: the translated text only, never the code or id. */
    public static Component issueLine(Issue issue) {
        return of(ChildMessages.issueLine(issue));
    }

    /** The {@code status.line} view: job id, translated state, cursor/total, and the pause text when paused. */
    public static Component status(JobStatus status) {
        Component state = Component.translatable(ChildMessages.state(status.state()));
        Object pause = status.shownPause() == null ? "" : Component.translatable(ChildMessages.pause(status.shownPause()));
        return Component.translatable(ChildMessages.STATUS_LINE, status.jobId(), state, status.cursor(), status.total(),
                pause);
    }

    /** The rejection's own line, then one child-facing line per blocking issue. */
    public static Component rejection(ApprovalRejection rejection, List<Issue> issues) {
        MutableComponent out = Component.translatable(ChildMessages.rejection(rejection));
        for (Issue issue : issues) {
            out.append("\n").append(issueLine(issue));
        }
        return out;
    }

    public static void send(ServerPlayer to, MessageKey key) {
        if (to != null && key != null) {
            to.sendSystemMessage(of(key));
        }
    }

    public static void send(ServerPlayer to, Component component) {
        if (to != null && component != null) {
            to.sendSystemMessage(component);
        }
    }

    /** Sends to a player who may have logged out meanwhile: resolved against the live player list. */
    public static void send(MinecraftServer server, UUID playerId, MessageKey key) {
        if (server != null && playerId != null && key != null) {
            send(server.getPlayerList().getPlayer(playerId), key);
        }
    }

    /** The {@link Component} variant for lines built of nested translations (a status line's state, say). */
    public static void send(MinecraftServer server, UUID playerId, Component component) {
        if (server != null && playerId != null && component != null) {
            send(server.getPlayerList().getPlayer(playerId), component);
        }
    }

    /**
     * Writes every issue once to the server log with its code-bearing id, then sends the child-facing line.
     * The log keeps the detail the chat line omits on purpose.
     */
    public static void sendIssues(MinecraftServer server, UUID playerId, List<Issue> issues) {
        for (Issue issue : issues) {
            MicraDrone.LOGGER.info("construction issue {}: {}", issue.id(), issue.message());
            send(server, playerId, MessageKey.of(ChildMessages.issue(issue.code())));
        }
    }
}
