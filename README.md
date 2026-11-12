<p align="center">
  <img src="docs/logo.png" alt="Craft Companion logo: a smiling grass block with a sprout and a speech bubble" width="160">
</p>

<h1 align="center">Craft Companion</h1>

<p align="center">Talk to Claude from Minecraft chat. It knows your pack's real recipes, can look things up online,
and can tweak mod settings for you.</p>

> Unofficial fan project. Not affiliated with or endorsed by Anthropic or Mojang. "Claude" and "Claude Code" are trademarks of Anthropic.

## What it does
Type `/claude` and just say what you want. There are no other commands to learn.
- **Ask anything:** Claude sees your position, biome, held items (with their mod) and your recent chat,
  so "what's this thing for?" and "what was that message?" just work.
- **Your pack's real recipes:** the first time you ask in a world, the mod indexes every recipe the server sent,
  including modpack changes. Claude searches it instead of guessing.
- **Show me the recipe:** Claude offers to open the recipe in EMI. Type `y` or `n`.
- **Web lookups:** wikis, guides, boss info.
- **Change mod settings:** "make my minimap show coordinates". Claude prepares the change and asks; type `y` to apply.
  Originals are backed up, so "undo that" works too.
- **Remembers you:** "remember my base is at 100 70 200" is saved to its notes for good.

Nothing happens to your game or files without a `y` from you. Questions also have clickable [Yes] / [No] buttons,
and a plain `y` or `n` typed while Claude is waiting is never sent to the server.

It runs [Claude Code](https://claude.com/claude-code) on your own PC with your own Claude account, using the fast,
low-usage Haiku model by default. Nothing goes through a third-party server.

## Requirements
- Minecraft **1.20.1** with **Fabric Loader** and **Fabric API**
- **Claude Code** installed and signed in on the same PC. It is included with the Claude desktop app,
  or install the CLI from claude.com/claude-code. Claude Code requires a Claude plan that includes it
  (Pro or Max) or an API key.
- Optional: **EMI** for the Show recipe buttons.
- Client side only. Works in single player and on any server; the server doesn't need it.

## Install
1. Download `craft-companion-<version>.jar` from the Releases page.
2. Put it in your instance's `mods` folder.
3. Launch the game, join a world and type `/claude hello`.

## Commands
| Command | What it does |
| --- | --- |
| `/claude <anything>` | Talk to Claude: questions, "show me the recipe for…", "change…", "undo that", "remember…" |
| `y` / `n` | Answer Claude's question (only while it's waiting) |
| `/claude status` | Shows which Claude Code it found, settings, and when it will compact next |
| `/claude compact` | Shrink the conversation now to save usage |
| `/claude reset` | Start a new conversation (CLAUDE.md is kept) |

## How it remembers things
The mod keeps its files in a `craft-companion` folder in your instance:
- `CLAUDE.md`: Claude's lasting memory. Ask Claude to remember something, or edit the
  "Notes about the player" section yourself. Read with every message; the mod never overwrites it.
- `instructions.md` and `mods.md`: how Claude should help, and your mod list. Rewritten every launch.
- `recipes/` and `items.txt`: the recipe and item index for the world you're in.
- `staged/` and `backups/`: proposed changes and backups for `/claude undo`.

The conversation carries on between messages and game sessions. Every 20 messages the mod runs Claude Code's
`/compact` in the background, which summarizes older messages so each new one uses less of your plan.

## Settings
`config/craftcompanion.json` (restart the game after editing):

| Setting | Default | Meaning |
| --- | --- | --- |
| `claudePath` | `""` | Full path to the Claude Code executable. Empty = find it automatically |
| `model` | `"haiku"` | Model alias: `haiku`, `sonnet` or `opus` |
| `compactEvery` | `20` | Compact after this many messages. `0` = never |
| `timeoutSeconds` | `180` | Give up on a reply after this long |
| `includeGameContext` | `true` | Send position, biome, held items, health, food, time and recent chat with each message |
| `allowWeb` | `true` | Let Claude search the web and read pages |
| `allowEdits` | `true` | Let Claude propose setting changes (always asks before applying) |
| `allowApiKey` | `false` | `false` hides `ANTHROPIC_API_KEY` so your plan is used. `true` lets Claude Code bill your API key |

## Privacy and safety
- Your messages, the game info above and your last few chat lines are sent to Anthropic through Claude Code,
  under your own account.
- Claude can only **read and search** its own folder and your `config`, `defaultconfigs`, `datapacks` and `kubejs`
  folders, and use web search and fetch if `allowWeb` is on.
- It **can't** run commands, read the rest of your PC, or change any game file directly. It may only write
  proposed changes to `craft-companion/staged/` and add notes to its own `CLAUDE.md`. Game files change only when
  you answer `y`, and the originals are backed up first.
- Your MCP servers and connectors are not loaded (`--strict-mcp-config`).

## Building from source
Needs JDK 17.
```bash
./gradlew build
```
The jar is written to `build/libs/`.

## License
MIT. See [LICENSE](LICENSE).
