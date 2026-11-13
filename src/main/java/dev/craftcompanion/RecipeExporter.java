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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Writes the recipes the client received from the server, plus an item name index, to text files
 * Claude can search.
 *
 * A big pack has 30,000+ recipes, so the work is done in small slices (a few milliseconds per tick)
 * instead of one long freeze. Everything here runs on the client thread except the file writes.
 */
final class RecipeExporter {
    private RecipeExporter() {}

    private static final long BUDGET_NANOS = 4_000_000L; // 4 ms of work per tick
    private static final long RECHECK_MS = 30_000;       // don't re-count recipes more than this often

    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    // What we last exported
    private static Object lastSource;
    private static int lastCount = -1;
    private static long lastCheck;
    private static boolean itemsWritten;

    // The running job (null when idle)
    private static List<Recipe<?>> jobRecipes;
    private static int jobIndex;
    private static Map<String, StringBuilder> jobByNamespace;
    private static int jobOk;
    private static List<Item> jobItems;
    private static int jobItemIndex;
    private static StringBuilder jobItemsOut;
    private static DynamicRegistryManager jobRegs;
    private static Path jobDir;
    private static Executor jobIo;
    private static Consumer<String> jobDone;

    static boolean busy() {
        return jobRecipes != null;
    }

    /**
     * Starts an export if the world or its recipes changed. Returns true if one was started.
     * onDone gets a short notice on the client thread when it finishes.
     */
    static boolean startIfNeeded(MinecraftClient mc, Path chatDir, Executor io, Consumer<String> onDone) {
        if (busy() || mc.getNetworkHandler() == null || mc.world == null) return false;
        Object source = mc.getNetworkHandler();
        long now = System.currentTimeMillis();
        boolean sameWorld = source == lastSource && itemsWritten;
        if (sameWorld && now - lastCheck < RECHECK_MS) return false;
        lastCheck = now;

        List<Recipe<?>> recipes = new ArrayList<>(mc.getNetworkHandler().getRecipeManager().values());
        if (sameWorld && recipes.size() == lastCount) return false;
        lastSource = source;
        lastCount = recipes.size();

        jobRecipes = recipes;
        jobIndex = 0;
        jobOk = 0;
        jobByNamespace = new TreeMap<>();
        jobRegs = mc.world.getRegistryManager();
        jobDir = chatDir;
        jobIo = io;
        jobDone = onDone;
        if (itemsWritten) {
            jobItems = null;
        } else {
            jobItems = new ArrayList<>();
            Registries.ITEM.forEach(jobItems::add);
            jobItemIndex = 0;
            jobItemsOut = new StringBuilder();
        }
        return true;
    }

    /** Call once per client tick. */
    static void tick() {
        if (jobRecipes == null) return;
        long end = System.nanoTime() + BUDGET_NANOS;
        while (jobIndex < jobRecipes.size() && System.nanoTime() < end) {
            Recipe<?> r = jobRecipes.get(jobIndex++);
            try {
                String line = describe(r, jobRegs);
                String ns = line.substring(0, line.indexOf(':'));
                if (!ns.matches("[a-z0-9_.-]+")) ns = "_other";
                jobByNamespace.computeIfAbsent(ns, k -> new StringBuilder()).append(line).append('\n');
                jobOk++;
            } catch (Throwable ignored) {
                // Some modded recipes can't be described from the client. Skip them.
            }
        }
        if (jobIndex < jobRecipes.size()) return;
        while (jobItems != null && jobItemIndex < jobItems.size() && System.nanoTime() < end) {
            Item item = jobItems.get(jobItemIndex++);
            try {
                Identifier id = Registries.ITEM.getId(item);
                jobItemsOut.append(id).append(" | ").append(new ItemStack(item).getName().getString())
                        .append(" | ").append(modName(id.getNamespace())).append('\n');
            } catch (Throwable ignored) {
            }
        }
        if (jobItems != null && jobItemIndex < jobItems.size()) return;
        finish();
    }

    private static void finish() {
        Map<String, StringBuilder> byNamespace = jobByNamespace;
        String items = jobItems == null ? null : jobItemsOut.toString();
        int count = jobOk;
        Path dir = jobDir;
        Consumer<String> done = jobDone;
        jobIo.execute(() -> {
            try {
                Path rdir = dir.resolve("recipes");
                if (Files.isDirectory(rdir)) {
                    try (Stream<Path> s = Files.list(rdir)) {
                        for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p);
                    }
                }
                Files.createDirectories(rdir);
                for (Map.Entry<String, StringBuilder> e : byNamespace.entrySet()) {
                    Files.writeString(rdir.resolve(e.getKey() + ".txt"), e.getValue());
                }
                if (items != null) Files.writeString(dir.resolve("items.txt"), items);
            } catch (IOException e) {
                System.err.println("[craftcompanion] recipe export failed: " + e);
            }
        });
        if (items != null) itemsWritten = true;
        jobRecipes = null;
        jobByNamespace = null;
        jobItems = null;
        jobItemsOut = null;
        jobRegs = null;
        if (done != null) done.accept("Indexed " + count + " recipes for Claude.");
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

    static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    static String modName(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, ns -> FabricLoader.getInstance().getModContainer(ns)
                .map(m -> m.getMetadata().getName()).orElse(ns));
    }
}
