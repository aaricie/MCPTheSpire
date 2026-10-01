"""Replay the baseline trace (seed MCPBENCH1) against the new tool surface and compare token costs.

Usage: python bench/replay.py   (game running with the mod, at the main menu with no saved run)
Token counts use tiktoken o200k_base as a proxy for Claude's tokenizer; ratios are what matter.
"""
import json
import os
import urllib.request

import tiktoken

ENC = tiktoken.get_encoding("o200k_base")
URL = "http://127.0.0.1:8080/mcp"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "after")
_id = [0]


def tok(text):
    return len(ENC.encode(text))


def rpc(method, params=None):
    _id[0] += 1
    body = {"jsonrpc": "2.0", "id": _id[0], "method": method}
    if params is not None:
        body["params"] = params
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())


def call(name, args=None):
    r = rpc("tools/call", {"name": name, "arguments": args or {}})["result"]
    return r["content"][0]["text"], r.get("isError", False)


def act(*actions):
    return ("act", {"actions": list(actions)})


# (label, baseline file in bench/baseline or a literal baseline token count, new call)
TRACE = [
    ("start_game", 144, ("start_game", {"character": "IRONCLAD", "ascension": 0, "seed": "MCPBENCH1"})),
    ("state: Neow", 125, ("get_state", {})),
    ("choose Talk", 195, act({"action": "choose", "choice": 1})),
    ("state: Neow options", 301, ("get_state", {})),
    ("choose card bonus", 224, act({"action": "choose", "choice": 1})),
    ("state: card reward", 283, ("get_state", {})),
    ("available commands", 123, None),  # folded into every state's Actions line
    ("take Anger", 179, act({"action": "choose", "choice": 3})),
    ("state: Neow leave", 125, ("get_state", {})),
    ("leave Neow", 204, act({"action": "choose", "choice": 1})),
    ("state: map screen", 277, ("get_state", {})),
    ("full map", "get_map_act1.txt", ("get_state", {"include": ["map"]})),
    ("enter combat", "choose_map_into_combat.txt", act({"action": "choose", "choice": 1})),
    ("state: combat T1", "screen_combat_t1.txt", ("get_state", {})),
    ("turn 1 batch", "exec_turn1.txt", act(
        {"action": "play", "card": "Anger", "target": 1}, {"action": "play", "card": 1, "target": 1},
        {"action": "play", "card": 2, "target": 1}, {"action": "end_turn"})),
    ("turn 2 batch", "exec_turn2.txt", act(
        {"action": "play", "card": 1, "target": 1}, {"action": "play", "card": 3, "target": 1},
        {"action": "play", "card": "Defend"}, {"action": "end_turn"})),
    ("turn 3 batch (kill)", "exec_turn3_kill.txt", act(
        {"action": "play", "card": "Bash", "target": 1}, {"action": "play", "card": "Anger", "target": 1},
        {"action": "play", "card": "Strike", "target": 1})),
    ("full game state", "game_state_reward.txt", ("get_state", {"include": ["deck", "relics"]})),
    ("take rewards", "exec_rewards.txt", act({"action": "choose", "choice": 1}, {"action": "choose", "choice": 2})),
    ("state: card reward", 291, ("get_state", {})),
    ("card info x3", 249, ("get_info", {"names": ["Armaments", "True Grit", "Flex"]})),
]


def baseline_tokens(ref):
    if isinstance(ref, int):
        return ref
    text = open(os.path.join(HERE, "baseline", ref), encoding="utf-8").read()
    if text.startswith("##"):
        text = text.split("\n", 1)[1]
    return tok(text)


def main():
    os.makedirs(OUT, exist_ok=True)
    rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                       "clientInfo": {"name": "bench", "version": "0"}})
    tools = rpc("tools/list")["result"]["tools"]
    tools_json = json.dumps(tools, separators=(",", ":"))
    with open(os.path.join(OUT, "tools_list.json"), "w", encoding="utf-8") as f:
        json.dump(tools, f, indent=1)
    base_tools = json.load(open(os.path.join(HERE, "baseline", "tools_list.json"), encoding="utf-8"))
    rows = [("tools/list (%d -> %d tools)" % (len(base_tools), len(tools)),
             tok(json.dumps(base_tools, separators=(",", ":"))), tok(tools_json))]

    transcript = []
    for label, base_ref, new_call in TRACE:
        before = baseline_tokens(base_ref)
        if new_call is None:
            rows.append((label, before, 0))
            continue
        text, err = call(*new_call)
        transcript.append("### %s  %s %s%s\n%s\n" % (label, new_call[0], json.dumps(new_call[1]),
                                                     "  [isError]" if err else "", text))
        rows.append((label, before, tok(text)))

    with open(os.path.join(OUT, "transcript.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(transcript))

    print("%-34s %9s %9s %7s" % ("step", "before", "after", "saved"))
    total_b = total_a = 0
    for label, b, a in rows:
        if not label.startswith("tools/list"):
            total_b += b
            total_a += a
        print("%-34s %9d %9d %6.0f%%" % (label, b, a, 100 * (1 - a / b) if b else 0))
    print("%-34s %9d %9d %6.0f%%" % ("TOTAL tool output (trace)", total_b, total_a, 100 * (1 - total_a / total_b)))


if __name__ == "__main__":
    main()
