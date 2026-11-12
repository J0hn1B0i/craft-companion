package dev.craftcompanion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Files that give Claude its context, all in the craft-companion folder:
 * - CLAUDE.md: created once and never overwritten. The player's own notes go here.
 * - instructions.md: how to use the recipe files, web and edits. Rewritten every launch.
 * - mods.md: installed mod list. Rewritten every launch.
 */
final class DefaultClaudeMd {
    private DefaultClaudeMd() {}

    static final String IMPORT = "@instructions.md";

    static String claudeMd(String instanceName, String mcVersion, int modCount) {
        return """
                # Claude in Minecraft

                You are chatting with the player from inside Minecraft through the /claude command.
                This file is your lasting memory. It is re-read every message and survives /compact.

                ## The game
                - Instance: %s
                - Minecraft %s on Fabric, %d mods installed.

                @instructions.md

                @mods.md

                ## Notes about the player
                - (Add goals, base locations, play style and anything else Claude should always know.)
                """.formatted(instanceName, mcVersion, modCount);
    }

    static String instructions(ClaudeRunner.Config cfg, boolean emi, String recipeViewer) {
        StringBuilder s = new StringBuilder();
        s.append("""
                # How to help (written by the Craft Companion mod every launch; don't edit)

                ## Answer style
                - Replies appear in Minecraft chat, which is small. Keep answers to 1-4 short sentences
                  unless asked for more. Plain text only: no markdown, headings, tables or code blocks.
                - Each message starts with a [Game: ...] line (position, held items with their ids, health)
                  and may include [Chat] lines: the most recent messages the player saw in game chat.
                  Use them when relevant. Don't repeat them back.
                - If the player wants you to always remember something, tell them to add it to the
                  "Notes about the player" section of craft-companion/CLAUDE.md.

                ## This pack's recipes: always check, never guess
                The mod exports every recipe the game has loaded, straight from the server, so the
                pack's own changes are included. Files in this folder:
                - items.txt: one line per item: `<item id> | <name> | <mod>`. Find an item's id from its
                  name here first (Grep, case-insensitive).
                - recipes/<namespace>.txt: one line per recipe, grouped by the output's namespace:
                  `<output id> x<count> "<name>" <- <recipe id> [<recipe type>] | <ingredients> | grid: <rows>`
                  Grid letters refer to the ingredients list; "." is an empty slot.
                - How to make X: Grep recipes/ for lines starting with the id: pattern "^<id> ".
                - What can I make with X: Grep recipes/ for the id anywhere in the line.
                - If no recipe makes an item, say so. It may come from loot, quests, trading or mobs.
                - If the recipes folder is missing or empty, ask the player to send a message while in a world.
                - Translate ids to names in your answer, and describe shaped grids row by row.
                """);
        if (emi) {
            s.append("""
                    - When your answer is about one specific item, end it with a marker on its own line:
                      [[recipe:<item id>]] to offer a button showing how it's made, or [[uses:<item id>]]
                      for what it's used in. The mod turns it into a button that opens %s.
                    """.formatted(recipeViewer));
        }
        s.append(cfg.allowWeb
                ? """

                ## Web
                - You can use WebSearch and WebFetch for wikis, guides, boss info and mechanics.
                  Use the recipe files, not the web, for recipes: the pack may change them.
                """
                : """

                ## Web
                - Web access is turned off. Say so if the player asks you to look something up online.
                """);
        s.append("""

                ## Mod settings (read-only)
                - The game's config/, defaultconfigs/, datapacks/ and kubejs/ folders (when present) are
                  available to Read and Grep at ../<folder>/. You can't change them directly.
                - Mod .jar files can't be changed. Settings live in config files; recipes, loot and
                  world generation can be changed with datapacks or KubeJS scripts.
                """);
        s.append(cfg.allowEdits
                ? """

                ## Making changes
                - Only when a message starts with [EDIT MODE] can you propose changes. Write the complete
                  new file to staged/<folder>/<path>, for example staged/config/xaerominimap.txt.
                  Copy the original exactly and change only what's needed. For a new datapack, write it
                  under staged/datapacks/<pack name>/ with a pack.mcmeta (pack_format 15 for 1.20.1).
                - Never touch the originals. The player reviews the list of staged files and clicks
                  Apply; the mod backs up the originals so /claude undo can restore them.
                - In your reply, say what you changed in one or two sentences and whether a restart
                  (most config files) or /reload (datapacks) is needed.
                - Without [EDIT MODE], don't stage anything. If the player wants a change, tell them to use
                  /claude edit <what to change>.
                """
                : """

                ## Making changes
                - Edits are turned off in the mod's settings. You can only explain what to change.
                """);
        return s.toString();
    }

    /** Older CLAUDE.md files said "You have no tools"; point them at instructions.md instead. */
    static void migrate(Path claudeMd) throws IOException {
        String s = Files.readString(claudeMd);
        if (s.contains(IMPORT)) return;
        s = s.replace("- You have no tools. You can't see the screen, change the game, or edit files.",
                "- What you can do (recipes, web, edits) is described in instructions.md, imported below.");
        s = s.stripTrailing() + "\n\n" + IMPORT + "\n";
        Files.writeString(claudeMd, s);
    }
}
