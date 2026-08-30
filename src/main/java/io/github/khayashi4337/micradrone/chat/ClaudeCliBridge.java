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
    /** {@code claude --version} is instant; anything longer than this means it isn't there in any usable form. */
    static final long PROBE_TIMEOUT_SECONDS = 15;
    /** cmd.exe's exit code for a command it couldn't find - what a missing claude.cmd looks like on Windows. */
    static final int WINDOWS_COMMAND_NOT_FOUND_EXIT = 9009;
    /**
     * What the player sees instead of a raw exit code when the CLI simply isn't installed. The mod's
     * AI tab is a thin client for the player's own Claude Code; without it the rest of the mod
     * works exactly as before, so the message says how to get it rather than just "failed".
     */
    public static final String CLI_NOT_FOUND_MESSAGE =
            "claude CLI not found - install Claude Code (npm install -g @anthropic-ai/claude-code), run 'claude login', then reopen Chat";

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
        /** The errorMessage of a round trip the player stopped with {@link #cancel} - not a real failure. */
        static final String CANCELLED_MESSAGE = "cancelled";

        static ClaudeCliResult cancelled() {
            return new ClaudeCliResult(false, null, null, CANCELLED_MESSAGE);
        }

        public boolean isCancelled() {
            return !success && CANCELLED_MESSAGE.equals(errorMessage);
        }
    }

    /** The process of the round trip in flight, if any - what {@link #cancel} kills. */
    private volatile Process inFlight;
    private volatile boolean cancelRequested;

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
     *
     * <p>The command is built as a single properly-quoted string and passed as one argument to
     * {@code cmd.exe /c}, wrapped in an extra pair of outer quotes. ProcessBuilder's list form
     * joins args with spaces without quoting individual ones, so a path containing cmd.exe
     * metacharacters ({@code &}, {@code |}, {@code >}, …) would be interpreted as shell syntax
     * (command injection). The quoting here ensures every arg survives cmd.exe's parser intact.
     */
    private static List<String> launchCommand(List<String> logicalCommand) {
        return launchCommand(logicalCommand, System.getProperty("os.name", ""));
    }

    static List<String> launchCommand(List<String> logicalCommand, String osName) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return logicalCommand;
        }
        StringBuilder cmdline = new StringBuilder();
        for (String arg : logicalCommand) {
            if (!cmdline.isEmpty()) {
                cmdline.append(' ');
            }
            cmdline.append(quoteForCmd(arg));
        }
        // Outer quotes: cmd.exe /c strips the first and last quote when the string starts with one
        // (rule 2 of its /C quote handling), which preserves the inner per-arg quoting intact.
        return List.of("cmd.exe", "/c", "\"" + cmdline + "\"");
    }

    /** Characters cmd.exe treats as syntax outside of double quotes. */
    private static final String CMD_SPECIAL_CHARS = "&|<>()^%!\" \t";

    /**
     * Wraps {@code arg} in double quotes if it contains any cmd.exe metacharacter, doubling
     * internal double quotes for cmd.exe's escaping convention. Args without special chars are
     * returned as-is so the command line stays readable in logs.
     */
    private static String quoteForCmd(String arg) {
        if (arg.isEmpty()) {
            return "\"\"";
        }
        if (arg.chars().noneMatch(c -> CMD_SPECIAL_CHARS.indexOf(c) >= 0)) {
            return arg;
        }
        return "\"" + arg.replace("\"", "\"\"") + "\"";
    }

    public CompletableFuture<ClaudeCliResult> send(String prompt, ClaudeCliOptions options) {
        cancelRequested = false;
        return CompletableFuture.supplyAsync(() -> runProcess(prompt, options), executor);
    }

    /**
     * Runs {@code claude --version} once: the version string if the CLI is installed and runnable,
     * empty if not. The chat panel calls this when it opens so a missing install is announced up
     * front, before the player types a question into the void.
     */
    public CompletableFuture<java.util.Optional<String>> probeVersion() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> command = launchCommand(List.of(claudeExecutable, "--version"));
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                Future<String> output = executor.submit(() -> readAll(process.getInputStream()));
                if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    output.cancel(true);
                    return java.util.Optional.<String>empty();
                }
                String text = output.get().trim();
                return process.exitValue() == 0 && !text.isEmpty()
                        ? java.util.Optional.of(text) : java.util.Optional.<String>empty();
            } catch (IOException | InterruptedException | ExecutionException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return java.util.Optional.<String>empty();
            }
        }, executor);
    }

    /**
     * True for the ways "there is no claude command" surfaces. Real-machine finding: on a Japanese
     * Windows, {@code cmd.exe /c} exits with 1 (not the documented 9009) and prints its message in
     * the OEM code page, which decodes as mojibake here - so neither the exit code nor the
     * localized text can be relied on. What every cmd.exe locale does have in common is quoting
     * the offending name in ASCII: {@code 'claude' は、内部コマンド...} / {@code 'claude' is not
     * recognized...}. That quoted-name check, with no stdout at all, is the portable signal.
     */
    static boolean looksLikeCliNotFound(int exitCode, String stderrOrMessage, String executable) {
        if (exitCode == WINDOWS_COMMAND_NOT_FOUND_EXIT) {
            return true;
        }
        String text = stderrOrMessage == null ? "" : stderrOrMessage;
        if (text.contains("error=2") || text.contains("No such file") || text.contains("not recognized")
                || text.contains("認識されていません")) {
            return true;
        }
        return exitCode != 0 && text.contains("'" + executable + "'");
    }

    /**
     * Stops the round trip in flight (Esc in the chat panel): its future then completes with
     * {@link ClaudeCliResult#isCancelled}. Kills the whole tree, not just the top process - on
     * Windows the launch goes through cmd.exe (see launchCommand), and destroying cmd.exe alone
     * would leave the actual node/claude child running to completion.
     */
    public void cancel() {
        cancelRequested = true;
        Process process = inFlight;
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * Shuts down the background executor and cancels any in-flight round trip. Called when the
     * IDE screen that owns this bridge is closed, so repeated open/close cycles don't accumulate
     * idle threads (the cached pool keeps them alive otherwise).
     */
    public void close() {
        cancel();
        executor.shutdownNow();
    }

    private ClaudeCliResult runProcess(String prompt, ClaudeCliOptions options) {
        List<String> command = launchCommand(buildCommand(claudeExecutable, options));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            inFlight = process;
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
            inFlight = null;
            if (cancelRequested) {
                return ClaudeCliResult.cancelled();
            }
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new ClaudeCliResult(false, null, null, "claude CLI timed out after " + CLI_TIMEOUT_SECONDS + "s");
            }
            String out = stdout.get();
            String err = stderr.get();
            if (process.exitValue() != 0) {
                if (looksLikeCliNotFound(process.exitValue(), err, claudeExecutable)) {
                    return new ClaudeCliResult(false, null, null, CLI_NOT_FOUND_MESSAGE);
                }
                return new ClaudeCliResult(false, null, null, "claude CLI exited " + process.exitValue() + ": " + err);
            }
            return ClaudeCliJson.parseResult(out);
        } catch (IOException | InterruptedException | ExecutionException e) {
            inFlight = null;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cancelRequested) {
                return ClaudeCliResult.cancelled();
            }
            if (looksLikeCliNotFound(-1, e.getMessage(), claudeExecutable)) {
                return new ClaudeCliResult(false, null, null, CLI_NOT_FOUND_MESSAGE);
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
