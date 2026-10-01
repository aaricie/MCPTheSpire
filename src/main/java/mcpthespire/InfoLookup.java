package mcpthespire;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.PotionHelper;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;

import java.util.ArrayList;
import java.util.List;

/** Looks up cards, relics, potions, and active powers by name or ID for get_info. */
public final class InfoLookup {

    private InfoLookup() {
    }

    public static String describe(List<String> queries) {
        StringBuilder out = new StringBuilder();
        for (String query : queries) {
            out.append(describe(query.trim())).append('\n');
        }
        return out.toString().trim();
    }

    private static String describe(String query) {
        if (CardCrawlGame.languagePack == null) return query + ": not found (game data not loaded yet)";
        List<String> found = new ArrayList<>();
        String card = card(query);
        if (card != null) found.add(card);
        String relic = relic(query);
        if (relic != null) found.add(relic);
        String potion = potion(query);
        if (potion != null) found.add(potion);
        found.addAll(powers(query));
        if (found.isEmpty()) return query + ": not found (try the exact name shown in the state)";
        return String.join("\n", found);
    }

    private static String card(String query) {
        boolean wantUpgrade = query.endsWith("+");
        String key = wantUpgrade ? query.substring(0, query.length() - 1) : query;
        AbstractCard base = null;
        if (CardLibrary.cards.containsKey(key)) {
            base = CardLibrary.cards.get(key);
        } else {
            for (AbstractCard c : CardLibrary.cards.values()) {
                if (c.name.equalsIgnoreCase(key) || c.cardID.equalsIgnoreCase(key)) {
                    base = c;
                    break;
                }
            }
        }
        if (base == null) return null;
        AbstractCard card = base.makeCopy();
        StringBuilder s = new StringBuilder("Card ").append(StateRenderer.cardOffer(card));
        if (card.canUpgrade()) {
            AbstractCard upgraded = card.makeCopy();
            upgraded.upgrade();
            s.append(" | Upgraded ").append(upgraded.name).append(" [").append(StateRenderer.cost(upgraded)).append("]: ")
                .append(StateRenderer.cardText(upgraded, false));
        }
        return s.toString();
    }

    private static String relic(String query) {
        AbstractRelic relic = null;
        if (RelicLibrary.isARelic(query)) {
            relic = RelicLibrary.getRelic(query);
        } else {
            List<ArrayList<AbstractRelic>> lists = new ArrayList<>();
            lists.add(RelicLibrary.starterList);
            lists.add(RelicLibrary.commonList);
            lists.add(RelicLibrary.uncommonList);
            lists.add(RelicLibrary.rareList);
            lists.add(RelicLibrary.bossList);
            lists.add(RelicLibrary.specialList);
            lists.add(RelicLibrary.shopList);
            for (ArrayList<AbstractRelic> list : lists) {
                if (list == null) continue;
                for (AbstractRelic r : list) {
                    if (r.name.equalsIgnoreCase(query) || r.relicId.equalsIgnoreCase(query)) {
                        relic = r;
                        break;
                    }
                }
                if (relic != null) break;
            }
        }
        if (relic == null) return null;
        return "Relic " + relic.name + " (" + StateRenderer.titleCase(relic.tier.name()) + "): " +
            StateRenderer.cleanText(relic.description);
    }

    private static String potion(String query) {
        AbstractPlayer.PlayerClass playerClass = CommandExecutor.isInDungeon() && AbstractDungeon.player != null
            ? AbstractDungeon.player.chosenClass : AbstractPlayer.PlayerClass.IRONCLAD;
        ArrayList<String> ids;
        try {
            ids = PotionHelper.getPotions(playerClass, true);
        } catch (RuntimeException e) {
            return null;
        }
        for (String id : ids) {
            AbstractPotion potion;
            try {
                potion = PotionHelper.getPotion(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (potion != null && (id.equalsIgnoreCase(query) || potion.name.equalsIgnoreCase(query))) {
                return "Potion " + potion.name + (potion.targetRequired ? " (target)" : "") + ": " +
                    StateRenderer.cleanText(potion.description);
            }
        }
        return null;
    }

    private static List<String> powers(String query) {
        List<String> result = new ArrayList<>();
        if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null) return result;
        addPowers(result, "you", AbstractDungeon.player, query);
        for (AbstractMonster m : StateRenderer.aliveMonsters()) addPowers(result, m.name, m, query);
        return result;
    }

    private static void addPowers(List<String> result, String owner, AbstractCreature creature, String query) {
        for (AbstractPower p : creature.powers) {
            if (p.name.equalsIgnoreCase(query) || p.ID.equalsIgnoreCase(query)) {
                result.add("Power " + p.name + " on " + owner + ": " + StateRenderer.cleanText(p.description));
            }
        }
    }
}
