# Token results (after optimization)

This compares the same seeded trace (Ironclad A0, seed `MCPBENCH1`) before and after the change: Neow, the first map, one three-turn Jaw Worm fight, and the rewards. The runs are reproducible: the replay drew exactly the same hands and enemy moves as the baseline.

Token counts use tiktoken `o200k_base` as a proxy for Claude's tokenizer. Run it again with `python bench/replay.py` (game at the main menu, no saved run). The baseline is described in [BASELINE.md](BASELINE.md), and the new output is in `after/transcript.md`.

| Step | Before | After | Saved |
|---|---:|---:|---:|
| **Tool definitions** (23 → 9 tools, sent with every request) | 2,150 | 895 | **58%** |
| Combat turn: 3 cards + end turn | 3,283 | 241 | **93%** |
| Combat turn: 3 cards + end turn | 3,335 | 279 | **92%** |
| Combat turn: lethal (batch stops at rewards) | 1,476 | 137 | **91%** |
| Combat state read | 576 | 172 | 70% |
| Full map | 3,098 | 526 | 83% |
| Full game state (deck + relics) | 825 | 95 | 88% |
| Card info x3 | 249 | 147 | 41% |
| Profile progress | 1,631 | 512 | 69% |
| **Whole trace (21 tool results)** | **16,283** | **3,845** | **76%** |

Two steps cost more than before. Leaving Neow went from 204 to 535 tokens, and a map-screen read went from 277 to 526, because the map screen now embeds the route map. Before, the model needed a separate `get_map` call (3,098 tokens) to plan a route. The embedded map lists only rooms still reachable, so it shrinks every floor.

The trace also over-counts the new version. It keeps the baseline's `get_state` call after every action so the steps line up, but `act` already returns the new state, so in real play those calls disappear. A realistic loop for this fight (map pick, three turns, rewards) is about **1,000 tokens instead of about 9,900**, a 90% cut.

## What changed

- **Compact text instead of JSON.**
- **No duplicate before/after snapshots per action.** Each action gets one line describing what it changed (HP, block, energy, powers, kills, gold, deck, potions, screen), followed by the final state once.
- **Removed noise:** UUIDs, redundant flags (`ready_for_command`, `in_game`, `is_screen_up`, `screen_name`, `is_gone`), and Gson HTML escaping.
- **Card text resolved with live numbers**, which also fixes Frail and Strength showing stale numbers. Reward, shop, and grid cards now carry their text, so no extra `get_info` round trip is needed.
- **Map as a reachable-only adjacency list**, embedded on the map screen.
- **Tool surface consolidated:** `get_state`, `act`, `get_info`, `get_profile_progress`, `get_action_status`, plus 4 run-management tools. Shared rules moved to the `initialize` instructions, and tool annotations were added.

## Logic fixes

- Every index is 1-based. Event options used to be 0-based while `choose` was 1-based.
- Enemy targets stay stable within a batch. Before, killing enemy 1 silently shifted "target 2" onto the wrong enemy. Now it resolves to the same monster or fails with "already dead".
- Card names resolve after an upgrade: `"Bash"` still finds `Bash+`.
- `select` with multiple cards no longer skips cards. Before, each pick shifted the remaining hand indices.
- Reward and deck cards no longer report a misleading `is_playable: false`.
- `end_turn` reports what the enemies did (HP lost, buffs gained).
- Profile stats: `highest_ascension: 51` was actually the furthest floor reached, and is now labeled that way.
- Errors are actionable, for example: `no card in hand can be played now (energy 0)`, `target 3 out of range 1-2`, `no 'Whirlwind' in hand [Strike, Defend]`.
