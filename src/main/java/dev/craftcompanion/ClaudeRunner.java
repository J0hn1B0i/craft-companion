package dev.craftcompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs Claude Code headlessly ("claude -p") and keeps one ongoing conversation.
 * Has no Minecraft dependencies so it can be tested on its own.
 */
public final class ClaudeRunner {

    /** Saved to config/craftcompanion.json. */
    public static final class Config {
        /** Full path to claude.exe. Empty = find it automatically. */
        public String claudePath = "";
        /** Model alias passed to --model. */
        public String model = "haiku";
        /** Run /compact after this many messages. 0 = never. */
        public int compactEvery = 20;
        /** Give up on a reply after this many seconds. */
        public int timeoutSeconds = 120;
        /** Add position, held item, health, biome etc. to each message. */
        public boolean includeGameContext = true;
        /**
         * false (default) = hide ANTHROPIC_API_KEY from Claude Code so it uses your signed-in
         * Claude plan. true = let Claude Code use the key (pay-per-use API billing).
         */
        public boolean allowApiKey = false;
    }

    /** Saved to craft-companion/state.json. */
    static final class State {
        String sessionId;
        int messagesSinceCompact;
        int totalMessages;
    }

    public record Reply(boolean ok, String text) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path chatDir;
    private final Path configFile;
    private final Path stateFile;
    private Config config = new Config();
    private State state = new State();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "craft-companion-worker");
        t.setDaemon(true);
        return t;
    });

    public ClaudeRunner(Path chatDir, Path configFile) {
        this.chatDir = chatDir;
        this.configFile = configFile;
        this.stateFile = chatDir.resolve("state.json");
    }

    public Config config() { return config; }
    public Path chatDir() { return chatDir; }

    // ---------------------------------------------------------------- setup

    /**
     * @param defaultClaudeMd written to CLAUDE.md only if it doesn't exist yet
     * @param modsMd          written to mods.md every time (null = leave alone)
     */
    public void load(String defaultClaudeMd, String modsMd) {
        try {
            Files.createDirectories(chatDir);
            if (Files.exists(configFile)) {
                Config c = GSON.fromJson(Files.readString(configFile), Config.class);
                if (c != null) config = c;
            }
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(config)); // writes any new defaults
            if (Files.exists(stateFile)) {
                State s = GSON.fromJson(Files.readString(stateFile), State.class);
                if (s != null) state = s;
            }
            Path claudeMd = chatDir.resolve("CLAUDE.md");
            if (!Files.exists(claudeMd)) Files.writeString(claudeMd, defaultClaudeMd);
            if (modsMd != null) Files.writeString(chatDir.resolve("mods.md"), modsMd);
        } catch (Exception e) {
            System.err.println("[craftcompanion] failed to load config: " + e);
        }
    }

    private void saveState() {
        try {
            Files.writeString(stateFile, GSON.toJson(state));
        } catch (IOException e) {
            System.err.println("[craftcompanion] failed to save state: " + e);
        }
    }

    /** Finds claude.exe: config, then PATH, then ~/.local/bin, then the desktop app's bundled copy. */
    public String findClaude() {
        if (config.claudePath != null && !config.claudePath.isBlank()
                && new File(config.claudePath).isFile()) {
            return config.claudePath;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = win ? "claude.exe" : "claude";

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, exe);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        File local = Paths.get(System.getProperty("user.home"), ".local", "bin", exe).toFile();
        if (local.isFile()) return local.getAbsolutePath();

        // Claude desktop app: %LOCALAPPDATA%\Claude-Data\<id>\claude-code\<version>\<hash>\claude.exe
        String lad = System.getenv("LOCALAPPDATA");
        if (lad != null) {
            Path root = Paths.get(lad, "Claude-Data");
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.find(root, 5,
                        (p, a) -> a.isRegularFile() && p.getFileName().toString().equalsIgnoreCase(exe)
                                && p.toString().contains("claude-code"))) {
                    return s.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                            .map(Path::toString).orElse(null);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- public API

    /** Sends a message. Compacts afterwards in the background when due. */
    public CompletableFuture<Reply> ask(String prompt, Consumer<String> notice) {
        return CompletableFuture.supplyAsync(() -> {
            Reply r = run(prompt, true);
            if (r.ok()) {
                state.messagesSinceCompact++;
                state.totalMessages++;
                saveState();
                if (config.compactEvery > 0 && state.messagesSinceCompact >= config.compactEvery) {
                    worker.submit(() -> {
                        notice.accept("Compacting the conversation to save usage...");
                        Reply c = doCompact();
                        notice.accept(c.ok() ? "Compacted." : "Compact failed: " + c.text());
                    });
                }
            }
            return r;
        }, worker);
    }

    public CompletableFuture<Reply> compact() {
        return CompletableFuture.supplyAsync(this::doCompact, worker);
    }

    /** Forgets the conversation. CLAUDE.md is kept. */
    public CompletableFuture<Void> reset() {
        return CompletableFuture.runAsync(() -> {
            state = new State();
            saveState();
        }, worker);
    }

    public String status() {
        String exe = findClaude();
        return "claude: " + (exe == null ? "NOT FOUND" : exe)
                + " | model: " + config.model
                + " | messages since compact: " + state.messagesSinceCompact + "/" + config.compactEvery
                + " | conversation: " + (state.sessionId == null ? "new" : state.sessionId.substring(0, 8));
    }

    public void shutdown() { worker.shutdownNow(); }

    // ---------------------------------------------------------------- internals

    private Reply doCompact() {
        if (state.sessionId == null) return new Reply(true, "Nothing to compact yet.");
        Reply r = run("/compact", false);
        if (r.ok()) {
            state.messagesSinceCompact = 0;
            saveState();
        }
        return r;
    }

    private Reply run(String stdinText, boolean retryFresh) {
        String exe = findClaude();
        if (exe == null) {
            return new Reply(false, "Claude Code not found. Set claudePath in config/craftcompanion.json.");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-p");
        cmd.add("--model");
        cmd.add(config.model);
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--strict-mcp-config"); // don't load the user's MCP servers
        if (state.sessionId != null) {
            cmd.add("--resume");
            cmd.add(state.sessionId);
        }
        cmd.add("--tools"); // no tools: chat only. Must stay last (it takes a list).
        cmd.add("");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(chatDir.toFile());
        pb.redirectError(chatDir.resolve("last-error.log").toFile());
        Map<String, String> env = pb.environment();
        if (!config.allowApiKey) env.remove("ANTHROPIC_API_KEY"); // use the signed-in plan

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return new Reply(false, "Could not start Claude Code: " + e.getMessage());
        }
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(proc.getInputStream()));
        try (OutputStream in = proc.getOutputStream()) {
            in.write(stdinText.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            proc.destroyForcibly();
            return new Reply(false, "Could not send message: " + e.getMessage());
        }

        try {
            if (!proc.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new Reply(false, "Timed out after " + config.timeoutSeconds + "s.");
            }
            String json = out.get(10, TimeUnit.SECONDS).trim();
            return parse(json, stdinText, retryFresh);
        } catch (Exception e) {
            proc.destroyForcibly();
            return new Reply(false, "Error: " + e.getMessage());
        }
    }

    private Reply parse(String json, String stdinText, boolean retryFresh) {
        int start = json.indexOf('{');
        JsonObject o = null;
        if (start >= 0) {
            try {
                JsonElement el = JsonParser.parseString(json.substring(start));
                if (el.isJsonObject()) o = el.getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        if (o == null) {
            // Probably a stale --resume id. Start a fresh conversation once.
            String err = readErrorLog().toLowerCase();
            if (retryFresh && state.sessionId != null
                    && (err.contains("session") || err.contains("conversation"))) {
                state.sessionId = null;
                state.messagesSinceCompact = 0;
                saveState();
                return run(stdinText, false);
            }
            String msg = json.isEmpty() ? readErrorLog() : json;
            return new Reply(false, msg.length() > 300 ? msg.substring(0, 300) + "..." : msg);
        }
        if (o.has("session_id") && !o.get("session_id").isJsonNull()) {
            state.sessionId = o.get("session_id").getAsString();
            saveState();
        }
        boolean isError = o.has("is_error") && o.get("is_error").getAsBoolean();
        String result = o.has("result") && !o.get("result").isJsonNull() ? o.get("result").getAsString() : "";
        return new Reply(!isError, result);
    }

    private String readErrorLog() {
        try {
            String s = Files.readString(chatDir.resolve("last-error.log")).trim();
            return s.isEmpty() ? "No reply from Claude Code." : s;
        } catch (IOException e) {
            return "No reply from Claude Code.";
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
