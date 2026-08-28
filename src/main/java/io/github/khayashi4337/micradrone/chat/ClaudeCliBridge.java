package io.github.khayashi4337.micradrone.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Spawns claude -p as a subprocess and talks JSON over stdin/stdout - the mechanics SPK-1/SPK-2
 * (docs/investigations/) verified by hand before this class existed. Never touches the render
 * thread: {@link #send} runs the whole round trip on a background executor, matching this
 * project's established worker-thread convention (DroneScriptRunner).
 */
public final class ClaudeCliBridge {
    /**
     * Fixed name for our own MCP server entry (see BlockSnapshotToolServer), so the allowlist below
     * can be a compile-time constant rather than something assembled per call.
     */
    static final String MCP_SERVER_NAME = "micradrone";
    static final String MCP_TOOL_NAME = "get_block_snapshot";
    static final String MCP_ALLOWED_TOOL = "mcp__" + MCP_SERVER_NAME + "__" + MCP_TOOL_NAME;

    /** How long one claude -p round trip may take before the bridge gives up and kills the process. */
    static final long CLI_TIMEOUT_SECONDS = 120;

    /**
     * @param sessionId     the id to pass to --session-id (new) or --resume (continuing)
     * @param isNewSession  true for a fresh session (--session-id), false to resume an existing one
     * @param dangerFlags   DangerModeState#toCliFlags's result for this send
     * @param mcpConfigPath path to the --mcp-config JSON file, or null to skip MCP entirely (e.g.
     *                      before BlockSnapshotToolServer is up)
     */
    public record ClaudeCliOptions(String sessionId, boolean isNewSession, List<String> dangerFlags, String mcpConfigPath) {
        /** A fresh session id and options ready for a controller's very first message. */
        public static ClaudeCliOptions freshSession(List<String> dangerFlags, String mcpConfigPath) {
            return new ClaudeCliOptions(UUID.randomUUID().toString(), true, dangerFlags, mcpConfigPath);
        }
    }

    public record ClaudeCliResult(boolean success, String responseText, String sessionId, String errorMessage) {
    }

    private final String claudeExecutable;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MicraDrone-ClaudeCli");
        t.setDaemon(true);
        return t;
    });

    public ClaudeCliBridge(String claudeExecutable) {
        this.claudeExecutable = claudeExecutable;
    }

    /** The full argv for this call, prompt excluded - the prompt always goes over stdin (SPK-1: a bare argument after --tools "" gets swallowed). */
    static List<String> buildCommand(String claudeExecutable, ClaudeCliOptions options) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudeExecutable);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add(options.isNewSession() ? "--session-id" : "--resume");
        cmd.add(options.sessionId());
        cmd.addAll(options.dangerFlags());
        if (options.mcpConfigPath() != null) {
            cmd.add("--mcp-config");
            cmd.add(options.mcpConfigPath());
            cmd.add("--allowedTools");
            cmd.add(MCP_ALLOWED_TOOL);
        }
        return cmd;
    }

    /**
     * Wraps {@code logicalCommand} for actual process launch. Real-machine testing found
     * {@code ProcessBuilder} fails outright on Windows for a bare "claude" (exit: file not found) -
     * npm installs it as claude.cmd (a batch wrapper) with no claude.exe, and unlike a real shell,
     * ProcessBuilder/CreateProcess does not consult PATHEXT to resolve that. Routing through
     * {@code cmd.exe /c} makes the launch behave exactly like a player typing "claude ..." at a
     * prompt, on whatever extension the CLI actually ships as - not just today's .cmd wrapper.
     */
    private static List<String> launchCommand(List<String> logicalCommand) {
        return launchCommand(logicalCommand, System.getProperty("os.name", ""));
    }

    static List<String> launchCommand(List<String> logicalCommand, String osName) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return logicalCommand;
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(logicalCommand);
        return wrapped;
    }

    public CompletableFuture<ClaudeCliResult> send(String prompt, ClaudeCliOptions options) {
        return CompletableFuture.supplyAsync(() -> runProcess(prompt, options), executor);
    }

    private ClaudeCliResult runProcess(String prompt, ClaudeCliOptions options) {
        List<String> command = launchCommand(buildCommand(claudeExecutable, options));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            // Drain stdout and stderr on their own threads from the moment the process starts.
            // Reading them one after another on this thread (the earlier shape) had two real
            // failure modes: a hung CLI blocked readAll() forever, so the waitFor timeout below never
            // got its turn and the chat's Send button stayed disabled for the rest of the session;
            // and a chatty stderr could fill its pipe and deadlock the child while stdout was still
            // being read. The executor is a cached pool, so submitting from inside a task is fine.
            Future<String> stdout = executor.submit(() -> readAll(process.getInputStream()));
            Future<String> stderr = executor.submit(() -> readAll(process.getErrorStream()));
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException childExitedBeforeReadingStdin) {
                // Fall through: the exit code and stderr below say why, far better than "pipe closed".
            }
            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ClaudeCliResult(false, null, null, "claude CLI timed out after " + CLI_TIMEOUT_SECONDS + "s");
            }
            String out = stdout.get();
            String err = stderr.get();
            if (process.exitValue() != 0) {
                return new ClaudeCliResult(false, null, null, "claude CLI exited " + process.exitValue() + ": " + err);
            }
            return ClaudeCliJson.parseResult(out);
        } catch (IOException | InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ClaudeCliResult(false, null, null, String.valueOf(e.getMessage()));
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
