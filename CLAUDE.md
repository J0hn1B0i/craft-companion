# Craft Companion mod (source)

Client-side Fabric mod that adds `/claude <message>` to Minecraft chat. Each message runs
Claude Code headlessly (`claude -p --model haiku --output-format json --strict-mcp-config --resume <id> --tools ""`)
with the prompt on stdin, using the player's Claude plan (ANTHROPIC_API_KEY is hidden from the child
process unless `allowApiKey` is true).

## Target
- Minecraft 1.20.1, Fabric, yarn 1.20.1+build.10, Fabric API 0.92.x, Java 17.

## Files
- `ClaudeRunner.java`: no Minecraft imports. Finds claude.exe, runs it on one worker thread,
  keeps the session id, runs `/compact` every `compactEvery` messages. Testable with plain javac + gson.
- `CraftCompanionClient.java`: client command (`/claude`, `status`, `compact`, `reset`), game-state line,
  mod list, chat output.
- `DefaultClaudeMd.java`: default text for the in-game Claude's `CLAUDE.md`.

## Runtime files (in the game/instance folder)
- `config/craftcompanion.json`: claudePath, model, compactEvery, timeoutSeconds, includeGameContext, allowApiKey.
- `craft-companion/CLAUDE.md`: the in-game Claude's lasting context. Created once, never overwritten.
- `craft-companion/mods.md`: installed mod list, rewritten every launch, imported by CLAUDE.md with `@mods.md`.
- `craft-companion/state.json`: session id and counters. `craft-companion/last-error.log`: stderr of the last run.

## Build
Needs a JDK 17. If none is installed, the Minecraft launcher's bundled Java 17 runtime
(`java-runtime-gamma`) usually includes javac; point JAVA_HOME at it.
```bash
./gradlew build      # jar in build/libs/
```

## Notes
- `--tools` takes a list, so it must stay the last flag.
- claude.exe is looked up in: config `claudePath`, PATH, `~/.local/bin`, then the newest copy bundled with the
  Claude desktop app under `%LOCALAPPDATA%\Claude-Data\...\claude-code\<version>\` (that path changes on app updates).
- `/compact` works in `-p` mode with `--resume` (tested: context about 17k -> 5k tokens, facts kept).

## MUST DO (next session) - from in-game testing 2026-11-12
Chat works in game, but Claude can't answer pack-specific questions:
1. **Recipes.** It has no recipe data and guesses. Plan: on world join, export the client's RecipeManager
   (the server sends all recipes, so pack/KubeJS changes are included) to text files in `craft-companion/recipes/`,
   one per mod namespace. Also add recipes for the held item straight into the [Game: ...] context.
   Give Claude read-only search over that folder (`--allowedTools Read Grep Glob`, cwd = craft-companion).
2. **Web.** It runs with `--tools ""`, so no web search. Add config `allowWeb` (default true?) that enables
   `WebSearch` and `WebFetch` for wiki and guide lookups. Note: uses more of the plan per message.
3. **File edits (opt-in).** The player wants Claude to change parts of mods. Add config `allowEdits` (default false,
   since this is a public mod). When on: run with cwd = game dir and allow only `Edit`/`Write` inside `config/`,
   `defaultconfigs/`, `kubejs/` (if present) and the world's `datapacks/`. Never `mods/` jars: compiled code
   can't be edited sensibly; changes go through configs, KubeJS scripts or datapacks instead.
   Before any edit-enabled message, zip the allowed folders to `craft-companion/backups/`; add `/claude undo`
   to restore the last backup. After a reply, list the changed files in chat and say whether `/reload` or a
   restart is needed.
4. **Item IDs in context.** In-game test sent "holding 1x Wrench", which is ambiguous with 470 mods.
   Send the registry id and mod too (e.g. `create:wrench` from Create), for the held item and offhand.
5. **Recent chat in context.** The player asked "what is that message that just popped up" and Claude couldn't
   see it. Keep the last ~10 non-/claude chat lines (mixin or ChatHud access) and send them with each message.
6. Keep everything else blocked (no Bash). Update README privacy section.

In-game test results 2026-11-12 (transcript confirmed the [Game] line and mod list both reached Claude):
Wrench recipe, Astral Tier III book, ME Controller: all "I don't know, check EMI". Web lookup: "I can't search".
The player reports the ME Controller may not be craftable normally in this pack; check the recipe export.

Test questions (Prominence II), all should be answered correctly after the fix:
- Hold a Create item, then: "What's the recipe for what I'm holding, and what can I make with it?"
- "How do I craft an ME Controller in this pack? Is it different from normal AE2?"
- "Look up online: what is the first boss in Prominence II Hasturian Era and how do I prepare for it?"
- (allowEdits on) "Make the Xaero minimap show my coordinates" or another small config change, then `/claude undo`.
