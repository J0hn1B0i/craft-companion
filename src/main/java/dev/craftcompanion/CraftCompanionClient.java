package dev.craftcompanion;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CraftCompanionClient implements ClientModInitializer {

    private static final Set<String> SKIP_IDS = Set.of("minecraft", "java", "fabricloader", "fabric-api", "craftcompanion");
    private static final Pattern MARKER = Pattern.compile("\\[\\[(recipe|uses):([a-z0-9_.-]+:[a-z0-9_./-]+)]]");
    private static final Pattern UNDO_MARKER = Pattern.compile("\\[\\[undo]]");
    private static final long QUESTION_MAX_AGE_MS = 5 * 60 * 1000;
    private static final int CHAT_LINES = 10;
    private static final long CHAT_MAX_AGE_MS = 10 * 60 * 1000;

    private static ClaudeRunner runner;
    private static StagedEdits edits;
    private static boolean emi;

    private record ChatLine(long time, String text) {}
    private static final Deque<ChatLine> recentChat = new ArrayDeque<>();

    /** An action Claude asked for, waiting for the player's Y or N. */
    private record Question(String text, Runnable onYes, Runnable onNo) {}
    private static final Deque<Question> questions = new ArrayDeque<>();
    private static long questionShownAt;
    /** What happened to Claude's requests since its last reply; sent with the next message. */
    private static final List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Work to run a couple of ticks later (after the chat screen has closed). */
    private static Runnable delayed;
    private static int delayTicks;

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

        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> runner.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (delayed != null && --delayTicks <= 0) {
                Runnable r = delayed;
                delayed = null;
                r.run();
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
                            say(Text.literal("Just type /claude and what you want, e.g. /claude show me the wrench recipe. "
                                    + "Answer Claude's questions with y or n. Extras: /claude status | compact | reset").formatted(Formatting.GRAY));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            say(Text.literal(runner.status()).formatted(Formatting.GRAY));
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
                        .then(ClientCommandManager.literal("edit")
                                .then(ClientCommandManager.argument("change", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            send(StringArgumentType.getString(ctx, "change"), ClaudeRunner.Mode.CHAT);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("apply").executes(ctx -> {
                            apply();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("discard").executes(ctx -> {
                            runner.worker().execute(() -> {
                                try {
                                    edits.clear();
                                    postNotice("Discarded the proposed changes.");
                                } catch (Exception e) {
                                    postError("Couldn't discard: " + e.getMessage());
                                }
                            });
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
                                    send(StringArgumentType.getString(ctx, "message"), ClaudeRunner.Mode.CHAT);
                                    return 1;
                                }))));
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
            Files.writeString(chatDir.resolve("instructions.md"),
                    DefaultClaudeMd.instructions(runner.config(), emi, "EMI"));
            Files.writeString(chatDir.resolve("mods.md"), modsMd);
        } catch (Exception e) {
            System.err.println("[craftcompanion] couldn't write context files: " + e);
        }
    }

    // ---------------------------------------------------------------- sending

    private static void send(String message, ClaudeRunner.Mode mode) {
        say(Text.literal("You: ").formatted(Formatting.AQUA)
                .append(Text.literal(message).formatted(Formatting.WHITE)));
        if (!questions.isEmpty()) {
            questions.clear();
            notice("(Skipped Claude's earlier question.)");
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
        notice("Claude is thinking...");

        StringBuilder prompt = new StringBuilder();
        if (!outcomes.isEmpty()) {
            prompt.append("[Since your last reply: ").append(String.join("; ", outcomes)).append("]\n");
            outcomes.clear();
        }
        if (runner.config().includeGameContext) prompt.append(gameContext()).append('\n');
        prompt.append(message);

        runner.ask(prompt.toString(), mode, CraftCompanionClient::postNotice)
                .thenAccept(r -> postReply(r, mode));
    }

    /** Player state plus recent chat. Runs on the client thread. */
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

    private static void postReply(ClaudeRunner.Reply r, ClaudeRunner.Mode mode) {
        List<String[]> markers = new ArrayList<>();
        boolean wantsUndo = false;
        String text = r.text();
        if (r.ok()) {
            Matcher m = MARKER.matcher(text);
            while (m.find() && markers.size() < 2) markers.add(new String[]{m.group(1), m.group(2)});
            wantsUndo = UNDO_MARKER.matcher(text).find();
            text = UNDO_MARKER.matcher(MARKER.matcher(text).replaceAll("")).replaceAll("").strip();
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

        MinecraftClient.getInstance().execute(() -> {
            if (!r.ok()) {
                say(Text.literal("[Claude] " + r.text()).formatted(Formatting.RED));
                return;
            }
            boolean first = true;
            for (String line : finalText.split("\\R")) {
                if (line.isBlank()) continue;
                MutableText msg = first
                        ? Text.literal("Claude: ").formatted(Formatting.GOLD).append(Text.literal(line).formatted(Formatting.WHITE))
                        : Text.literal("  " + line).formatted(Formatting.WHITE);
                say(msg);
                first = false;
            }
            if (first && markers.isEmpty() && stagedFinal.isEmpty() && !undoFinal) {
                say(Text.literal("Claude: (empty reply)").formatted(Formatting.GRAY));
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

    private static boolean hasQuestion() {
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
        say(Text.literal("Claude asks: ").formatted(Formatting.YELLOW)
                .append(Text.literal(q.text()).formatted(Formatting.WHITE))
                .append(Text.literal("  Type ").formatted(Formatting.GRAY))
                .append(Text.literal("y").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" or ").formatted(Formatting.GRAY))
                .append(Text.literal("n").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal("  "))
                .append(button("[Yes]", Formatting.GREEN, "/claude y", "Yes"))
                .append(Text.literal(" "))
                .append(button("[No]", Formatting.RED, "/claude n", "No")));
    }

    private static void answer(boolean yes) {
        if (!hasQuestion()) {
            notice("Claude isn't waiting for an answer.");
            return;
        }
        Question q = questions.pollFirst();
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
                postNotice("Originals are backed up; /claude undo puts them back. Config changes usually need a "
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
        // Wait two ticks so the chat screen has closed before EMI opens its screen.
        delayTicks = 2;
        delayed = () -> {
            try {
                EmiCompat.show(stack, uses);
            } catch (Throwable t) {
                say(Text.literal("Couldn't open EMI: " + t.getMessage()).formatted(Formatting.RED));
            }
        };
    }

    // ---------------------------------------------------------------- chat helpers

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

    private static void say(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(text);
    }
}
