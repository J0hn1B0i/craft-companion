package dev.craftcompanion;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class CraftCompanionClient implements ClientModInitializer {

    private static final Set<String> SKIP_IDS = Set.of("minecraft", "java", "fabricloader", "fabric-api", "craftcompanion");

    private static ClaudeRunner runner;

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("craftcompanion.json");
        runner = new ClaudeRunner(gameDir.resolve("craft-companion"), configFile);

        // Top-level mods only (skip libraries bundled inside other jars and Fabric's own modules).
        List<ModMetadata> mods = FabricLoader.getInstance().getAllMods().stream()
                .filter(m -> m.getContainingMod().isEmpty())
                .map(ModContainer::getMetadata)
                .filter(m -> !SKIP_IDS.contains(m.getId()) && !m.getId().startsWith("fabric-"))
                .sorted(Comparator.comparing(m -> m.getName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        Path abs = gameDir.toAbsolutePath().normalize();
        String instanceName = abs.getFileName() == null ? "Minecraft" : abs.getFileName().toString();
        String modsMd = "## Installed mods (" + mods.size() + ")\n"
                + mods.stream().map(ModMetadata::getName).collect(Collectors.joining(", ")) + "\n";
        runner.load(DefaultClaudeMd.text(instanceName, mcVersion, mods.size()), modsMd);

        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> runner.shutdown());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("claude")
                        .executes(ctx -> {
                            say(Text.literal("Usage: /claude <message>  |  /claude status  |  /claude compact  |  /claude reset")
                                    .formatted(Formatting.GRAY));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            say(Text.literal(runner.status()).formatted(Formatting.GRAY));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("compact").executes(ctx -> {
                            say(Text.literal("Compacting the conversation...").formatted(Formatting.GRAY));
                            runner.compact().thenAccept(r -> postReply(r.ok()
                                    ? new ClaudeRunner.Reply(true, "Compacted.")
                                    : r, true));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            runner.reset().thenRun(() -> postNotice("Started a new conversation. CLAUDE.md is kept."));
                            return 1;
                        }))
                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String message = StringArgumentType.getString(ctx, "message");
                                    send(message);
                                    return 1;
                                }))));
    }

    private static void send(String message) {
        say(Text.literal("You: ").formatted(Formatting.AQUA)
                .append(Text.literal(message).formatted(Formatting.WHITE)));
        say(Text.literal("Claude is thinking...").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));

        String prompt = runner.config().includeGameContext ? gameContext() + "\n" + message : message;
        runner.ask(prompt, CraftCompanionClient::postNotice)
                .thenAccept(r -> postReply(r, false));
    }

    /** One line describing the player's state. Runs on the client thread. */
    private static String gameContext() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "[Game: not in a world]";
        BlockPos pos = p.getBlockPos();
        String biome = mc.world.getBiome(pos).getKey().map(k -> k.getValue().toString()).orElse("unknown");
        String dim = mc.world.getRegistryKey().getValue().toString();
        ItemStack held = p.getMainHandStack();
        String item = held.isEmpty() ? "nothing" : held.getCount() + "x " + held.getName().getString();
        long day = mc.world.getTimeOfDay() / 24000L;
        long time = mc.world.getTimeOfDay() % 24000L;
        String tod = time < 12000 ? "day" : time < 13800 ? "dusk" : time < 22200 ? "night" : "dawn";
        return String.format("[Game: pos %d %d %d, %s, biome %s, holding %s, health %.0f/%.0f, food %d/20, day %d %s]",
                pos.getX(), pos.getY(), pos.getZ(), dim, biome, item,
                p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), day, tod);
    }

    private static void postReply(ClaudeRunner.Reply r, boolean quiet) {
        MinecraftClient.getInstance().execute(() -> {
            if (!r.ok()) {
                say(Text.literal("[Claude] " + r.text()).formatted(Formatting.RED));
                return;
            }
            String text = cleanMarkdown(r.text());
            if (quiet) {
                say(Text.literal(text).formatted(Formatting.GRAY));
                return;
            }
            boolean first = true;
            for (String line : text.split("\\R")) {
                if (line.isBlank()) continue;
                MutableText msg = first
                        ? Text.literal("Claude: ").formatted(Formatting.GOLD).append(Text.literal(line).formatted(Formatting.WHITE))
                        : Text.literal("  " + line).formatted(Formatting.WHITE);
                say(msg);
                first = false;
            }
            if (first) say(Text.literal("Claude: (empty reply)").formatted(Formatting.GRAY));
        });
    }

    private static void postNotice(String notice) {
        MinecraftClient.getInstance().execute(() -> say(Text.literal(notice).formatted(Formatting.DARK_GRAY)));
    }

    /** Minecraft chat can't render markdown, so strip the common bits. */
    private static String cleanMarkdown(String s) {
        return s.replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*[*]\\s+", "- ");
    }

    private static void say(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(text);
    }
}
