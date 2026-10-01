### start_game  start_game {"character": "IRONCLAD", "ascension": 0, "seed": "MCPBENCH1"}
Started IRONCLAD.
[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Talk]
Potions: none (3 slots)
Actions: choose 1-1

### state: Neow  get_state {}
[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Talk]
Potions: none (3 slots)
Actions: choose 1-1

### choose Talk  act {"actions": [{"action": "choose", "choice": 1}]}
1. choose Talk: options changed

[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Choose a Card to obtain]
 2. [Enemies in your next three combats have 1 HP]
 3. [Lose 8 Max HP Choose a rare colorless Card to obtain]
 4. [Lose your starting Relic Obtain a random boss Relic]
Potions: none (3 slots)
Actions: choose 1-4

### state: Neow options  get_state {}
[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Choose a Card to obtain]
 2. [Enemies in your next three combats have 1 HP]
 3. [Lose 8 Max HP Choose a rare colorless Card to obtain]
 4. [Lose your starting Relic Obtain a random boss Relic]
Potions: none (3 slots)
Actions: choose 1-4

### choose card bonus  act {"actions": [{"action": "choose", "choice": 1}]}
1. choose Choose a Card to obtain: screen -> CARD_REWARD

[CARD_REWARD] Act 1 Floor 0 | HP 80/80 | Gold 99
Card reward (pick one or skip):
 1. Armaments [1] Skill, Common: Gain 5 Block. Upgrade a card in your hand for the rest of combat.
 2. Clash [0] Attack, Common: Can only be played if every card in your hand is an Attack. Deal 14 damage.
 3. Anger [0] Attack, Common: Deal 6 damage. Add a copy of this card into your discard pile.
Potions: none (3 slots)
Actions: choose 1-3, skip

### state: card reward  get_state {}
[CARD_REWARD] Act 1 Floor 0 | HP 80/80 | Gold 99
Card reward (pick one or skip):
 1. Armaments [1] Skill, Common: Gain 5 Block. Upgrade a card in your hand for the rest of combat.
 2. Clash [0] Attack, Common: Can only be played if every card in your hand is an Attack. Deal 14 damage.
 3. Anger [0] Attack, Common: Deal 6 damage. Add a copy of this card into your discard pile.
Potions: none (3 slots)
Actions: choose 1-3, skip

### take Anger  act {"actions": [{"action": "choose", "choice": 3}]}
1. choose Anger: Deck 10->11; screen -> EVENT

[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Leave]
Potions: none (3 slots)
Actions: choose 1-1

### state: Neow leave  get_state {}
[EVENT] Act 1 Floor 0 | HP 80/80 | Gold 99
Event: Neow
Choices:
 1. [Leave]
Potions: none (3 slots)
Actions: choose 1-1

### leave Neow  act {"actions": [{"action": "choose", "choice": 1}]}
1. choose Leave: screen -> MAP

[MAP] Act 1 Floor 0 | HP 80/80 | Gold 99
Map act 1, boss Hexaghost. Row y: column+room>next columns; M monster, E elite, R rest, $ shop, T treasure, ? event, * you
y0: 0M>1 1M>2 3M>3 4M>5
y1: 1M>1 2M>1 3M>2 5M>4,5
y2: 1M>0,1 2?>2 4M>3 5?>4,5
y3: 0?>0 1M>1 2$>2 3M>3 4?>3 5M>4
y4: 0$>0 1$>0 2M>2 3M>2,3 4M>5
y5: 0E>0,1 2R>1,2 3E>3 5R>5
y6: 0M>0 1?>1,2 2M>2 3M>2 5E>5
y7: 0R>0 1R>0 2?>2,3 5R>4
y8: 0T>1 2T>1 3T>4 4T>4
y9: 1R>0,2 4?>3,5
y10: 0M>0,1 2E>3 3?>4 5M>5,6
y11: 0?>0 1E>2 3?>3 4M>3 5M>4 6R>5
y12: 0M>1 2M>3 3M>3,4 4?>5 5M>5
y13: 1?>2 3?>3,4 4M>4 5M>4,6
y14: 2R>boss 3R>boss 4R>boss 6R>boss
Next: 1. y0 0M (monster) 2. y0 1M (monster) 3. y0 3M (monster) 4. y0 4M (monster)
Potions: none (3 slots)
Actions: choose 1-4, cancel

