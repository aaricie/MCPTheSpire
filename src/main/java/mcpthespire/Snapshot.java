package mcpthespire;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small capture of the game state, used to detect when an action has settled and to
 * describe what an action changed ("Jaw Worm HP 41->35, +Vulnerable 2").
 */
public final class Snapshot {

    public boolean inGame;
    public String screen = "";
    public boolean saveExists;
    public String readiness = "";
    boolean combat;
    int hp, maxHp, block, energy, gold, deckSize;
    Map<String, Integer> powers = new LinkedHashMap<>();
    final Map<AbstractMonster, MonsterState> monsters = new IdentityHashMap<>();
    final List<AbstractMonster> monsterOrder = new ArrayList<>();
    List<String> hand = new ArrayList<>();
    List<String> potions = new ArrayList<>();
    List<String> choices = new ArrayList<>();
    String piles = "";

    static final class MonsterState {
        String name;
        int hp, block;
        boolean alive;
        String intent;
        Map<String, Integer> powers;

        String signature() {
            return name + hp + "/" + block + alive + intent + powers;
        }
    }

    public Snapshot() {
    }

    public static Snapshot capture() {
        Snapshot s = new Snapshot();
        s.readiness = GameStateListener.getReadinessReason();
        s.inGame = CommandExecutor.isInDungeon();
        if (!s.inGame) {
            s.screen = "MAIN_MENU";
            if (CardCrawlGame.mainMenuScreen != null && CardCrawlGame.mainMenuScreen.screen != null) {
                s.screen += ":" + CardCrawlGame.mainMenuScreen.screen.name();
            }
            s.saveExists = CardCrawlGame.characterManager != null && CardCrawlGame.characterManager.anySaveFileExists();
            return s;
        }
        ChoiceScreenUtils.ChoiceType type = ChoiceScreenUtils.getCurrentChoiceType();
        boolean combat = AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT;
        s.combat = combat;
        s.screen = type == ChoiceScreenUtils.ChoiceType.NONE && combat ? "COMBAT" : type.name();
        s.deckSize = AbstractDungeon.player.masterDeck.size();
        s.hp = AbstractDungeon.player.currentHealth;
        s.maxHp = AbstractDungeon.player.maxHealth;
        s.gold = AbstractDungeon.player.gold;
        for (AbstractPotion potion : AbstractDungeon.player.potions) s.potions.add(potion.name);
        if (combat) {
            s.block = AbstractDungeon.player.currentBlock;
            s.energy = EnergyPanel.totalCount;
            s.powers = powers(AbstractDungeon.player);
            for (AbstractCard card : AbstractDungeon.player.hand.group) s.hand.add(card.uuid.toString());
            s.piles = AbstractDungeon.player.drawPile.size() + "/" + AbstractDungeon.player.discardPile.size() +
                "/" + AbstractDungeon.player.exhaustPile.size();
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room.monsters != null) {
            for (AbstractMonster m : room.monsters.monsters) {
                MonsterState ms = new MonsterState();
                ms.name = m.name;
                ms.hp = m.currentHealth;
                ms.block = m.currentBlock;
                ms.alive = !m.isDeadOrEscaped();
                ms.intent = ms.alive && combat ? StateRenderer.intent(m) : "";
                ms.powers = powers(m);
                s.monsters.put(m, ms);
                s.monsterOrder.add(m);
            }
        }
        if (CommandExecutor.isChooseCommandAvailable()) s.choices = ChoiceScreenUtils.getCurrentChoiceList();
        return s;
    }

