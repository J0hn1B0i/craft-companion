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
