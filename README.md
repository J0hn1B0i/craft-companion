# Craft Companion

Talk to Claude from Minecraft chat (Fabric 1.20.1). Type `/claude <message>` and Claude replies in chat, aware of
where you are, what you're holding and which mods your pack has.

It runs [Claude Code](https://claude.com/claude-code) on your own PC with your own Claude account,
using the fast, low-usage Haiku model by default. Nothing goes through a third-party server.

> Unofficial fan project. Not affiliated with or endorsed by Anthropic or Mojang. "Claude" and "Claude Code" are trademarks of Anthropic.

## Requirements
- Minecraft **1.20.1** with **Fabric Loader** and **Fabric API**
- **Claude Code** installed and signed in on the same PC. It is included with the Claude desktop app,
  or install the CLI from claude.com/claude-code. Claude Code requires a Claude plan that includes it
  (Pro or Max) or an API key.
- Client side only. Works in single player and on any server; the server doesn't need it.

## Install
1. Download `craft-companion-<version>.jar` from the Releases page.
2. Put it in your instance's `mods` folder.
3. Launch the game and type `/claude hello`.

## Commands
| Command | What it does |
| --- | --- |
| `/claude <message>` | Ask Claude something |
| `/claude status` | Shows which Claude Code it found and when it will compact next |
| `/claude compact` | Shrink the conversation now to save usage |
| `/claude reset` | Start a new conversation (CLAUDE.md is kept) |

## How it remembers things
The mod creates a `craft-companion` folder in your instance:
- `CLAUDE.md`: Claude's lasting memory. Add your goals, base locations and play style under
  "Notes about the player". It is read with every message and never overwritten by the mod.
- `mods.md`: your installed mod list, refreshed every launch so Claude knows your pack.

The conversation itself carries on between messages and game sessions. Every 20 messages the mod
runs Claude Code's `/compact` in the background, which summarizes older messages so each new one
uses less of your plan.

## Settings
`config/craftcompanion.json` (restart the game after editing):

| Setting | Default | Meaning |
| --- | --- | --- |
| `claudePath` | `""` | Full path to the Claude Code executable. Empty = find it automatically |
| `model` | `"haiku"` | Model alias: `haiku`, `sonnet` or `opus` |
| `compactEvery` | `20` | Compact after this many messages. `0` = never |
| `timeoutSeconds` | `120` | Give up on a reply after this long |
| `includeGameContext` | `true` | Send position, dimension, biome, held item, health, food and time with each message |
| `allowApiKey` | `false` | `false` hides `ANTHROPIC_API_KEY` so your plan is used. `true` lets Claude Code bill your API key |

## Privacy and safety
- Your messages and the game info above are sent to Anthropic through Claude Code, under your own account.
- Claude runs with **no tools**: it can't read or change files, run commands or control the game.
  It only answers in chat.
- Your MCP servers and connectors are not loaded (`--strict-mcp-config`).

## Building from source
Needs JDK 17.
```bash
./gradlew build
```
The jar is written to `build/libs/`.

## License
MIT. See [LICENSE](LICENSE).