    private static Map<String, Integer> powers(AbstractCreature creature) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (AbstractPower p : creature.powers) result.put(p.name, p.amount);
        return result;
    }

    /** Equality used to decide whether an action has had any visible effect yet. */
    public String signature() {
        StringBuilder s = new StringBuilder();
        s.append(inGame).append('|').append(screen).append('|').append(saveExists).append('|').append(readiness)
            .append('|').append(hp).append('/').append(maxHp).append('|').append(block).append('|').append(energy)
            .append('|').append(gold).append('|').append(deckSize).append('|').append(powers).append('|').append(hand).append('|').append(potions)
            .append('|').append(choices).append('|').append(piles);
        for (AbstractMonster m : monsterOrder) s.append('|').append(monsters.get(m).signature());
        return s.toString();
    }

    public boolean sameAs(Snapshot other) {
        return other != null && signature().equals(other.signature());
    }

    /** True when a monster present in both snapshots now shows a different intent. */
    public static boolean intentChanged(Snapshot before, Snapshot after) {
        for (Map.Entry<AbstractMonster, MonsterState> e : after.monsters.entrySet()) {
            MonsterState old = before.monsters.get(e.getKey());
            if (old != null && old.alive && e.getValue().alive && !Objects.equals(old.intent, e.getValue().intent)) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable list of changes, or "no visible change". */
    public static String diff(Snapshot a, Snapshot b) {
        List<String> parts = new ArrayList<>();
        if (a.inGame && b.inGame) {
            change(parts, "HP", a.hp, b.hp);
            change(parts, "Max HP", a.maxHp, b.maxHp);
            boolean bothCombat = a.combat && b.combat;
            if (bothCombat) {
                change(parts, "Block", a.block, b.block);
                change(parts, "Energy", a.energy, b.energy);
                powerChanges(parts, "", a.powers, b.powers);
            }
            change(parts, "Gold", a.gold, b.gold);
            change(parts, "Deck", a.deckSize, b.deckSize);
            potionChanges(parts, a.potions, b.potions);
            for (AbstractMonster m : b.monsterOrder) {
                MonsterState now = b.monsters.get(m);
                MonsterState was = a.monsters.get(m);
                if (was == null) {
                    if (now.alive) parts.add(now.name + " appeared");
                    continue;
                }
                if (was.alive && !now.alive) {
                    parts.add(now.name + (now.hp <= 0 ? " died" : " left"));
                    continue;
                }
                if (!now.alive) continue;
                List<String> mParts = new ArrayList<>();
                change(mParts, "HP", was.hp, now.hp);
                if (bothCombat) {
                    change(mParts, "Block", was.block, now.block);
                    powerChanges(mParts, "", was.powers, now.powers);
                }
                if (!mParts.isEmpty()) parts.add(now.name + " " + String.join(", ", mParts));
            }
        }
        if (!a.screen.equals(b.screen) || a.inGame != b.inGame) parts.add("screen -> " + b.screen);
        else if (!a.choices.equals(b.choices)) parts.add("options changed");
        return parts.isEmpty() ? "no visible change" : String.join("; ", parts);
    }

    private static void potionChanges(List<String> parts, List<String> was, List<String> now) {
        List<String> gained = new ArrayList<>(now);
        List<String> lost = new ArrayList<>(was);
        for (String p : was) gained.remove(p);
        for (String p : now) lost.remove(p);
        for (String p : gained) if (!"Potion Slot".equals(p)) parts.add("+" + p);
        for (String p : lost) if (!"Potion Slot".equals(p)) parts.add("-" + p);
    }

    private static void change(List<String> parts, String label, int was, int now) {
        if (was != now) parts.add(label + " " + was + "->" + now);
    }

    private static void powerChanges(List<String> parts, String prefix, Map<String, Integer> was, Map<String, Integer> now) {
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            Integer old = was.get(e.getKey());
            if (old == null) parts.add(prefix + "+" + e.getKey() + amount(e.getValue()));
            else if (!old.equals(e.getValue())) parts.add(prefix + e.getKey() + " " + old + "->" + e.getValue());
        }
        for (String name : was.keySet()) {
            if (!now.containsKey(name)) parts.add(prefix + "-" + name);
        }
    }

    private static String amount(int amount) {
        return amount == 0 || amount == -1 ? "" : " " + amount;
    }
}
