package dev.craftcompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
        public int timeoutSeconds = 180;
        /** Add position, held item, health, biome and recent chat to each message. */
        public boolean includeGameContext = true;
        /** Let Claude search the web and read web pages (wikis, guides). */
        public boolean allowWeb = true;
        /** Let Claude propose config/datapack changes. Nothing changes until you answer Y. */
        public boolean allowEdits = true;
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

    public enum Mode { CHAT, COMPACT }

    public record Reply(boolean ok, String text) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path gameDir;
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

    public ClaudeRunner(Path gameDir, Path chatDir, Path configFile) {
        this.gameDir = gameDir;
        this.chatDir = chatDir;
        this.configFile = configFile;
        this.stateFile = chatDir.resolve("state.json");
    }

    public Config config() { return config; }
    public Path chatDir() { return chatDir; }
    public ExecutorService worker() { return worker; }

    // ---------------------------------------------------------------- setup

    /** Loads config and state. Call once at startup. */
    public void load() {
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

    /**
     * Sends a message. Compacts afterwards in the background when due.
     * @param progress short status lines ("Searching recipes...") as Claude works
     */
    public CompletableFuture<Reply> ask(String prompt, Mode mode, Consumer<String> progress) {
        return CompletableFuture.supplyAsync(() -> {
            Reply r = run(prompt, mode, progress, true);
            if (r.ok()) {
                state.messagesSinceCompact++;
                state.totalMessages++;
                saveState();
                if (config.compactEvery > 0 && state.messagesSinceCompact >= config.compactEvery) {
                    worker.submit(() -> {
                        progress.accept("Compacting the conversation to save usage...");
                        Reply c = doCompact();
                        progress.accept(c.ok() ? "Compacted." : "Compact failed: " + c.text());
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
                + " | web: " + (config.allowWeb ? "on" : "off")
                + " | edits: " + (config.allowEdits ? "on" : "off")
                + " | messages since compact: " + state.messagesSinceCompact + "/" + config.compactEvery
                + " | conversation: " + (state.sessionId == null ? "new" : state.sessionId.substring(0, 8));
    }

    public void shutdown() { worker.shutdownNow(); }

    // ---------------------------------------------------------------- internals

    private Reply doCompact() {
        if (state.sessionId == null) return new Reply(true, "Nothing to compact yet.");
        Reply r = run("/compact", Mode.COMPACT, s -> {}, false);
        if (r.ok()) {
            state.messagesSinceCompact = 0;
            saveState();
        }
        return r;
    }

    /** Game folders Claude may read (and, in edit mode, stage changes for). */
    public List<Path> modFolders() {
        List<Path> out = new ArrayList<>();
        for (String name : StagedEdits.ROOTS) {
            Path p = gameDir.resolve(name);
            if (Files.isDirectory(p)) out.add(p.toAbsolutePath().normalize());
        }
        return out;
    }

    private List<String> buildCommand(String exe, Mode mode) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-p");
        cmd.add("--model");
        cmd.add(config.model);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--strict-mcp-config"); // don't load the user's MCP servers
        if (state.sessionId != null) {
            cmd.add("--resume");
            cmd.add(state.sessionId);
        }
        if (mode == Mode.COMPACT) {
            cmd.add("--tools");
            cmd.add("");
            return cmd;
        }
        List<Path> dirs = modFolders();
        if (!dirs.isEmpty()) {
            cmd.add("--add-dir"); // read-only: writes there are never allowed
            for (Path d : dirs) cmd.add(d.toString());
        }
        // Edit(CLAUDE.md): Claude may add notes to its own memory file when asked to remember something.
        List<String> tools = new ArrayList<>(List.of("Read", "Grep", "Glob", "Edit"));
        List<String> allowed = new ArrayList<>(List.of("Read", "Grep", "Glob", "Edit(CLAUDE.md)"));
        if (config.allowWeb) {
            tools.addAll(List.of("WebSearch", "WebFetch"));
            allowed.addAll(List.of("WebSearch", "WebFetch"));
        }
        if (config.allowEdits) { // Claude may stage changes; the player confirms before anything is applied
            tools.add("Write");
            allowed.addAll(List.of("Write(staged/**)", "Edit(staged/**)"));
        }
        cmd.add("--allowedTools");
        cmd.add(String.join(",", allowed));
        cmd.add("--tools");
        cmd.add(String.join(",", tools));
        return cmd;
    }

    private Reply run(String stdinText, Mode mode, Consumer<String> progress, boolean retryFresh) {
        String exe = findClaude();
        if (exe == null) {
            return new Reply(false, "Claude Code not found. Set claudePath in config/craftcompanion.json.");
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(exe, mode));
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
        try (OutputStream in = proc.getOutputStream()) {
            in.write(stdinText.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            proc.destroyForcibly();
            return new Reply(false, "Could not send message: " + e.getMessage());
        }

        // Read the event stream as it arrives so we can show progress.
        CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> {
            JsonObject last = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonObject ev = parseObject(line);
                    if (ev == null) continue;
                    String type = str(ev, "type");
                    if ("result".equals(type)) last = ev;
                    else if ("assistant".equals(type)) reportTools(ev, progress);
                }
            } catch (IOException ignored) {
            }
            return last;
        });

        try {
            if (!proc.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new Reply(false, "Timed out after " + config.timeoutSeconds + "s.");
            }
            JsonObject o = result.get(10, TimeUnit.SECONDS);
            if (o == null) {
                String err = readErrorLog();
                String lower = err.toLowerCase();
                // Probably a stale --resume id. Start a fresh conversation once.
                if (retryFresh && state.sessionId != null
                        && (lower.contains("session") || lower.contains("conversation"))) {
                    state.sessionId = null;
                    state.messagesSinceCompact = 0;
                    saveState();
                    return run(stdinText, mode, progress, false);
                }
                return new Reply(false, err.length() > 300 ? err.substring(0, 300) + "..." : err);
            }
            String sid = str(o, "session_id");
            if (sid != null) {
                state.sessionId = sid;
                saveState();
            }
            boolean isError = o.has("is_error") && o.get("is_error").getAsBoolean();
            String text = str(o, "result");
            return new Reply(!isError, text == null ? "" : text);
        } catch (Exception e) {
            proc.destroyForcibly();
            return new Reply(false, "Error: " + e.getMessage());
        }
    }

    /** Turns tool calls into short status lines for the chat. */
    private static void reportTools(JsonObject ev, Consumer<String> progress) {
        JsonObject msg = ev.has("message") && ev.get("message").isJsonObject() ? ev.getAsJsonObject("message") : null;
        if (msg == null || !msg.has("content") || !msg.get("content").isJsonArray()) return;
        JsonArray content = msg.getAsJsonArray("content");
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject b = el.getAsJsonObject();
            if (!"tool_use".equals(str(b, "type"))) continue;
            String name = str(b, "name");
            JsonObject input = b.has("input") && b.get("input").isJsonObject() ? b.getAsJsonObject("input") : new JsonObject();
            String line = switch (name == null ? "" : name) {
                case "Grep", "Glob" -> {
                    String p = String.valueOf(str(input, "path")) + String.valueOf(str(input, "pattern"));
                    yield p.contains("recipes") ? "Searching recipes..." : p.contains("items") ? "Looking up items..." : "Searching files...";
                }
                case "Read" -> "Reading " + shortName(str(input, "file_path")) + "...";
                case "WebSearch" -> "Searching the web: " + str(input, "query");
                case "WebFetch" -> "Reading " + host(str(input, "url")) + "...";
                case "Write", "Edit" -> "Preparing a change to " + shortName(str(input, "file_path")) + "...";
                default -> null;
            };
            if (line != null) progress.accept(line);
        }
    }

    private static String shortName(String path) {
        if (path == null) return "a file";
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String host(String url) {
        if (url == null) return "a web page";
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "a web page";
        }
    }

    private static JsonObject parseObject(String line) {
        line = line.trim();
        if (!line.startsWith("{")) return null;
        try {
            JsonElement el = JsonParser.parseString(line);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private String readErrorLog() {
        try {
            String s = Files.readString(chatDir.resolve("last-error.log")).trim();
            return s.isEmpty() ? "No reply from Claude Code." : s;
        } catch (IOException e) {
            return "No reply from Claude Code.";
        }
    }
}
