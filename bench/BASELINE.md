# Token baseline (before optimization)

Captured 2026-10-01 against the live mod (commit `6126543`), Ironclad A0, seed `MCPBENCH1`.
Token counts use tiktoken `o200k_base` as a proxy. Claude's tokenizer gives different absolute
numbers, but the before/after ratios carry over. Raw responses are in `baseline/`.

## Fixed cost: tool definitions (sent with every model request)

| Item | Tokens |
|---|---|
| `tools/list`, 23 tools (minified) | **2,150** |
| `execute_actions` definition alone | 595 |
| `play_card` definition | 209 |
| 11 single-action and navigation tools that duplicate `execute_actions` | ~760 |

## Per-call costs (measured)

| Call | Situation | Tokens |
|---|---|---|
| `execute_actions` (3 plays + end turn) | combat turn 1 | **3,283** |
| `execute_actions` (3 plays + end turn) | combat turn 2 | **3,335** |
| `execute_actions` (2 plays, combat ends) | kill | 1,476 |
| `get_screen_state` | combat, 5 cards, 1 enemy | 576 |
| `choose` (map node into combat) | | 584 |
| `get_map` | act 1 | **3,098** |
| `get_profile_progress` | main menu | 1,631 |
| `get_game_state` (all sections) | reward screen, 11-card deck | 825 |
| `get_screen_state` | event / card reward / map | 125 to 301 |
| `get_card_info` (3 cards) | needed to judge a card reward | 249 |

A single three-turn Jaw Worm fight cost about **8.7k tokens** of tool output.
Extrapolated to a full run (about 50 floors and 150+ combat turns), that comes to **500k+ tokens of
tool output**. In most agent loops this output also stays in context and is re-read on every later step.

## Where the tokens go

1. **Batch responses snapshot every action twice.** Each action returns full `before` and `after`
   states, each action's `after` is identical to the next action's `before`, and the final `after`
   repeats again. This accounts for about 85% of a combat turn.
2. **UUIDs on every card.** Each one costs about 20 tokens, and agents never need them because
   stable indices are resolved server-side.
3. **Redundant fields.** These include `ready_for_command` alongside `readiness`, `in_game`,
   `is_screen_up`, `screen_name` alongside `screen_type`, `room_type`, HP and energy repeated at the
   top level and under `player`, `is_gone` (always false, since dead enemies are filtered out),
   three `{"name":"Potion Slot",...}` objects for empty slots, event options listed three times
   (`choice_list` plus `text` plus `label`), and HAND_SELECT `hand` duplicating `eligible_choices`.
4. **Gson HTML escaping.** `=` becomes `=` and `'` becomes `'`.
5. **Map as nested JSON objects** with coordinate objects for every parent and child edge.
6. **Envelope noise.** Single actions return a nested MCP `acknowledgement` object, an
   `operation_id` even after the action has settled, and both `status` and `settled`.

## Logic and usability problems found during the trace

- Event `screen_state.options[].choice_index` is **0-indexed**, but `choose` is **1-indexed**.
  Map `next_nodes` uses 1-indexed values.
- Card descriptions contain raw placeholders (`"Deal !D! damage. NL Apply !M! Vulnerable."`), so the
  model has to cross-reference numeric fields to work out what a card does.
- Card rewards, shop cards, and grid cards carry no description and no damage or block values, which
  forces an extra `get_card_info` round trip for every reward decision.
- Reward and deck cards report `is_playable: false` because playability is checked outside combat.
  This is misleading.
- `end_turn` does not say what the enemies did. The model has to diff HP values to find out.
- There are four overlapping ways to read state (`get_game_state`, `get_screen_state`,
  `get_available_commands`, and the `after` field of actions) and two ways to act (11 single tools
  or `execute_actions`).
