# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
Craft Companion: a client-side Fabric mod for Minecraft 1.20.1. A buddy in the screen corner (key, default `\`) and a `/claude` command run **Claude Code headlessly on the player's PC** and bring its answers back into the game. Features, commands and the settings table are in `README.md`; the source of truth for settings is `ClaudeRunner.Config`. Versions (Fabric Loader 0.15.11, Fabric API 0.92.2, yarn 1.20.1+build.10, EMI API) are in `gradle.properties`; Java 17. EMI is a `modCompileOnly` soft dependency.

This repo lives inside a CurseForge instance (Prominence II: Hasturian Era, ~470 mods). The instance folder is the parent directory and is **not** a git repo; the mod's runtime files (`../craft-companion/`, `../config/craftcompanion.json`) sit there too, outside this repo.

## Commands
```bash
export JAVA_HOME="/c/Users/Johns/curseforge/minecraft/Install/runtime/java-runtime-gamma/windows-x64/java-runtime-gamma"  # no system JDK 17
./gradlew build                                  # jar -> build/libs/craft-companion-<mod_version>.jar (first build downloads Minecraft and mappings)
rm ../mods/craft-companion-*.jar && cp build/libs/craft-companion-<version>.jar ../mods/   # only one jar of this mod may be present
./gradlew --stop                                 # the Gradle daemon idles at ~600 MB
```
- Installing fails with "Device or resource busy" while Minecraft is running (Windows locks the jar). Close the game; never leave two versions in `mods/` (same mod id crashes the game).
- There is no test suite and no dev run configuration. Check changes by launching the instance from CurseForge.
- Classes without Minecraft imports (`ClaudeRunner`, `StagedEdits`, `DefaultClaudeMd`, plus `ProcessPriority`/`Kernel32Ex` which need the JNA jars) can be exercised outside the game: compile them with plain `javac` against `gson-2.10.jar`, `jna-5*.jar` and `jna-platform-5*.jar` from `~/.gradle/caches`, plus a throwaway `main` in package `dev.craftcompanion` (the helpers are package-private). Point it at a fake game dir containing `craft-companion/recipes/*.txt`, `items.txt` and `config/`, write `instructions.md` with `DefaultClaudeMd.instructions(cfg, true, "EMI", "Sprout")`, then call `ClaudeRunner.ask(...)`. This is how replies, staged edits, apply/undo and priority lowering have been verified.
- To see exactly what the in-game Claude received, read its transcript: `~/.claude/projects/<instance path with every non-alphanumeric character replaced by ->-craft-companion/<sessionId>.jsonl` (session id is in `../craft-companion/state.json`).

## Architecture
Three channels connect the game and Claude. Reading `CraftCompanionClient` alone doesn't show them.

**1. Player to Claude (prompt).** `CraftCompanionClient.send` assembles one text prompt: `[Since your last reply: ...]` (outcomes of the player's y/n answers) + `[Game: ...]` (position, biome, held items with registry ids, health) + `[Looking at: ...]` (crosshair entity, or block raycast to 64) + a pointer to `view.png` (screenshot taken when the panel opens or a `/claude` message is sent) + `[Chat Ns ago]` lines (ring buffer fed by `ClientReceiveMessageEvents`) + the message. `ClaudeRunner.ask` runs it on a single worker thread as `claude -p --model haiku --output-format stream-json --verbose --strict-mcp-config [--resume <id>] --add-dir <config folders> --allowedTools ... --tools ...` with the prompt on **stdin** and cwd `craft-companion/`. It reads the event stream for progress lines ("Searching recipes...") and the final result, stores the session id in `state.json`, and runs `/compact` in `Mode.COMPACT` every `compactEvery` messages. `ANTHROPIC_API_KEY` is removed from the child env unless `allowApiKey`, so the signed-in plan is used. The child is set to below-normal CPU priority through JNA.

**2. Claude's context (files in cwd `craft-companion/`).** Claude Code loads `CLAUDE.md` there. It is created once and never overwritten (the player and Claude, via `Edit(CLAUDE.md)`, add notes); it imports `@instructions.md` (rewritten every launch by `DefaultClaudeMd.instructions` from the config flags) and `@mods.md` (mod list). `RecipeExporter` fills `recipes/<namespace>.txt` and `items.txt` from the client's `RecipeManager` (the server sends all recipes, so pack and datapack changes are included); Claude finds recipes by Grep. The export runs in 4 ms slices per tick, and a message waits (`askWhenReady`) until it is done.

**3. Claude to the game (markers and staged files).** The reply text may contain `[[recipe:<id>]]`, `[[uses:<id>]]`, `[[undo]]`, `[[name:<x>]]`; `postReply` parses and strips them. Edits are never made directly: Claude may only `Write` under `staged/**`; `StagedEdits.pending()` lists those files (whitelisted roots `config`, `defaultconfigs`, `kubejs`, `datapacks`, no `..`). Each marker or staged set becomes a `Question` in a y/n queue. The player answers with a typed `y`/`n` (swallowed by `ClientSendMessageEvents.ALLOW_CHAT` while a question is open), the panel, or `[Yes]/[No]` chat buttons (`/claude y|n`). `apply` backs up originals to `backups/<time>/` (new files are listed in `_new_files.txt` so `undo` can delete them). EMI opens only through `EmiCompat`, the one class that touches EMI classes (guarded by the `emi` flag).

**Threading and output.** All game and UI state (`log`, `questions`, `outcomes`, `delayed`, `busy`) is client-thread only; the worker thread and file writes hand back via `MinecraftClient.execute`. `later(ticks, task)` defers work to the tick handler (used so a screen or screenshot happens after the chat screen closes). `panelMode` decides whether `say()` writes to game chat or only to the panel history (`log`, with `logVersion` so `BuddyScreen` re-wraps only on change). Replies go through `cleanMarkdown`/`flattenTables` because Minecraft chat can't render markdown.

## Gotchas
- Claude Code loads `CLAUDE.md` from every parent directory of its cwd. Never put a `CLAUDE.md` (or other Claude Code instructions) in the instance root or any parent of `craft-companion/`: it would be injected into every in-game message. This file sits in a sibling folder, so it isn't loaded.
- `--allowedTools` and `--tools` take lists; they are passed as single comma-joined arguments and kept last. Compact mode uses `--tools ""`.
- `/compact` works in `-p` mode with `--resume` (about 17k to 5k tokens in testing, facts kept).
- claude.exe is found via config `claudePath`, PATH, `~/.local/bin`, then the newest copy bundled with the Claude desktop app under `%LOCALAPPDATA%\Claude-Data\...\claude-code\<version>\` (the path changes when the app updates; the result is cached while the file exists).
- Yarn 1.20.1 signatures differ from newer Minecraft (for example `Screen.mouseScrolled` takes three arguments).
- Don't edit `options.txt` or `minecraftinstance.json` while the game or the CurseForge app is running; they overwrite changes. Back up first (see `../craft-companion/backup-pack-tuning/`). On this instance the talk key is bound to `L` in `options.txt`.
- Releases are published by the owner on GitHub (`gh release create v<version> build/libs/craft-companion-<version>.jar`); the remote is `J0hn1B0i/craft-companion`, branch `main`.

## Status (v1.3.1)
Built, installed and pushed, but only exercised outside the game. Still to check in game: panel size and dragging, recipe export on the full pack (no hitch, correct count), EMI opening from a button, `y`/`n` typed in chat, chat capture of advancement messages. Lag was investigated by code review only; to confirm, use the pack's spark mod (`/spark profiler start`, play, `/spark profiler stop`). Pack tuning already applied on the dev instance: `allocatedMemory` 12288 and 119 Spanish/Portuguese translation packs removed from the active resource pack list (files kept).