### state: map screen  get_state {}
[MAP] Act 1 Floor 0 | HP 80/80 | Gold 99
Map act 1, boss Hexaghost. Row y: column+room>next columns; M monster, E elite, R rest, $ shop, T treasure, ? event, * you
y0: 0M>1 1M>2 3M>3 4M>5
y1: 1M>1 2M>1 3M>2 5M>4,5
y2: 1M>0,1 2?>2 4M>3 5?>4,5
y3: 0?>0 1M>1 2$>2 3M>3 4?>3 5M>4
y4: 0$>0 1$>0 2M>2 3M>2,3 4M>5
y5: 0E>0,1 2R>1,2 3E>3 5R>5
y6: 0M>0 1?>1,2 2M>2 3M>2 5E>5
y7: 0R>0 1R>0 2?>2,3 5R>4
y8: 0T>1 2T>1 3T>4 4T>4
y9: 1R>0,2 4?>3,5
y10: 0M>0,1 2E>3 3?>4 5M>5,6
y11: 0?>0 1E>2 3?>3 4M>3 5M>4 6R>5
y12: 0M>1 2M>3 3M>3,4 4?>5 5M>5
y13: 1?>2 3?>3,4 4M>4 5M>4,6
y14: 2R>boss 3R>boss 4R>boss 6R>boss
Next: 1. y0 0M (monster) 2. y0 1M (monster) 3. y0 3M (monster) 4. y0 4M (monster)
Potions: none (3 slots)
Actions: choose 1-4, cancel

### full map  get_state {"include": ["map"]}
[MAP] Act 1 Floor 0 | HP 80/80 | Gold 99
Map act 1, boss Hexaghost. Row y: column+room>next columns; M monster, E elite, R rest, $ shop, T treasure, ? event, * you
y0: 0M>1 1M>2 3M>3 4M>5
y1: 1M>1 2M>1 3M>2 5M>4,5
y2: 1M>0,1 2?>2 4M>3 5?>4,5
y3: 0?>0 1M>1 2$>2 3M>3 4?>3 5M>4
y4: 0$>0 1$>0 2M>2 3M>2,3 4M>5
y5: 0E>0,1 2R>1,2 3E>3 5R>5
y6: 0M>0 1?>1,2 2M>2 3M>2 5E>5
y7: 0R>0 1R>0 2?>2,3 5R>4
y8: 0T>1 2T>1 3T>4 4T>4
y9: 1R>0,2 4?>3,5
y10: 0M>0,1 2E>3 3?>4 5M>5,6
y11: 0?>0 1E>2 3?>3 4M>3 5M>4 6R>5
y12: 0M>1 2M>3 3M>3,4 4?>5 5M>5
y13: 1?>2 3?>3,4 4M>4 5M>4,6
y14: 2R>boss 3R>boss 4R>boss 6R>boss
Next: 1. y0 0M (monster) 2. y0 1M (monster) 3. y0 3M (monster) 4. y0 4M (monster)
Potions: none (3 slots)
Actions: choose 1-4, cancel

### enter combat  act {"actions": [{"action": "choose", "choice": 1}]}
1. choose (x=0, y=0) Enemy: Jaw Worm appeared; screen -> COMBAT

[COMBAT] Act 1 Floor 1 | HP 80/80 | Gold 99 | Turn 1 | Energy 3/3
You: Block 0
Enemies (incoming 11 damage):
 1. Jaw Worm 41/41 | Attack 11
Hand:
 1. Strike [1] Deal 6 damage. (target)
 2. Strike [1] Deal 6 damage. (target)
 3. Anger [0] Deal 6 damage. Add a copy of this card into your discard pile. (target)
 4. Defend [1] Gain 5 Block.
 5. Defend [1] Gain 5 Block.
Piles: draw 6, discard 0, exhaust 0
Potions: none (3 slots)
Actions: play, end_turn

### state: combat T1  get_state {}
[COMBAT] Act 1 Floor 1 | HP 80/80 | Gold 99 | Turn 1 | Energy 3/3
You: Block 0
Enemies (incoming 11 damage):
 1. Jaw Worm 41/41 | Attack 11
Hand:
 1. Strike [1] Deal 6 damage. (target)
 2. Strike [1] Deal 6 damage. (target)
 3. Anger [0] Deal 6 damage. Add a copy of this card into your discard pile. (target)
 4. Defend [1] Gain 5 Block.
 5. Defend [1] Gain 5 Block.
Piles: draw 6, discard 0, exhaust 0
Potions: none (3 slots)
Actions: play, end_turn

### turn 1 batch  act {"actions": [{"action": "play", "card": "Anger", "target": 1}, {"action": "play", "card": 1, "target": 1}, {"action": "play", "card": 2, "target": 1}, {"action": "end_turn"}]}
1. play Anger -> Jaw Worm: Jaw Worm HP 41->35
2. play Strike -> Jaw Worm: Energy 3->2; Jaw Worm HP 35->29
3. play Strike -> Jaw Worm: Energy 2->1; Jaw Worm HP 29->23
4. end_turn: HP 80->69; Energy 1->3

[COMBAT] Act 1 Floor 1 | HP 69/80 | Gold 99 | Turn 2 | Energy 3/3
You: Block 0
Enemies (incoming 7 damage):
 1. Jaw Worm 23/41 | Attack 7 + Defend
