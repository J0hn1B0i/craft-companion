package dev.craftcompanion;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CraftCompanionClient implements ClientModInitializer {

    private static final Set<String> SKIP_IDS = Set.of("minecraft", "java", "fabricloader", "fabric-api", "craftcompanion");
    private static final Pattern MARKER = Pattern.compile("\\[\\[(recipe|uses):([a-z0-9_.-]+:[a-z0-9_./-]+)]]");
    private static final Pattern UNDO_MARKER = Pattern.compile("\\[\\[undo]]");
    private static final Pattern NAME_MARKER = Pattern.compile("\\[\\[name:([^\\]]{1,24})]]");
    private static final long QUESTION_MAX_AGE_MS = 5 * 60 * 1000;
    private static final int CHAT_LINES = 10;
    private static final long CHAT_MAX_AGE_MS = 10 * 60 * 1000;
    private static final int LOG_MAX = 200;

    private static ClaudeRunner runner;
    private static StagedEdits edits;
    private static boolean emi;
    private static KeyBinding talkKey;

    private record ChatLine(long time, String text) {}
    private static final Deque<ChatLine> recentChat = new ArrayDeque<>();

    /** An action Claude asked for, waiting for the player's Y or N. */
    private record Question(String text, Runnable onYes, Runnable onNo) {}
    private static final Deque<Question> questions = new ArrayDeque<>();
    private static long questionShownAt;
    /** What happened to Claude's requests since its last reply; sent with the next message. */
    private static final List<String> outcomes = new CopyOnWriteArrayList<>();

    /** Everything said in this session, shown in the buddy panel. Client thread only. */
    private static final List<Text> log = new ArrayList<>();
    /** True while the latest request came from the panel: replies go there instead of chat. */
    private static boolean panelMode;
    private static boolean summoned;
    private static int busy;

    /** Work to run some ticks later. Client thread only. */
    private static final class Delayed {
        int ticks;
        final Runnable task;
        Delayed(int ticks, Runnable task) { this.ticks = ticks; this.task = task; }
    }
    private static final List<Delayed> delayed = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path chatDir = gameDir.resolve("craft-companion");
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("craftcompanion.json");
        runner = new ClaudeRunner(gameDir, chatDir, configFile);
        runner.load();
        edits = new StagedEdits(gameDir, chatDir);
        emi = FabricLoader.getInstance().isModLoaded("emi");
        writeContextFiles(gameDir, chatDir);

        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.craftcompanion.talk",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, "key.categories.craftcompanion"));
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> BuddyHud.render(ctx));

        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> runner.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            for (Iterator<Delayed> it = new ArrayList<>(delayed).iterator(); it.hasNext(); ) {
                Delayed d = it.next();
                if (--d.ticks <= 0) {
                    delayed.remove(d);
                    d.task.run();
                }
            }
            while (talkKey.wasPressed()) {
                if (c.currentScreen == null && c.player != null) {
                    captureView(); // before the panel covers part of the screen
                    summoned = true;
                    c.setScreen(new BuddyScreen());
                }
            }
        });
        // While Claude is waiting for an answer, a plain "y" or "n" typed in chat answers it
        // instead of being sent to the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            Boolean yes = parseAnswer(message);
            if (yes == null || !hasQuestion()) return true;
            MinecraftClient.getInstance().execute(() -> answer(yes));
            return false;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                remember((overlay ? "(action bar) " : "") + message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                remember(message.getString()));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("claude")
                        .executes(ctx -> {
                            String key = talkKey.getBoundKeyLocalizedText().getString();
                            chat(Text.literal("Press " + key + " to talk to " + buddyName()
                                    + ", or type /claude and what you want. Answer questions with y or n. "
                                    + "Extras: /claude status | compact | reset").formatted(Formatting.GRAY));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            chat(Text.literal(runner.status()).formatted(Formatting.GRAY));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("compact").executes(ctx -> {
                            notice("Compacting the conversation...");
                            runner.compact().thenAccept(r -> postNotice(r.ok() ? "Compacted." : "Compact failed: " + r.text()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            runner.reset().thenRun(() -> postNotice("Started a new conversation. CLAUDE.md is kept."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("y").executes(ctx -> {
                            answer(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("n").executes(ctx -> {
                            answer(false);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("undo").executes(ctx -> {
                            undo();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("recipe")
                                .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            showInViewer(StringArgumentType.getString(ctx, "item"), false);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("uses")
                                .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            showInViewer(StringArgumentType.getString(ctx, "item"), true);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String msg = StringArgumentType.getString(ctx, "message");
                                    // Wait for the chat screen to close so the screenshot shows the world.
                                    later(2, () -> {
                                        captureView();
                                        send(msg, false);
                                    });
                                    return 1;
                                }))));
    }

    // ---------------------------------------------------------------- for the HUD and panel

    static String buddyName() {
        String n = runner.config().buddyName;
        return n == null || n.isBlank() ? "Sprout" : n;
    }

    static boolean isBusy() { return busy > 0; }

    static boolean buddyShown() {
        return summoned || busy > 0 || hasQuestion() || runner.config().buddyAlwaysVisible;
    }

    static void setSummoned(boolean s) { summoned = s; }

    static List<Text> log() { return log; }

    /** A line typed into the panel. */
    static void fromPanel(String text) {
        Boolean yes = parseAnswer(text);
        if (yes != null && hasQuestion()) {
            panelMode = true;
            answer(yes);
            return;
        }
        send(text, true);
    }

    // ---------------------------------------------------------------- context files

    private static void writeContextFiles(Path gameDir, Path chatDir) {
        // Top-level mods only (skip libraries bundled inside other jars and Fabric's own modules).
        List<ModMetadata> mods = FabricLoader.getInstance().getAllMods().stream()
                .filter(m -> m.getContainingMod().isEmpty())
                .map(ModContainer::getMetadata)
                .filter(m -> !SKIP_IDS.contains(m.getId()) && !m.getId().startsWith("fabric-"))
                .sorted(Comparator.comparing(m -> m.getName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String instanceName = gameDir.getFileName() == null ? "Minecraft" : gameDir.getFileName().toString();
        String modsMd = "## Installed mods (" + mods.size() + ")\n"
                + mods.stream().map(m -> m.getName() + " (" + m.getId() + ")").collect(Collectors.joining(", ")) + "\n";
        try {
            Files.createDirectories(chatDir);
            Path claudeMd = chatDir.resolve("CLAUDE.md");
            if (!Files.exists(claudeMd)) {
                Files.writeString(claudeMd, DefaultClaudeMd.claudeMd(instanceName, mcVersion, mods.size()));
            } else {
                DefaultClaudeMd.migrate(claudeMd);
            }
            writeInstructions();
            Files.writeString(chatDir.resolve("mods.md"), modsMd);
        } catch (Exception e) {
            System.err.println("[craftcompanion] couldn't write context files: " + e);
        }
    }

    private static void writeInstructions() {
        try {
            Files.writeString(runner.chatDir().resolve("instructions.md"),
                    DefaultClaudeMd.instructions(runner.config(), emi, "EMI", buddyName()));
        } catch (Exception e) {
            System.err.println("[craftcompanion] couldn't write instructions: " + e);
        }
    }

    // ---------------------------------------------------------------- sending

    private static void send(String message, boolean fromPanel) {
        panelMode = fromPanel;
        say(Text.literal("You: ").formatted(Formatting.AQUA)
                .append(Text.literal(message).formatted(Formatting.WHITE)));
        if (!questions.isEmpty()) {
            questions.clear();
            notice("(Skipped the earlier question.)");
        }
        if (runner.config().allowEdits) {
            runner.worker().execute(() -> {
                try {
                    edits.clear();
                } catch (Exception ignored) {
                }
            });
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        String indexed = RecipeExporter.exportIfNeeded(mc, runner.chatDir(), runner.worker());
        if (indexed != null) notice(indexed);
        if (!fromPanel) notice(buddyName() + " is thinking...");

        StringBuilder prompt = new StringBuilder();
        if (!outcomes.isEmpty()) {
            prompt.append("[Since your last reply: ").append(String.join("; ", outcomes)).append("]\n");
            outcomes.clear();
        }
        if (runner.config().includeGameContext) prompt.append(gameContext()).append('\n');
        prompt.append(message);

        busy++;
        runner.ask(prompt.toString(), ClaudeRunner.Mode.CHAT, CraftCompanionClient::postNotice)
                .thenAccept(CraftCompanionClient::postReply);
    }

    /** Saves the current frame (downscaled) to craft-companion/view.png. Client thread. */
    private static void captureView() {
        if (!runner.config().allowScreenshots) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            NativeImage full = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
            int w = Math.min(1280, full.getWidth());
            int h = Math.max(1, full.getHeight() * w / full.getWidth());
            NativeImage small = new NativeImage(w, h, false);
            full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
            full.close();
            Path out = runner.chatDir().resolve("view.png");
            runner.worker().execute(() -> {
                try {
                    small.writeTo(out);
                } catch (Exception e) {
                    System.err.println("[craftcompanion] couldn't save view: " + e);
                } finally {
                    small.close();
                }
            });
        } catch (Throwable t) {
            System.err.println("[craftcompanion] couldn't capture view: " + t);
        }
    }

    /** Player state, what they're looking at, and recent chat. Client thread. */
    private static String gameContext() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "[Game: not in a world]";
        BlockPos pos = p.getBlockPos();
        String biome = mc.world.getBiome(pos).getKey().map(k -> k.getValue().toString()).orElse("unknown");
        String dim = mc.world.getRegistryKey().getValue().toString();
        long day = mc.world.getTimeOfDay() / 24000L;
        long time = mc.world.getTimeOfDay() % 24000L;
        String tod = time < 12000 ? "day" : time < 13800 ? "dusk" : time < 22200 ? "night" : "dawn";
        StringBuilder sb = new StringBuilder(String.format(
                "[Game: pos %d %d %d, %s, biome %s, main hand %s, off hand %s, health %.0f/%.0f, food %d/20, day %d %s]",
                pos.getX(), pos.getY(), pos.getZ(), dim, biome,
                describe(p.getMainHandStack()), describe(p.getOffHandStack()),
                p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), day, tod));
        String looking = lookingAt(mc);
        if (looking != null) sb.append("\n[Looking at: ").append(looking).append(']');
        if (runner.config().allowScreenshots) sb.append("\n[Screenshot of the player's view: view.png]");
        long now = System.currentTimeMillis();
        synchronized (recentChat) {
            for (ChatLine c : recentChat) {
                long ago = (now - c.time()) / 1000;
                if (now - c.time() > CHAT_MAX_AGE_MS) continue;
                sb.append("\n[Chat ").append(ago < 60 ? ago + "s" : ago / 60 + "m").append(" ago] ").append(c.text());
            }
        }
        return sb.toString();
    }

    /** The entity or block under the crosshair (blocks up to 64 blocks away). */
    private static String lookingAt(MinecraftClient mc) {
        try {
            HitResult hit = mc.crosshairTarget;
            if (hit instanceof EntityHitResult eh) {
                Entity e = eh.getEntity();
                Identifier type = Registries.ENTITY_TYPE.getId(e.getType());
                StringBuilder sb = new StringBuilder(e.getName().getString())
                        .append(" (").append(type).append(", ").append(RecipeExporter.modName(type.getNamespace())).append(")");
                if (e instanceof LivingEntity le) {
                    sb.append(String.format(", health %.0f/%.0f", le.getHealth(), le.getMaxHealth()));
                }
                sb.append(String.format(", %.0f blocks away", Math.sqrt(e.squaredDistanceTo(mc.player))));
                return sb.toString();
            }
            Entity cam = mc.getCameraEntity();
            if (cam == null) return null;
            HitResult far = cam.raycast(64, 1.0f, false);
            if (far instanceof BlockHitResult bh && far.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = bh.getBlockPos();
                BlockState state = mc.world.getBlockState(bp);
                Identifier id = Registries.BLOCK.getId(state.getBlock());
                StringBuilder sb = new StringBuilder(state.getBlock().getName().getString())
                        .append(" (").append(id).append(", ").append(RecipeExporter.modName(id.getNamespace())).append(")")
                        .append(" at ").append(bp.getX()).append(' ').append(bp.getY()).append(' ').append(bp.getZ())
                        .append(String.format(", %.0f blocks away", Math.sqrt(bp.getSquaredDistance(cam.getPos()))));
                String props = state.getEntries().entrySet().stream().limit(6)
                        .map(en -> en.getKey().getName() + "=" + en.getValue())
                        .collect(Collectors.joining(", "));
                if (!props.isEmpty()) sb.append(", state: ").append(props);
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String describe(ItemStack s) {
        if (s.isEmpty()) return "empty";
        Identifier id = Registries.ITEM.getId(s.getItem());
        return s.getCount() + "x " + s.getName().getString() + " (" + id + ", " + RecipeExporter.modName(id.getNamespace()) + ")";
    }

    private static void remember(String text) {
        if (text == null || text.isBlank()) return;
        String t = text.length() > 300 ? text.substring(0, 300) + "..." : text;
        synchronized (recentChat) {
            recentChat.addLast(new ChatLine(System.currentTimeMillis(), t));
            while (recentChat.size() > CHAT_LINES) recentChat.removeFirst();
        }
    }

    // ---------------------------------------------------------------- replies

    private static void postReply(ClaudeRunner.Reply r) {
        List<String[]> markers = new ArrayList<>();
        boolean wantsUndo = false;
        String newName = null;
        String text = r.text();
        if (r.ok()) {
            Matcher m = MARKER.matcher(text);
            while (m.find() && markers.size() < 2) markers.add(new String[]{m.group(1), m.group(2)});
            wantsUndo = UNDO_MARKER.matcher(text).find();
            Matcher nm = NAME_MARKER.matcher(text);
            if (nm.find()) newName = nm.group(1).trim();
            text = NAME_MARKER.matcher(UNDO_MARKER.matcher(MARKER.matcher(text).replaceAll("")).replaceAll(""))
                    .replaceAll("").strip();
        }
        String finalText = cleanMarkdown(text);

        List<String> staged = List.of();
        if (r.ok() && runner.config().allowEdits) {
            try {
                staged = edits.pending();
            } catch (Exception ignored) {
            }
        }
        List<String> stagedFinal = staged;
        boolean undoFinal = wantsUndo;
        String nameFinal = newName;

        MinecraftClient.getInstance().execute(() -> {
            busy = Math.max(0, busy - 1);
            if (!r.ok()) {
                say(Text.literal("[" + buddyName() + "] " + r.text()).formatted(Formatting.RED));
                return;
            }
            if (nameFinal != null && !nameFinal.isEmpty()) {
                runner.config().buddyName = nameFinal;
                runner.saveConfig();
                writeInstructions();
            }
            boolean first = true;
            for (String line : finalText.split("\\R")) {
                if (line.isBlank()) continue;
                MutableText msg = first
                        ? Text.literal(buddyName() + ": ").formatted(Formatting.GOLD).append(Text.literal(line).formatted(Formatting.WHITE))
                        : Text.literal("  " + line).formatted(Formatting.WHITE);
                say(msg);
                first = false;
            }
            if (first && markers.isEmpty() && stagedFinal.isEmpty() && !undoFinal) {
                say(Text.literal(buddyName() + ": (empty reply)").formatted(Formatting.GRAY));
            }

            // Turn what Claude asked for into Y/N questions, asked one at a time.
            if (!stagedFinal.isEmpty()) {
                questions.add(new Question("Apply these changes? " + String.join(", ", stagedFinal)
                        + " (the originals are backed up first)", CraftCompanionClient::apply,
                        () -> {
                            runner.worker().execute(() -> {
                                try {
                                    edits.clear();
                                } catch (Exception ignored) {
                                }
                            });
                            notice("OK, nothing was changed.");
                            outcomes.add("player answered N, staged changes discarded");
                        }));
            }
            if (undoFinal) {
                questions.add(new Question("Undo the last applied change?", CraftCompanionClient::undo,
                        () -> {
                            notice("OK, nothing was undone.");
                            outcomes.add("player answered N to undo");
                        }));
            }
            if (emi) {
                for (String[] mk : markers) {
                    boolean uses = mk[0].equals("uses");
                    String name = itemName(mk[1]);
                    if (name == null) continue;
                    questions.add(new Question((uses ? "Show what " + name + " is used in" : "Show the " + name + " recipe")
                            + " in EMI?", () -> {
                        showInViewer(mk[1], uses);
                        outcomes.add("player opened " + mk[1] + (uses ? " uses" : " recipe") + " in EMI");
                    }, () -> outcomes.add("player declined opening " + mk[1] + " in EMI")));
                }
            }
            askNext();
        });
    }

    // ---------------------------------------------------------------- Y/N questions

    static boolean hasQuestion() {
        if (questions.isEmpty()) return false;
        if (System.currentTimeMillis() - questionShownAt > QUESTION_MAX_AGE_MS) {
            questions.clear();
            return false;
        }
        return true;
    }

    private static void askNext() {
        Question q = questions.peekFirst();
        if (q == null) return;
        questionShownAt = System.currentTimeMillis();
        MutableText line = Text.literal(buddyName() + " asks: ").formatted(Formatting.YELLOW)
                .append(Text.literal(q.text()).formatted(Formatting.WHITE));
        log(line);
        if (!panelMode) {
            chat(line.copy()
                    .append(Text.literal("  Type ").formatted(Formatting.GRAY))
                    .append(Text.literal("y").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(" or ").formatted(Formatting.GRAY))
                    .append(Text.literal("n").formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal("  "))
                    .append(button("[Yes]", Formatting.GREEN, "/claude y", "Yes"))
                    .append(Text.literal(" "))
                    .append(button("[No]", Formatting.RED, "/claude n", "No")));
        }
    }

    static void answer(boolean yes) {
        if (!hasQuestion()) {
            notice(buddyName() + " isn't waiting for an answer.");
            return;
        }
        Question q = questions.pollFirst();
        log(Text.literal("You: " + (yes ? "yes" : "no")).formatted(Formatting.AQUA));
        (yes ? q.onYes() : q.onNo()).run();
        askNext();
    }

    /** "y", "yes", "n", "no" (any case, optional punctuation) or null. */
    private static Boolean parseAnswer(String message) {
        String m = message.trim().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        return switch (m) {
            case "y", "yes", "yeah", "yep", "sure", "ok", "okay" -> Boolean.TRUE;
            case "n", "no", "nope", "nah" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String itemName(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        return new ItemStack(Registries.ITEM.get(id)).getName().getString();
    }

    private static MutableText button(String label, Formatting color, String command, String hover) {
        return Text.literal(label).styled(s -> s.withColor(color).withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }

    // ---------------------------------------------------------------- edits

    private static void apply() {
        runner.worker().execute(() -> {
            try {
                List<String> files = edits.apply();
                if (files.isEmpty()) {
                    postNotice("Nothing to apply.");
                    return;
                }
                postNotice("Applied " + files.size() + " file(s): " + String.join(", ", files));
                outcomes.add("player answered Y; applied (originals backed up, undo possible): " + String.join(", ", files));
                postNotice("Originals are backed up, so you can ask to undo it. Config changes usually need a "
                        + "restart; datapack changes need /reload.");
            } catch (Exception e) {
                postError("Couldn't apply: " + e.getMessage());
            }
        });
    }

    private static void undo() {
        runner.worker().execute(() -> {
            try {
                Optional<List<String>> r = edits.undo();
                if (r.isEmpty()) postNotice("Nothing to undo.");
                else {
                    postNotice("Restored: " + String.join(", ", r.get()) + ". Restart or /reload to see it.");
                    outcomes.add("last applied change was undone: " + String.join(", ", r.get()));
                }
            } catch (Exception e) {
                postError("Couldn't undo: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- recipe viewer

    private static void showInViewer(String itemId, boolean uses) {
        if (!emi) {
            say(Text.literal("EMI isn't installed, so recipes can't be opened.").formatted(Formatting.RED));
            return;
        }
        Identifier id = Identifier.tryParse(itemId.trim().toLowerCase(Locale.ROOT));
        if (id == null || !Registries.ITEM.containsId(id)) {
            say(Text.literal("Unknown item: " + itemId).formatted(Formatting.RED));
            return;
        }
        ItemStack stack = new ItemStack(Registries.ITEM.get(id));
        if (stack.isEmpty() || stack.isOf(Items.AIR)) return;
        // Wait two ticks so the chat screen or panel has closed before EMI opens its screen.
        later(2, () -> {
            try {
                EmiCompat.show(stack, uses);
            } catch (Throwable t) {
                say(Text.literal("Couldn't open EMI: " + t.getMessage()).formatted(Formatting.RED));
            }
        });
    }

    // ---------------------------------------------------------------- output helpers

    private static void later(int ticks, Runnable task) {
        MinecraftClient.getInstance().execute(() -> delayed.add(new Delayed(ticks, task)));
    }

    /** Minecraft chat can't render markdown, so strip the common bits. */
    private static String cleanMarkdown(String s) {
        return s.replaceAll("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)", "$1 ($2)")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*[*]\\s+", "- ");
    }

    private static void notice(String text) {
        say(Text.literal(text).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
    }

    private static void postNotice(String text) {
        MinecraftClient.getInstance().execute(() -> notice(text));
    }

    private static void postError(String text) {
        MinecraftClient.getInstance().execute(() -> say(Text.literal(text).formatted(Formatting.RED)));
    }

    /** Adds to the panel history, and to game chat unless the conversation is happening in the panel. */
    private static void say(Text text) {
        log(text);
        if (!panelMode) chat(text);
    }

    private static void log(Text text) {
        log.add(text);
        while (log.size() > LOG_MAX) log.remove(0);
    }

    private static void chat(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(text);
    }
}
