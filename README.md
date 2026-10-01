# MCPTheSpire

[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

> **Personal fork.** This is a personal fork of [ifree/MCPTheSpire](https://github.com/ifree/MCPTheSpire), the original mod and all credit for it. This fork fixes the Streamable HTTP handshake for stricter MCP clients and reworks the tool surface for token efficiency (compact text state, 9 tools; see [bench/RESULTS.md](bench/RESULTS.md)). It is not affiliated with or supported by the original author, so please don't report issues from this fork upstream.

A Slay the Spire mod that enables AI agents to play the game through the Model Context Protocol (MCP). This allows AI assistants like Claude, GPT, or other MCP-compatible clients to interact with and control Slay the Spire gameplay programmatically.

## Features

- **MCP Protocol Support**: Full implementation of Model Context Protocol using Streamable HTTP transport
- **Real-time Game Control**: AI agents can play cards, make choices, use potions, and navigate menus
- **Token-efficient**: Compact text state and per-action change logs instead of JSON snapshots (about 75% less tool output, about 90% less per combat turn)
- **Batch Actions**: Execute multiple actions in a single call, with indices that stay stable across the batch
- **Thread-safe Operations**: Read-only operations execute immediately, game-changing actions are queued for safe execution on the game thread
- **Configurable Server**: Customizable host and port settings

## Requirements

- **Java 8+**
- **Slay the Spire** (base game)
- **ModTheSpire** (mod loader)
- **BaseMod** (required dependency)
- An MCP-compatible AI client (Claude Desktop, custom MCP clients, etc.)

## Installation

1. Install [ModTheSpire](https://github.com/kiooeht/ModTheSpire) following their installation guide
2. Install [BaseMod](https://github.com/daviscook477/BaseMod) if not already installed
3. Download the latest `MCPTheSpire.jar` from the releases page
4. Place the JAR file in your ModTheSpire mods directory
5. Launch Slay the Spire through ModTheSpire
6. Enable the "MCP The Spire" mod in the ModTheSpire menu

### Build locally on Windows

From this checkout, run `scripts\build-local.ps1 -Javac 'C:\path\to\jdk\bin\javac.exe'` in PowerShell. The script uses the default Steam installation path, the installed ModTheSpire and BaseMod workshop JARs, and writes `target\MCPTheSpire.jar`. Pass `-GameDirectory` if Slay the Spire is installed elsewhere. Copy that JAR into the game's `mods` directory, select MCP The Spire in ModTheSpire, and launch the game.

## Configuration

The mod can be configured through the config file at `MCPTheSpire/config.properties`:

```properties
host=127.0.0.1
port=8080
```

## Usage

### Connecting an AI Client

Configure your MCP client to connect to:
```
http://127.0.0.1:8080/mcp
```

For Codex, add this to `~/.codex/config.toml`:

```toml
[mcp_servers.slay_the_spire]
enabled = true
url = "http://127.0.0.1:8080/mcp"
```

The MCP server starts inside the modded game. Launch Slay the Spire with ModTheSpire first; after it reaches the main menu, an MCP client can start or continue a run and control gameplay through the exposed tools. The mod does not launch the game executable or enable mods itself.

For Claude Desktop, add to your MCP config:
```json
{
  "mcpServers": {
    "slay-the-spire": {
      "url": "http://127.0.0.1:8080/mcp",
      "transport": "streamable-http"
    }
  }
}
```

## MCP Tools Reference

The server exposes 9 tools and returns **compact text** rather than JSON, which keeps token usage low (see [bench/RESULTS.md](bench/RESULTS.md)). General rules are sent once in the `initialize` result's `instructions` field, so they are not repeated in every tool description:

- All numbers (hand card, enemy, choice, potion slot) are **1-based** and match the latest state.
- Within one `act` call, numbers refer to the state **at call time**. They do not shift as cards are played, enemies die, or rewards are taken.
- `act` returns one line per action describing what changed, followed by the new state, so a follow-up `get_state` is rarely needed.

| Tool | Purpose |
|---|---|
| `get_state` | Current screen as text. Optional `include`: `deck`, `relics`, `piles`, `map` |
| `act` | Run one or more actions in order |
| `get_info` | Describe cards (with the upgraded version), relics, potions, or powers active in combat, by name or ID |
| `get_profile_progress` | Per-character stats and unlocks. Optional `include_cards`, `include_achievements` |
| `get_action_status` | Result of an action reported as still running (pass its `operation_id`) |
| `start_game` | `character` (IRONCLAD/SILENT/DEFECT/WATCHER), optional `ascension`, `seed` |
| `continue_game` / `abandon_run` / `save_game` | Main-menu run management |

Read-only tools carry `readOnlyHint`, and `abandon_run` carries `destructiveHint`, so clients can auto-approve safely.

### State format

```
[COMBAT] Act 1 Floor 1 | HP 69/80 | Gold 99 | Turn 2 | Energy 3/3
You: Block 0
Enemies (incoming 7 damage):
 1. Jaw Worm 23/41 | Attack 7 + Defend
Hand:
 1. Strike [1] Deal 6 damage. (target)
 2. Defend [1] Gain 5 Block.
 3. Bash [2] Deal 8 damage. Apply 2 Vulnerable. (target)
Piles: draw 1, discard 6, exhaust 0
Potions: 1. Fire Potion (target), 2. empty, 3. empty
Actions: play, end_turn, use_potion/discard_potion
```

- Card text already includes current damage, block, and magic numbers (affected by Strength, Frail, and so on).
- Card rewards, shop items, boss relics, and events list numbered choices with full text.
- Large card grids are shown as a single numbered line.
- On the map screen, the map appears automatically, showing only rooms still reachable: `y3: 0?>0 *1M>1,2` means row 3, column 0 is an event leading to column 0, and you (`*`) are at column 1, a monster room leading to columns 1 and 2.

### `act`

`{"actions": [...]}`, where each item has an `action` plus parameters:

| Action | Parameters |
|---|---|
| `play` | `card` (hand number, name like `"Strike+"`, or ID), `target?` (enemy number; optional with one enemy) |
| `end_turn` | |
| `choose` | `choice` |
| `proceed` / `confirm` | |
| `skip` / `cancel` | |
| `select` | `drop: [cards]` or `keep: [cards]` on hand-selection screens; auto-confirms |
| `use_potion` | `slot`, `target?` |
| `discard_potion` | `slot` |
| `wait` | `ms` (max 500) |

Example result:

```
1. play Anger -> Jaw Worm: Jaw Worm HP 41->35
2. play Strike -> Jaw Worm: Energy 3->2; Jaw Worm HP 35->29
3. play Strike -> Jaw Worm: Energy 2->1; Jaw Worm HP 29->23
4. end_turn: HP 80->69; Energy 1->3

[COMBAT] Act 1 Floor 1 | HP 69/80 | Gold 99 | Turn 2 | Energy 3/3
...
```

A batch stops early when the screen changes or an enemy's intent changes, and the result says so. A few follow-ups still run on the new screen:

- Index-free buttons (`proceed`, `confirm`, `skip`, `cancel`) when the new screen offers them, for example picking a card reward and then pressing `proceed`.
- `select` on a hand-selection screen opened by the previous card.

On an error, the result names the failed action, explains why, and lists what is available. It also includes the current state.

Older names are still accepted as aliases: `play_card`, `select_cards`, `card_index`, `card_name`, `card_id`, `target_index`, `choice_index`, `potion_slot`.

## Building from Source

```bash
# Clone repository
git clone <repo-url>
cd MCPTheSpire

# Set up dependencies in lib/
# - desktop-1.0.jar (from Slay the Spire)
# - ModTheSpire.jar
# - BaseMod.jar

# Build
mvn clean package

# Output: target/MCPTheSpire.jar
```

## Architecture

- **`MCPServer`**: HTTP server with Streamable HTTP transport
- **`MCPToolHandler`**: Tool definitions and execution
- **`StateRenderer`**: Compact text rendering of the game state
- **`Snapshot`**: Before/after capture used for settling and per-action change logs
- **`CommandExecutor`**: Safe command execution on game thread
- **Patches**: SpirePatch hooks for game events

## License

MIT License - see [LICENSE](LICENSE) file.

## Acknowledgments

- [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) - design inspiration
- [TelnetTheSpire](https://github.com/cdaymand/TelnetTheSpire/tree/master) - design inspiration
- [ModTheSpire](https://github.com/kiooeht/ModTheSpire) - Mod loading framework
- [BaseMod](https://github.com/daviscook477/BaseMod) - Essential modding library
