package dev.craftcompanion;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Writes the recipes the client received from the server, plus an item name index, to text files
 * Claude can search. Must be called on the client thread; file writing happens on the given executor.
 */
final class RecipeExporter {
    private RecipeExporter() {}

    private static Object lastSource;
    private static int lastCount = -1;
    private static boolean itemsWritten;
    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    /** Exports if the world or its recipe list changed since last time. Returns a notice or null. */
    static String exportIfNeeded(MinecraftClient mc, Path chatDir, Executor io) {
        if (mc.getNetworkHandler() == null || mc.world == null) return null;
        Collection<Recipe<?>> recipes = mc.getNetworkHandler().getRecipeManager().values();
        Object source = mc.getNetworkHandler();
        if (source == lastSource && recipes.size() == lastCount && itemsWritten) return null;
        lastSource = source;
        lastCount = recipes.size();

        DynamicRegistryManager regs = mc.world.getRegistryManager();
        Map<String, StringBuilder> byNamespace = new TreeMap<>();
        int ok = 0;
        for (Recipe<?> r : recipes) {
            try {
                String line = describe(r, regs);
                String ns = line.substring(0, line.indexOf(':'));
                if (!ns.matches("[a-z0-9_.-]+")) ns = "_other";
                byNamespace.computeIfAbsent(ns, k -> new StringBuilder()).append(line).append('\n');
                ok++;
            } catch (Throwable ignored) {
                // Some modded recipes can't be described from the client. Skip them.
            }
        }
        String items = itemsWritten ? null : itemIndex();
        itemsWritten = true;
        int count = ok;

        io.execute(() -> {
            try {
                Path dir = chatDir.resolve("recipes");
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> s = Files.list(dir)) {
                        for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p);
                    }
                }
                Files.createDirectories(dir);
                for (Map.Entry<String, StringBuilder> e : byNamespace.entrySet()) {
                    Files.writeString(dir.resolve(e.getKey() + ".txt"), e.getValue());
                }
                if (items != null) Files.writeString(chatDir.resolve("items.txt"), items);
            } catch (IOException e) {
                System.err.println("[craftcompanion] recipe export failed: " + e);
            }
        });
        return "Indexed " + count + " recipes for Claude.";
    }

    private static String describe(Recipe<?> r, DynamicRegistryManager regs) {
        ItemStack out = r.getOutput(regs);
        String type = String.valueOf(Registries.RECIPE_TYPE.getId(r.getType()));
        StringBuilder sb = new StringBuilder();
        if (out == null || out.isEmpty()) {
            sb.append(r.getId()).append(" (no single item output)");
        } else {
            sb.append(id(out.getItem())).append(" x").append(out.getCount())
                    .append(" \"").append(out.getName().getString()).append('"');
        }
        sb.append(" <- ").append(r.getId()).append(" [").append(type).append("] | ");

        List<Ingredient> ings = r.getIngredients();
        if (r instanceof ShapedRecipe shaped) {
            Map<String, Character> letters = new LinkedHashMap<>();
            StringBuilder grid = new StringBuilder();
            int w = shaped.getWidth(), h = shaped.getHeight();
            for (int y = 0; y < h; y++) {
                if (y > 0) grid.append(" / ");
                for (int x = 0; x < w; x++) {
                    Ingredient ing = ings.get(y * w + x);
                    String d = ingredient(ing);
                    if (d == null) {
                        grid.append('.');
                    } else {
                        Character c = letters.computeIfAbsent(d, k -> (char) ('A' + letters.size()));
                        grid.append(c);
                    }
                }
            }
            List<String> parts = new ArrayList<>();
            letters.forEach((d, c) -> parts.add(c + " = " + d));
            sb.append(String.join("; ", parts)).append(" | grid: ").append(grid);
        } else {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Ingredient ing : ings) {
                String d = ingredient(ing);
                if (d != null) counts.merge(d, 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            counts.forEach((d, n) -> parts.add(n + "x " + d));
            sb.append(parts.isEmpty() ? "(ingredients not visible on the client)" : String.join("; ", parts));
        }
        return sb.toString();
    }

    private static String ingredient(Ingredient ing) {
        if (ing == null || ing.isEmpty()) return null;
        ItemStack[] stacks = ing.getMatchingStacks();
        if (stacks.length == 0) return null;
        if (stacks.length == 1) return id(stacks[0].getItem()) + " \"" + stacks[0].getName().getString() + "\"";
        StringBuilder sb = new StringBuilder("any of: ");
        int n = Math.min(stacks.length, 4);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(id(stacks[i].getItem()));
        }
        if (stacks.length > n) sb.append(" (+").append(stacks.length - n).append(" more)");
        return sb.toString();
    }

    private static String itemIndex() {
        StringBuilder sb = new StringBuilder();
        for (Item item : Registries.ITEM) {
            try {
                Identifier id = Registries.ITEM.getId(item);
                sb.append(id).append(" | ").append(new ItemStack(item).getName().getString())
                        .append(" | ").append(modName(id.getNamespace())).append('\n');
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    static String modName(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, ns -> FabricLoader.getInstance().getModContainer(ns)
                .map(m -> m.getMetadata().getName()).orElse(ns));
    }
}