Hand:
 1. Strike [1] Deal 6 damage. (target)
 2. Defend [1] Gain 5 Block.
 3. Strike [1] Deal 6 damage. (target)
 4. Defend [1] Gain 5 Block.
 5. Strike [1] Deal 6 damage. (target)
Piles: draw 1, discard 6, exhaust 0
Potions: none (3 slots)
Actions: play, end_turn

### turn 2 batch  act {"actions": [{"action": "play", "card": 1, "target": 1}, {"action": "play", "card": 3, "target": 1}, {"action": "play", "card": "Defend"}, {"action": "end_turn"}]}
1. play Strike -> Jaw Worm: Energy 3->2; Jaw Worm HP 23->17
2. play Strike -> Jaw Worm: Energy 2->1; Jaw Worm HP 17->11
3. play Defend: Block 0->5; Energy 1->0
4. end_turn: HP 69->67; Block 5->0; Energy 0->3; Jaw Worm Block 0->5

[COMBAT] Act 1 Floor 1 | HP 67/80 | Gold 99 | Turn 3 | Energy 3/3
You: Block 0
Enemies (incoming 7 damage):
 1. Jaw Worm 11/41 Block 5 | Attack 7 + Defend
Hand:
 1. Bash [2] Deal 8 damage. Apply 2 Vulnerable. (target)
 2. Anger [0] Deal 6 damage. Add a copy of this card into your discard pile. (target)
 3. Defend [1] Gain 5 Block.
 4. Strike [1] Deal 6 damage. (target)
 5. Strike [1] Deal 6 damage. (target)
Piles: draw 7, discard 0, exhaust 0
Potions: none (3 slots)
Actions: play, end_turn

### turn 3 batch (kill)  act {"actions": [{"action": "play", "card": "Bash", "target": 1}, {"action": "play", "card": "Anger", "target": 1}, {"action": "play", "card": "Strike", "target": 1}]}
Stopped after 2/3: the screen changed; remaining actions were not run.
1. play Bash -> Jaw Worm: Energy 3->1; Jaw Worm HP 11->8, Block 5->0, +Vulnerable 2
2. play Anger -> Jaw Worm: HP 67->73; Jaw Worm died; screen -> COMBAT_REWARD

[COMBAT_REWARD] Act 1 Floor 1 | HP 73/80 | Gold 99
Rewards:
 1. 10 gold
 2. Card (opens a card choice)
Potions: none (3 slots)
Actions: choose 1-2, proceed

### full game state  get_state {"include": ["deck", "relics"]}
[COMBAT_REWARD] Act 1 Floor 1 | HP 73/80 | Gold 99
Rewards:
 1. 10 gold
 2. Card (opens a card choice)
Potions: none (3 slots)
Deck (11): Strike x5, Defend x4, Bash, Anger
Relics:
 - Burning Blood: At the end of combat, heal 6 HP.
Actions: choose 1-2, proceed

### take rewards  act {"actions": [{"action": "choose", "choice": 1}, {"action": "choose", "choice": 2}]}
1. choose 10 gold: Gold 99->109; options changed
2. choose card: screen -> CARD_REWARD

[CARD_REWARD] Act 1 Floor 1 | HP 73/80 | Gold 109
Card reward (pick one or skip):
 1. Armaments [1] Skill, Common: Gain 5 Block. Upgrade a card in your hand for the rest of combat.
 2. True Grit [1] Skill, Common: Gain 7 Block. Exhaust 1 card at random.
 3. Flex [0] Skill, Common: Gain 2 Strength. At the end of this turn, lose 2 Strength.
Potions: none (3 slots)
Actions: choose 1-3, skip

### state: card reward  get_state {}
[CARD_REWARD] Act 1 Floor 1 | HP 73/80 | Gold 109
Card reward (pick one or skip):
 1. Armaments [1] Skill, Common: Gain 5 Block. Upgrade a card in your hand for the rest of combat.
 2. True Grit [1] Skill, Common: Gain 7 Block. Exhaust 1 card at random.
 3. Flex [0] Skill, Common: Gain 2 Strength. At the end of this turn, lose 2 Strength.
Potions: none (3 slots)
Actions: choose 1-3, skip

### card info x3  get_info {"names": ["Armaments", "True Grit", "Flex"]}
Card Armaments [1] Skill, Common: Gain 5 Block. Upgrade a card in your hand for the rest of combat. | Upgraded Armaments+ [1]: Gain 5 Block. Upgrade all cards in your hand for the rest of combat.
Card True Grit [1] Skill, Common: Gain 7 Block. Exhaust 1 card at random. | Upgraded True Grit+ [1]: Gain 9 Block. Exhaust 1 card.
Card Flex [0] Skill, Common: Gain 2 Strength. At the end of this turn, lose 2 Strength. | Upgraded Flex+ [0]: Gain 4 Strength. At the end of this turn, lose 4 Strength.
