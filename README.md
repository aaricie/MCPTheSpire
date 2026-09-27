# MCPTheSpire

[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

A Slay the Spire mod that enables AI agents to play the game through the Model Context Protocol (MCP). This allows AI assistants like Claude, GPT, or other MCP-compatible clients to interact with and control Slay the Spire gameplay programmatically.

## Features

- **MCP Protocol Support**: Full implementation of Model Context Protocol using Streamable HTTP transport
- **Real-time Game Control**: AI agents can play cards, make choices, use potions, and navigate menus
- **Batch Actions**: Execute multiple actions in a single call for efficient gameplay
- **Game State Access**: Comprehensive access to player stats, hand, monsters, screen information, and card details on demand
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

### State Query Tools (Read-only)

#### `get_game_state`
Get complete game state with optional filtering.

**Parameters:**
- `include` (optional): Array of sections to include: `player`, `deck`, `relics`, `potions`, `combat`, `screen`
- Note: `map` is **excluded by default** (large/static data) - add `"map"` to include array if needed

**Example:**
```json
{"include": ["player", "combat", "map"]}
```

#### `get_map`
Get the full visible route graph for the current act, including room coordinates/types, connections, the current node, and `choice_index` values for the next reachable nodes. This is the simplest way to plan a path; call it again after moving to a new act.

#### `get_piles`
Get the cards currently visible in the draw, discard, and exhaust piles during combat. The draw-pile list is deliberately unordered because the game does not show its exact draw order.

#### `get_profile_progress`
Read character statistics and unlock progress for the active save slot, even at the main menu. The default response includes each character's wins, deaths, playtime, ascension, next unlock bundle, and locked cards, plus locked relics. Set `include_cards: true` for the complete per-character card catalogs or `include_achievements: true` for achievement details. This reports progress without navigating the Statistics screens or finishing a run.

#### `get_action_status`
If an action returns `status: "queued"` or `"resolving"`, pass its `operation_id` to this tool for the final result. A pending response does not mean the action failed; do not submit it again blindly.

#### `get_screen_state`
Get lightweight current screen state. **Recommended for most use cases.**

**Returns:**
- `screen_type`: Current screen (COMBAT_REWARD, CARD_REWARD, MAP, etc.)
- `room_phase`: Room phase (COMBAT, COMPLETE, etc.)
- `choice_list`: Available choices
- `can_proceed` / `can_cancel`: Button availability
- `hand`, `monsters`, `energy`: Combat info (if in combat)
- `cards_played_this_turn` and, with Velvet Choker, `card_play_limit` / `card_plays_remaining`
- `pile_counts`: Draw, discard, and exhaust pile sizes
- `readiness`: Why the game is ready or still resolving
- Player powers/block, potion descriptions, card descriptions, and monster attack details (if in combat)

On `HAND_SELECT`, `screen_state.eligible_choices` lists selectable cards, while `screen_state.full_hand_before_selection` preserves the hand before the game filtered those choices. On game-over screens, `screen_name` is normalized to `VICTORY` or `DEFEAT`; `raw_screen_name` preserves the game's internal label.

#### `get_available_commands`
Get context-aware list of available tools for current screen.

**Returns:**
```json
{
  "screen_type": "COMBAT_REWARD",
  "available_tools": [
    {"tool": "choose", "description": "Make a choice from: [gold, card]"},
    {"tool": "proceed", "description": "Click proceed button"}
  ]
}
```

#### `get_card_info`
Get detailed card information including description and upgraded version.

**When to use:** Card objects in game state are optimized for size and don't include descriptions. Use this tool when you need to know what a card does.

**Parameters:**
- `card_ids` (required): Array of card IDs to query

**Example:**
```json
{"card_ids": ["Strike_R", "Bash", "Defend_R"]}
```

**Returns:**
```json
{
  "cards": [
    {
      "id": "Bash",
      "name": "Bash",
      "type": "ATTACK",
      "rarity": "BASIC",
      "cost": 2,
      "description": "Deal !D! damage. Apply !M! Vulnerable.",
      "base_damage": 8,
      "base_magic_number": 2,
      "has_target": true,
      "upgraded": {
        "name": "Bash+",
        "description": "Deal !D! damage. Apply !M! Vulnerable.",
        "base_damage": 10,
        "base_magic_number": 3
      }
    }
  ]
}
```

### Batch Actions (Recommended)

#### `execute_actions`
Execute multiple actions in sequence. **This is the most efficient way to play.**

Pass action objects, not JSON strings: `{"actions":[{"action":"play_card","card_index":1,"target_index":1},{"action":"end_turn"}]}`.

Actions wait for the game to be ready (animations complete, state updated) before each execution and before returning. Each action includes compact `before` and `after` states. A batch stops early if the screen or a visible enemy intent changes, so the caller can reconsider the remaining actions.

**Key Feature: Stable Indices** - All `card_index` and `choice_index` values refer to positions **at the time you called the tool**, not after each action executes. You don't need to recalculate indices as cards are played or choices are made.

**Example - Stable card_index:**
```
Your hand: [1: Strike, 2: Defend, 3: Bash, 4: Strike, 5: Defend]
You want to play: Strike(1), Bash(3), Strike(4)

CORRECT - use original positions:
  card_index: 1, 3, 4

WRONG - don't recalculate after each play:
  card_index: 1, 2, 2  ← This is incorrect!
```

**Example - Stable choice_index:**
```
Rewards: [1: gold, 2: potion, 3: card, 4: relic]
You want: gold(1) and card(3)

CORRECT: choice_index: 1, 3
WRONG:   choice_index: 1, 2  ← Don't recalculate!
```

**Parameters:**
- `actions`: Array of action objects

**Supported Actions:**

| Action | Parameters | Description |
|--------|------------|-------------|
| `play_card` | `card_index` OR `card_name` OR `card_id`, `target_index?` | Play a card by position, name, or ID |
| `end_turn` | - | End turn |
| `choose` | `choice_index` | Make a choice (1-indexed) |
| `select_cards` | `drop` OR `keep` (array) | Select cards on HAND_SELECT screen |
| `proceed` | - | Click proceed button |
| `confirm` | - | Confirm selection (grid/hand select) |
| `skip` | - | Skip card reward |
| `cancel` | - | Cancel/return |
| `use_potion` | `potion_slot`, `target_index?` | Use potion |
| `discard_potion` | `potion_slot` | Discard potion |
| `wait` | `ms?` | Wait (max 500ms) |

**Action Parameters Detail:**

`play_card` - Play a card from hand
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `card_index` | integer | One of three | 1-indexed position in hand (stable) |
| `card_name` | string | One of three | Card display name (e.g., "Strike", "Strike+") |
| `card_id` | string | One of three | Card internal ID (e.g., "Strike_R") |
| `target_index` | integer | For targeted | 1-indexed monster target |

`choose` - Make a choice on current screen
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `choice_index` | integer | Yes | 1-indexed choice position (stable) |

`select_cards` - Select cards on HAND_SELECT screen
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `drop` | array | One of two | Cards to select (by index/name/id) |
| `keep` | array | One of two | Cards to keep (selects all others) |

`use_potion` - Use a potion
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `potion_slot` | integer | Yes | 1-indexed potion slot |
| `target_index` | integer | For targeted | 1-indexed monster target |

`discard_potion` - Discard a potion
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `potion_slot` | integer | Yes | 1-indexed potion slot |

`wait` - Wait for animations/state
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `ms` | integer | No | Milliseconds to wait (default: 100, max: 500) |

**Example - Play cards by name (recommended):**
```json
{
  "actions": [
    {"action": "play_card", "card_name": "Defend"},
    {"action": "play_card", "card_name": "Strike", "target_index": 1},
    {"action": "play_card", "card_name": "Bash", "target_index": 1},
    {"action": "end_turn"}
  ]
}
```

**Using card_name is recommended** - it automatically finds the card in your current hand, so you don't need to track index changes.

**Alternative - Play by index (indices are stable):**
```json
{
  "actions": [
    {"action": "play_card", "card_index": 1},
    {"action": "play_card", "card_index": 3},
    {"action": "play_card", "card_index": 5},
    {"action": "end_turn"}
  ]
}
```
Indices are **automatically stabilized** - `card_index: 1, 3, 5` plays the cards at those positions **when you called the tool**. No need to account for hand changes as cards are played.

**Example - Take rewards and proceed (indices are stable):**
```json
{
  "actions": [
    {"action": "choose", "choice_index": 1},
    {"action": "choose", "choice_index": 2},
    {"action": "proceed"}
  ]
}
```

**Example - select_cards for HAND_SELECT screen (e.g., after Survivor):**
```json
{
  "actions": [
    {"action": "play_card", "card_name": "Survivor"},
    {"action": "select_cards", "drop": ["Strike"]}
  ]
}
```

The `select_cards` action supports two modes:
- `drop`: Select these cards (to be discarded/affected)
- `keep`: Keep these cards, select all others

**Note:** `select_cards` automatically confirms after selection. No need for a separate `confirm` action.

Cards can be specified by:
- **Index**: `[1, 3]` (1-indexed, uses stable initial positions)
- **Name**: `["Strike", "Defend"]`
- **ID**: `["Strike_R", "Defend_G"]`
- **Mixed**: `[1, "Bash", "Defend_R"]`

**Example - Gambler's Chip (keep specific cards, discard rest):**
```json
{
  "actions": [
    {"action": "select_cards", "keep": ["Bash", "Inflame"]}
  ]
}
```
Inspect the settled hand before submitting the next play.

**Returns:**
```json
{
  "status": "ready",
  "settled": true,
  "actions_requested": 2,
  "actions_completed": 2,
  "partial": false,
  "actions": [
    {"index": 1, "action": {"action": "play_card", "card_name": "Defend"}, "before": {}, "after": {}},
    {"index": 2, "action": {"action": "end_turn"}, "before": {}, "after": {}}
  ],
  "after": {}
}
```

On error, the response has `status: "error"`, `failed_index` (1-indexed), `failed_action`, and `error`. On an expected early stop, `partial: true` and `stop_reason` explain why. A transition lasting more than the request's 20-second wait returns a pending `operation_id`; poll with `get_action_status`.

### Individual Action Tools

These execute single actions. Use `execute_actions` for efficiency when possible.

#### `play_card`
Play a card from hand. Specify ONE of:
- `card_name`: Card display name (e.g., "Strike", "Strike+", "Defend")
- `card_id`: Card internal ID (e.g., "Strike_R", "Defend_G")
- `card_index`: 1-indexed card position

Optional:
- `target_index`: 1-indexed monster target (for targeted cards)

**Examples:** `{card_name:'Bash',target_index:1}`, `{card_index:3,target_index:1}`, `{card_name:'Defend'}`

#### `end_turn`
End your current turn in combat.

#### `choose`
Make a choice from the current screen.
- `choice_index` (required): 1-indexed choice position

#### `use_potion`
Use a potion.
- `potion_slot` (required): 1-indexed potion slot
- `target_index` (optional): 1-indexed target

#### `discard_potion`
Discard a potion.
- `potion_slot` (required): 1-indexed potion slot

### Navigation Tools

| Tool | Description | Use When |
|------|-------------|----------|
| `proceed` | Leave current screen | Combat rewards, rest site, shop room, chest |
| `confirm` | Confirm selection | Grid select, hand select |
| `skip` | Skip without selecting | Card reward screen |
| `cancel` | Go back | Shop screen, map, boss reward |

### Game Management Tools

#### `start_game`
Start a new game.
- `character` (required): IRONCLAD, SILENT, DEFECT, or WATCHER
- `ascension` (optional): 0-20
- `seed` (optional): Alphanumeric seed

#### `continue_game`
Continue from save file.

#### `abandon_run`
Abandon current run.

#### `save_game`
Return to the main menu. Continue restores the game's latest autosave checkpoint; choices made since that checkpoint may need to be repeated. To preserve rewards from a room, advance to the next room before leaving.

## Screen Types Reference

| Screen Type | Available Actions |
|-------------|-------------------|
| `NONE` (Combat) | `play_card`, `end_turn`, `use_potion` |
| `COMBAT_REWARD` | `choose` (rewards), `proceed` |
| `CARD_REWARD` | `choose` (cards), `skip` |
| `MAP` | `choose` (nodes), `cancel` |
| `REST` | `choose` (rest/smith), `proceed` |
| `EVENT` | `choose` (options) |
| `SHOP_SCREEN` | `choose` (items), `cancel` |
| `SHOP_ROOM` | `choose`, `proceed` |
| `BOSS_REWARD` | `choose` (relics), `skip` |
| `GRID` | `choose`, `confirm` |
| `HAND_SELECT` | `select_cards` (auto-confirms) |

## Data Structures

Game state objects are optimized for minimal size. Fields are only included when meaningful.

### Card Object
| Field | Always | Description |
|-------|--------|-------------|
| `name`, `uuid`, `id`, `type`, `cost` | Yes | Core identity |
| `upgrades` | If > 0 | Times upgraded |
| `is_playable` | In combat | Can be played |
| `has_target` | If true | Requires target |
| `exhausts` | If true | Exhausts on play |
| `damage`, `block`, `magic_number` | If > 0 | Current values |
| `base_damage`, `base_block`, `base_magic_number` | If different | Base values (when modified) |

**Note:** Card descriptions are not included. Use `get_card_info` to query descriptions.

### Monster Object
| Field | Always | Description |
|-------|--------|-------------|
| `id`, `name`, `current_hp`, `max_hp`, `is_gone` | Yes | Core info |
| `intent` | Yes | Current intent |
| `move.damage`, `move.hits` | If attacking | Attack details |
| `block` | If > 0 | Current block |
| `powers` | If any | Active powers |
| `half_dead` | If true | Half dead state |

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
- **`GameStateConverter`**: Game state serialization
- **`CommandExecutor`**: Safe command execution on game thread
- **Patches**: SpirePatch hooks for game events

## License

MIT License - see [LICENSE](LICENSE) file.

## Acknowledgments

- [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) - design inspiration
- [TelnetTheSpire](https://github.com/cdaymand/TelnetTheSpire/tree/master) - design inspiration
- [ModTheSpire](https://github.com/kiooeht/ModTheSpire) - Mod loading framework
- [BaseMod](https://github.com/daviscook477/BaseMod) - Essential modding library
