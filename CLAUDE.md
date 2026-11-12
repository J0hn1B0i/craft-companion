# Craft Companion mod (source)

Client-side Fabric mod that adds `/claude <message>` to Minecraft chat. Each message runs
Claude Code headlessly (`claude -p --model haiku --output-format json --strict-mcp-config --resume <id> --tools ""`)
with the prompt on stdin, using the player's Claude plan (ANTHROPIC_API_KEY is hidden from the child
process unless `allowApiKey` is true).

## Target
- Minecraft 1.20.1, Fabric, yarn 1.20.1+build.10, Fabric API 0.92.x, Java 17.

## Files
- `ClaudeRunner.java`: no Minecraft imports. Finds claude.exe, runs it (stream-json) on one worker thread,
  keeps the session id, picks tools per mode (CHAT/EDIT/COMPACT), runs `/compact` every `compactEvery` messages.
- `StagedEdits.java`: no Minecraft imports. staged/ -> apply with backups -> undo.
- `RecipeExporter.java`: writes recipes/<namespace>.txt and items.txt from the client's recipe manager.
- `EmiCompat.java`: only class touching EMI (compileOnly); loaded only when EMI is present.
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

## Done in 1.1.0 (from the 2026-11-12 in-game test)
Recipe + item export on first message per world (RecipeExporter), item ids and recent chat in [Game] context,
web search, `/claude edit` -> staged/ -> `/claude apply|discard|undo` (StagedEdits, backups/), EMI buttons via
[[recipe:id]] / [[uses:id]] markers and `/claude recipe|uses <id>`. instructions.md is rewritten each launch;
CLAUDE.md is migrated once (adds @instructions.md). Logo: docs/logo.png, icon in assets/craftcompanion/.
Outside-game tests passed (recipes, chat, web, staged edit, apply, undo).

## 1.2.0: natural language + Y/N
No commands needed: every /claude message may stage edits (allowEdits), emit [[recipe:id]] / [[uses:id]] / [[undo]].
The client turns those into a queue of Y/N questions (plain y/n typed in chat is intercepted with
ClientSendMessageEvents.ALLOW_CHAT while a question is open, 5 min expiry; [Yes]/[No] buttons run /claude y|n).
Outcomes of answers are prepended to the next prompt as [Since your last reply: ...] so Claude knows what was applied.
Claude may Edit(CLAUDE.md) itself to save "remember ..." notes. Old commands (edit/apply/discard/undo/recipe/uses)
still work but are no longer advertised. Outside-game tests passed for all of this.

## 1.3.0: corner buddy
BuddyHud (HudRenderCallback, icon.png 32px, bob/blink, "..."/"!" bubble) shows when summoned, busy or asking.
Key `key.craftcompanion.talk` (default backslash; the dev's own pack uses L) captures view.png then opens BuddyScreen:
a non-pausing panel with history (log), input, Yes/No buttons, Hide. Replies go to the panel when the request
came from it (panelMode), otherwise to chat. Context adds [Looking at: ...] (crosshair entity, or block raycast 64)
and view.png (downscaled to 1280 wide, written on the worker); Claude reads it only when needed. [[name:X]] renames
the buddy (config.buddyName). Outside-game tests passed (looking-at, screenshot description, rename).

## Not yet verified in game
- Recipe export on a real pack (count, speed, odd modded recipes), EMI screen opening from a chat button click,
  chat capture of advancement/mod messages. Test questions:
  - Hold a Create item: "What's the recipe for what I'm holding, and what can I make with it?"
  - "How do I craft an ME Controller in this pack? Is it different from normal AE2?"
  - "Look up online: what is the first boss in Prominence II Hasturian Era and how do I prepare for it?"
  - `/claude edit make the minimap show my coordinates`, then Apply, then `/claude undo`.

## NEXT TIME (player feedback after 1.3.0, 2026-11-12)
1. **Panel text box too big.** Shrink BuddyScreen (pw max 300 / ph max 230 is too large); maybe ~200x150, smaller input.
2. **Game feels a bit laggier than before.** Investigate. Suspects to measure first:
   - END_CLIENT_TICK handler copies the `delayed` list every tick (allocation each tick); only copy when non-empty.
   - BuddyHud draws every frame even when hidden? (returns early, but check) and wraps nothing heavy; BuddyScreen
     re-wraps the whole log every frame: cache wrapped lines until the log changes.
   - captureView() on the client thread (resizeSubRectTo on a full frame) when opening the panel / sending.
   - RecipeExporter first-message hitch (~31k recipes + 22k item names on the client thread).
   - findClaude() walks %LOCALAPPDATA%\Claude-Data on every message (worker thread, but disk I/O): cache the path.
   Compare with the mod removed to confirm it's the mod at all (spark profiler is a good tool if the pack has it).
3. **Optimize the modpack generally** (separate from the mod): check performance mods present/missing
   (Sodium/Embeddium-equivalent, ModernFix, FerriteCore, Entity Culling, ImmediatelyFast, etc.), JVM args and
   RAM allocation in minecraftinstance.json / CurseForge settings, render/simulation distance in options.txt,
   shaders, and heavy mods. Report suggestions; change nothing without asking.
