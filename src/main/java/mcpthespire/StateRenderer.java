package mcpthespire;

import basemod.ReflectionHacks;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.orbs.EmptyOrbSlot;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.relics.RunicDome;
import com.megacrit.cardcrawl.relics.VelvetChoker;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.GameOverScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import mcpthespire.patches.UpdateBodyTextPatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders game state as compact, model-readable text.
 *
 * Every numbered list matches the index the matching action expects (1-based), and the
 * numbered choices are produced in the same order as ChoiceScreenUtils.getCurrentChoiceList().
 */
public final class StateRenderer {

    private StateRenderer() {
    }

    public static String render(Set<String> include) {
        StringBuilder out = new StringBuilder();
        if (!CommandExecutor.isInDungeon()) {
            renderMainMenu(out);
            return out.toString().trim();
        }

        ChoiceScreenUtils.ChoiceType type = ChoiceScreenUtils.getCurrentChoiceType();
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        boolean combat = room.phase == AbstractRoom.RoomPhase.COMBAT;

        out.append('[').append(screenLabel(type, combat)).append("] Act ").append(AbstractDungeon.actNum)
            .append(" Floor ").append(AbstractDungeon.floorNum)
            .append(" | HP ").append(AbstractDungeon.player.currentHealth).append('/').append(AbstractDungeon.player.maxHealth)
            .append(" | Gold ").append(AbstractDungeon.player.gold);
        if (combat) {
            out.append(" | Turn ").append(GameActionManager.turn)
                .append(" | Energy ").append(EnergyPanel.totalCount).append('/').append(AbstractDungeon.player.energy.energy);
        }
        out.append('\n');
        String readiness = GameStateListener.getReadinessReason();
        if (!"ready".equals(readiness)) {
            out.append("Status: ").append(readiness).append(" (game still resolving)\n");
        }

        if (combat) renderCombat(out);
        renderScreen(out, type, include);
        renderPotions(out);

        if (include.contains("deck")) renderDeck(out);
        if (include.contains("relics")) renderRelics(out);
        if (include.contains("piles") && combat) renderPiles(out);
        if (include.contains("map") && type != ChoiceScreenUtils.ChoiceType.MAP) renderMap(out);

        out.append("Actions: ").append(availableActions(type, combat));
        return out.toString().trim();
    }

    // ---------------------------------------------------------------- header / menu

    public static String screenLabel() {
        return screenLabel(ChoiceScreenUtils.getCurrentChoiceType(),
            AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT);
    }

    private static String screenLabel(ChoiceScreenUtils.ChoiceType type, boolean combat) {
        if (type == ChoiceScreenUtils.ChoiceType.GAME_OVER) return isVictory() ? "VICTORY" : "DEFEAT";
        if (type == ChoiceScreenUtils.ChoiceType.NONE) return combat ? "COMBAT" : "NONE";
        return type.name();
    }

    private static void renderMainMenu(StringBuilder out) {
        out.append("[MAIN_MENU]");
        String readiness = GameStateListener.getReadinessReason();
        if (!"ready".equals(readiness)) out.append(" Status: ").append(readiness);
        out.append('\n');
        boolean save = CardCrawlGame.characterManager != null && CardCrawlGame.characterManager.anySaveFileExists();
        out.append("Saved run: ").append(save ? "yes" : "no").append('\n');
        out.append("Actions: ").append(save ? "continue_game, abandon_run" : "start_game")
            .append(", get_profile_progress");
    }

    public static String availableActions() {
        return availableActions(ChoiceScreenUtils.getCurrentChoiceType(),
            AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT);
    }

    private static String availableActions(ChoiceScreenUtils.ChoiceType type, boolean combat) {
        List<String> actions = new ArrayList<>();
        if (combat && !AbstractDungeon.isScreenUp) {
            if (CommandExecutor.isPlayCommandAvailable()) actions.add("play");
            actions.add("end_turn");
        }
        if (type == ChoiceScreenUtils.ChoiceType.HAND_SELECT) {
            actions.add("select");
        } else if (CommandExecutor.isChooseCommandAvailable()) {
            actions.add("choose 1-" + ChoiceScreenUtils.getCurrentChoiceList().size());
        }
        if (ChoiceScreenUtils.isConfirmButtonAvailable()) actions.add(ChoiceScreenUtils.getConfirmButtonText());
        if (ChoiceScreenUtils.isCancelButtonAvailable()) {
            actions.add("skip".equals(ChoiceScreenUtils.getCancelButtonText()) ? "skip" : "cancel");
        }
        if (CommandExecutor.isPotionCommandAvailable()) actions.add("use_potion/discard_potion");
        if (actions.isEmpty()) actions.add("wait");
        return String.join(", ", actions);
    }

    // ---------------------------------------------------------------- combat

    private static void renderCombat(StringBuilder out) {
        out.append("You: Block ").append(AbstractDungeon.player.currentBlock);
        String powers = powers(AbstractDungeon.player);
        if (!powers.isEmpty()) out.append(" | ").append(powers);
        if (AbstractDungeon.player.stance != null && !"Neutral".equals(AbstractDungeon.player.stance.ID)) {
            out.append(" | Stance ").append(AbstractDungeon.player.stance.name);
        }
        out.append('\n');
        if (AbstractDungeon.player.maxOrbs > 0 && AbstractDungeon.player.orbs != null) {
            out.append("Orbs (next evoke first):");
            for (AbstractOrb orb : AbstractDungeon.player.orbs) {
                if (orb instanceof EmptyOrbSlot) out.append(" [empty]");
                else out.append(" [").append(orb.name).append(' ').append(orb.passiveAmount).append('/')
                    .append(orb.evokeAmount).append(']');
            }
            out.append(" (passive/evoke)\n");
        }

        List<AbstractMonster> monsters = aliveMonsters();
        int incoming = 0;
        StringBuilder enemies = new StringBuilder();
        for (int i = 0; i < monsters.size(); i++) {
            AbstractMonster m = monsters.get(i);
            enemies.append(' ').append(i + 1).append(". ").append(m.name).append(' ')
                .append(m.currentHealth).append('/').append(m.maxHealth);
            if (m.currentBlock > 0) enemies.append(" Block ").append(m.currentBlock);
            if (m.halfDead) enemies.append(" (half-dead)");
            enemies.append(" | ").append(intent(m));
            String mp = powers(m);
            if (!mp.isEmpty()) enemies.append(" | ").append(mp);
            enemies.append('\n');
            incoming += incomingDamage(m);
        }
        out.append("Enemies");
        if (incoming > 0) out.append(" (incoming ").append(incoming).append(" damage)");
        out.append(":\n").append(enemies);

        out.append("Hand:\n");
        ArrayList<AbstractCard> hand = AbstractDungeon.player.hand.group;
        boolean anyPlayable = !AbstractDungeon.isScreenUp && CommandExecutor.isPlayCommandAvailable();
        for (int i = 0; i < hand.size(); i++) {
            AbstractCard card = hand.get(i);
            out.append(' ').append(i + 1).append(". ").append(cardLine(card, true));
            if (anyPlayable && !card.canUse(AbstractDungeon.player, null)) out.append(" (can't play now)");
            out.append('\n');
        }
        if (hand.isEmpty()) out.append(" (empty)\n");

        out.append("Piles: draw ").append(AbstractDungeon.player.drawPile.size())
            .append(", discard ").append(AbstractDungeon.player.discardPile.size())
            .append(", exhaust ").append(AbstractDungeon.player.exhaustPile.size());
        if (AbstractDungeon.player.hasRelic(VelvetChoker.ID)) {
            out.append(" | Cards played ").append(AbstractDungeon.actionManager.cardsPlayedThisTurn.size()).append("/6");
        }
        out.append('\n');
    }

    public static List<AbstractMonster> aliveMonsters() {
        List<AbstractMonster> result = new ArrayList<>();
        if (AbstractDungeon.getMonsters() == null) return result;
        for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            if (!m.isDeadOrEscaped()) result.add(m);
        }
        return result;
    }

    public static String intent(AbstractMonster m) {
        if (AbstractDungeon.player.hasRelic(RunicDome.ID)) return "intent hidden";
        String name = m.intent.name();
        if (name.startsWith("ATTACK")) {
            StringBuilder s = new StringBuilder("Attack ").append(m.getIntentDmg());
            int hits = attackHits(m);
            if (hits > 1) s.append('x').append(hits);
            for (String part : name.substring("ATTACK".length()).split("_")) {
                if (!part.isEmpty()) s.append(" + ").append(titleCase(part));
            }
            return s.toString();
        }
        return titleCase(name.replace('_', ' '));
    }

    private static int attackHits(AbstractMonster m) {
        try {
            boolean multi = (boolean) ReflectionHacks.getPrivate(m, AbstractMonster.class, "isMultiDmg");
            return multi ? (int) ReflectionHacks.getPrivate(m, AbstractMonster.class, "intentMultiAmt") : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static int incomingDamage(AbstractMonster m) {
        if (m.halfDead || AbstractDungeon.player.hasRelic(RunicDome.ID) || !m.intent.name().startsWith("ATTACK")) return 0;
        return Math.max(0, m.getIntentDmg()) * attackHits(m);
    }

    public static String powers(AbstractCreature creature) {
        List<String> parts = new ArrayList<>();
        for (AbstractPower p : creature.powers) {
            parts.add(p.amount != 0 && p.amount != -1 ? p.name + " " + p.amount : p.name);
        }
        return String.join(", ", parts);
    }

    private static void renderPotions(StringBuilder out) {
        ArrayList<AbstractPotion> potions = AbstractDungeon.player.potions;
        boolean any = false;
        for (AbstractPotion p : potions) any |= !(p instanceof PotionSlot);
        if (!any) {
            out.append("Potions: none (").append(potions.size()).append(" slots)\n");
            return;
        }
        out.append("Potions:");
        for (int i = 0; i < potions.size(); i++) {
            AbstractPotion p = potions.get(i);
            out.append(' ').append(i + 1).append(". ");
            if (p instanceof PotionSlot) {
                out.append("empty");
            } else {
                out.append(p.name);
                if (p.targetRequired) out.append(" (target)");
            }
            if (i < potions.size() - 1) out.append(',');
        }
        out.append('\n');
    }

    // ---------------------------------------------------------------- cards

    /** "Strike [1] Deal 6 damage. (target)" */
    public static String cardLine(AbstractCard card, boolean live) {
        StringBuilder s = new StringBuilder(card.name).append(" [").append(cost(card)).append("] ")
            .append(cardText(card, live));
        if (card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY) {
            s.append(" (target)");
        }
        return s.toString();
    }

    /** "Armaments [1] Skill, Common: Gain 5 Block. ..." for cards outside the hand. */
    public static String cardOffer(AbstractCard card) {
        return card.name + " [" + cost(card) + "] " + titleCase(card.type.name()) + ", " +
            titleCase(card.rarity.name()) + ": " + cardText(card, false);
    }

    public static String cost(AbstractCard card) {
        if (card.cost == -1) return "X";
        if (card.cost == -2) return "-";
        if (card.freeToPlay()) return "0";
        return Integer.toString(AbstractDungeon.player != null && AbstractDungeon.player.hand.group.contains(card)
            ? card.costForTurn : card.cost);
    }

    /** Card description with !D!/!B!/!M! replaced by real numbers and markup removed. */
    public static String cardText(AbstractCard card, boolean live) {
        String text = card.rawDescription == null ? "" : card.rawDescription;
        int damage = live && card.damage > 0 ? card.damage : card.baseDamage;
        int block = live && card.block > 0 ? card.block : card.baseBlock;
        int magic = live && card.magicNumber > 0 ? card.magicNumber : card.baseMagicNumber;
        text = text.replace("!D!", Integer.toString(damage))
            .replace("!B!", Integer.toString(block))
            .replace("!M!", Integer.toString(magic));
        return cleanText(text);
    }

    public static String cleanText(String text) {
        if (text == null) return "";
        text = ChoiceScreenUtils.removeTextFormatting(text.replace(" NL ", " "));
        text = text.replaceAll("\\[[RGBWE]\\]", "[E]")
            .replaceAll("\\b[a-z_]+:(?=[A-Za-z])", "")
            .replaceAll("\\*(?=\\w)", "")
            .replaceAll("\\s+", " ")
            .trim();
        return text;
    }

    // ---------------------------------------------------------------- screens

    private static void renderScreen(StringBuilder out, ChoiceScreenUtils.ChoiceType type, Set<String> include) {
        switch (type) {
            case EVENT:
                renderEvent(out);
                break;
            case CHEST:
                renderSimpleChoices(out);
                break;
            case SHOP_ROOM:
                out.append("Merchant: choose 1 to browse, proceed to leave.\n");
                break;
            case REST:
                renderSimpleChoices(out);
                break;
            case CARD_REWARD:
                renderCardReward(out);
                break;
            case COMBAT_REWARD:
                renderCombatReward(out);
                break;
            case MAP:
                renderMap(out);
                renderMapChoices(out);
                break;
            case BOSS_REWARD:
                out.append("Boss relics (pick one):\n");
                ArrayList<AbstractRelic> relics = AbstractDungeon.bossRelicScreen.relics;
                for (int i = 0; i < relics.size(); i++) {
                    out.append(' ').append(i + 1).append(". ").append(relicLine(relics.get(i))).append('\n');
                }
                break;
            case SHOP_SCREEN:
                renderShop(out);
                break;
            case GRID:
                renderGrid(out);
                break;
            case HAND_SELECT:
                renderHandSelect(out);
                break;
            case GAME_OVER:
                out.append(isVictory() ? "Victory" : "Defeat").append(", score ").append(gameOverScore()).append('\n');
                break;
            default:
                break;
        }
    }

    private static void renderSimpleChoices(StringBuilder out) {
        if (!CommandExecutor.isChooseCommandAvailable()) return;
        ArrayList<String> choices = ChoiceScreenUtils.getCurrentChoiceList();
        out.append("Choices:");
        for (int i = 0; i < choices.size(); i++) out.append(' ').append(i + 1).append(". ").append(choices.get(i));
        out.append('\n');
    }

    private static void renderEvent(StringBuilder out) {
        AbstractEvent event = AbstractDungeon.getCurrRoom().event;
        if (event != null) {
            Object name = null;
            try {
                name = ReflectionHacks.getPrivateStatic(event.getClass(), "NAME");
            } catch (RuntimeException ignored) {
                // Some modded events do not define NAME.
            }
            out.append("Event: ").append(name != null ? name : event.getClass().getSimpleName()).append('\n');
        }
        ChoiceScreenUtils.EventDialogType dialog = ChoiceScreenUtils.getEventDialogType();
        if (dialog == ChoiceScreenUtils.EventDialogType.IMAGE || dialog == ChoiceScreenUtils.EventDialogType.ROOM) {
            String body = cleanText(UpdateBodyTextPatch.bodyText);
            if (!body.isEmpty()) out.append(body).append('\n');
            out.append("Choices:\n");
            int index = 1;
            for (LargeDialogOptionButton button : ChoiceScreenUtils.getEventButtons()) {
                String text = optionText(button.msg);
                if (button.isDisabled) out.append(" (unavailable) ").append(text).append('\n');
                else out.append(' ').append(index++).append(". ").append(text).append('\n');
            }
        } else {
            ArrayList<String> choices = ChoiceScreenUtils.getEventScreenChoices();
            if (!choices.isEmpty()) {
                out.append("Choices:");
                for (int i = 0; i < choices.size(); i++) out.append(' ').append(i + 1).append(". ").append(choices.get(i));
                out.append('\n');
            }
        }
    }

    private static String optionText(String msg) {
        String text = cleanText(msg);
        return text.replaceFirst("^\\[\\s*(.*?)\\s*\\]", "[$1]");
    }

    private static void renderCardReward(StringBuilder out) {
        out.append("Card reward (pick one");
        if (ChoiceScreenUtils.isCardRewardSkipAvailable()) out.append(" or skip");
        out.append("):\n");
        ArrayList<AbstractCard> cards = AbstractDungeon.cardRewardScreen.rewardGroup;
        for (int i = 0; i < cards.size(); i++) {
            out.append(' ').append(i + 1).append(". ").append(cardOffer(cards.get(i))).append('\n');
        }
        if (ChoiceScreenUtils.isBowlAvailable()) {
            out.append(' ').append(cards.size() + 1).append(". Singing Bowl: +2 Max HP instead\n");
        }
    }

    private static void renderCombatReward(StringBuilder out) {
        ArrayList<RewardItem> rewards = AbstractDungeon.combatRewardScreen.rewards;
        if (rewards.isEmpty()) {
            out.append("Rewards: none left\n");
            return;
        }
        out.append("Rewards:\n");
        boolean slotsFull = !hasEmptyPotionSlot();
        for (int i = 0; i < rewards.size(); i++) {
            RewardItem r = rewards.get(i);
            out.append(' ').append(i + 1).append(". ");
            switch (r.type) {
                case GOLD:
                case STOLEN_GOLD:
                    out.append(r.goldAmt + r.bonusGold).append(" gold");
                    break;
                case POTION:
                    out.append("Potion: ").append(r.potion.name).append(" - ").append(cleanText(r.potion.description));
                    if (slotsFull) out.append(" (potion slots full: discard one first)");
                    break;
                case RELIC:
                    out.append("Relic: ").append(relicLine(r.relic));
                    break;
                case CARD:
                    out.append("Card (opens a card choice)");
                    break;
                case SAPPHIRE_KEY:
                    out.append("Sapphire Key");
                    if (r.relicLink != null && r.relicLink.relic != null) {
                        out.append(" (instead of relic ").append(r.relicLink.relic.name).append(')');
                    }
                    break;
                case EMERALD_KEY:
                    out.append("Emerald Key");
                    break;
                default:
                    out.append(titleCase(r.type.name().replace('_', ' ')));
            }
            out.append('\n');
        }
    }

    private static void renderShop(StringBuilder out) {
        out.append("Shop (affordable):\n");
        int index = 1;
        for (Object item : ChoiceScreenUtils.getAvailableShopItems()) {
            out.append(' ').append(index++).append(". ");
            if (item instanceof String) {
                out.append("Remove a card - ").append(ShopScreen.actualPurgeCost).append('g');
            } else if (item instanceof AbstractCard) {
                AbstractCard card = (AbstractCard) item;
                out.append(cardOffer(card)).append(" - ").append(card.price).append('g');
            } else if (item instanceof StoreRelic) {
                StoreRelic relic = (StoreRelic) item;
                out.append("Relic ").append(relicLine(relic.relic)).append(" - ").append(relic.price).append('g');
            } else if (item instanceof StorePotion) {
                StorePotion potion = (StorePotion) item;
                out.append("Potion ").append(potion.potion.name).append(": ")
                    .append(cleanText(potion.potion.description)).append(" - ").append(potion.price).append('g');
            }
            out.append('\n');
        }
        List<String> tooExpensive = new ArrayList<>();
        int gold = AbstractDungeon.player.gold;
        for (AbstractCard card : ChoiceScreenUtils.getShopScreenCards()) {
            if (card.price > gold) tooExpensive.add(card.name + " " + card.price + "g");
        }
        for (StoreRelic relic : ChoiceScreenUtils.getShopScreenRelics()) {
            if (relic.price > gold) tooExpensive.add(relic.relic.name + " " + relic.price + "g");
        }
        for (StorePotion potion : ChoiceScreenUtils.getShopScreenPotions()) {
            if (potion.price > gold) tooExpensive.add(potion.potion.name + " " + potion.price + "g");
        }
        if (AbstractDungeon.shopScreen.purgeAvailable && ShopScreen.actualPurgeCost > gold) {
            tooExpensive.add("card removal " + ShopScreen.actualPurgeCost + "g");
        }
        if (!tooExpensive.isEmpty()) out.append("Too expensive: ").append(String.join(", ", tooExpensive)).append('\n');
    }

    private static void renderGrid(StringBuilder out) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        String tip = null;
        int numCards = 1;
        try {
            tip = (String) ReflectionHacks.getPrivate(screen, GridCardSelectScreen.class, "tipMsg");
            numCards = (int) ReflectionHacks.getPrivate(screen, GridCardSelectScreen.class, "numCards");
        } catch (RuntimeException ignored) {
            // Fall back to the generic prompt.
        }
        String purpose = screen.forUpgrade ? "upgrade" : screen.forTransform ? "transform" : screen.forPurge ? "remove" : "select";
        out.append("Card selection: ").append(tip != null && !tip.isEmpty() ? cleanText(tip) : purpose)
            .append(" (").append(screen.anyNumber ? "any number" : numCards + " card" + (numCards == 1 ? "" : "s")).append(")\n");
        if (screen.confirmScreenUp || screen.isJustForConfirming) {
            out.append("Selected: ").append(cardNames(screen.selectedCards)).append(" - confirm or cancel\n");
            return;
        }
        ArrayList<AbstractCard> cards = ChoiceScreenUtils.getGridScreenCards();
        if (cards.size() <= 6) {
            for (int i = 0; i < cards.size(); i++) {
                out.append(' ').append(i + 1).append(". ").append(cardOffer(cards.get(i))).append('\n');
            }
        } else {
            out.append("Cards:");
            for (int i = 0; i < cards.size(); i++) {
                out.append(i == 0 ? " " : ", ").append(i + 1).append(' ').append(cards.get(i).name);
            }
            out.append('\n');
        }
        if (!screen.selectedCards.isEmpty()) out.append("Selected: ").append(cardNames(screen.selectedCards)).append('\n');
    }

    private static void renderHandSelect(StringBuilder out) {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        out.append("Hand selection: ").append(screen.selectionReason == null ? "select" : cleanText(screen.selectionReason))
            .append(" (").append(screen.numCardsToSelect).append(" card").append(screen.numCardsToSelect == 1 ? "" : "s");
        if (screen.canPickZero) out.append(", may pick zero");
        out.append(") - use select with drop or keep\n");
        Set<AbstractCard> eligible = new HashSet<>(AbstractDungeon.player.hand.group);
        List<String> excluded = new ArrayList<>();
        for (AbstractCard card : HandSelectionTracker.getHandBeforeSelection()) {
            if (!eligible.contains(card) && !screen.selectedCards.contains(card)) excluded.add(card.name);
        }
        if (!excluded.isEmpty()) out.append("Not selectable: ").append(String.join(", ", excluded)).append('\n');
        if (!screen.selectedCards.isEmpty()) out.append("Selected: ").append(cardNames(screen.selectedCards.group)).append('\n');
    }

    private static String cardNames(List<AbstractCard> cards) {
        List<String> names = new ArrayList<>();
        for (AbstractCard card : cards) names.add(card.name);
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static boolean hasEmptyPotionSlot() {
        for (AbstractPotion potion : AbstractDungeon.player.potions) {
            if (potion instanceof PotionSlot) return true;
        }
        return false;
    }

    public static String relicLine(AbstractRelic relic) {
        StringBuilder s = new StringBuilder(relic.name);
        if (relic.counter >= 0) s.append(" (").append(relic.counter).append(')');
        return s.append(": ").append(cleanText(relic.description)).toString();
    }

    private static boolean isVictory() {
        if (AbstractDungeon.deathScreen != null) return AbstractDungeon.deathScreen.isVictory;
        return AbstractDungeon.victoryScreen != null;
    }

    private static int gameOverScore() {
        try {
            if (AbstractDungeon.deathScreen != null) {
                return (int) ReflectionHacks.getPrivate(AbstractDungeon.deathScreen, GameOverScreen.class, "score");
            }
            if (AbstractDungeon.victoryScreen != null) {
                return (int) ReflectionHacks.getPrivate(AbstractDungeon.victoryScreen, GameOverScreen.class, "score");
            }
        } catch (RuntimeException ignored) {
            // Score is cosmetic.
        }
        return 0;
    }

    // ---------------------------------------------------------------- optional sections

    private static void renderDeck(StringBuilder out) {
        out.append("Deck (").append(AbstractDungeon.player.masterDeck.size()).append("): ")
            .append(groupedNames(AbstractDungeon.player.masterDeck.group)).append('\n');
    }

    private static void renderRelics(StringBuilder out) {
        out.append("Relics:\n");
        for (AbstractRelic relic : AbstractDungeon.player.relics) {
            out.append(" - ").append(relicLine(relic)).append('\n');
        }
    }

    private static void renderPiles(StringBuilder out) {
        out.append("Draw pile (order hidden): ").append(groupedNames(sortedByName(AbstractDungeon.player.drawPile.group))).append('\n');
        out.append("Discard pile: ").append(groupedNames(AbstractDungeon.player.discardPile.group)).append('\n');
        out.append("Exhaust pile: ").append(groupedNames(AbstractDungeon.player.exhaustPile.group)).append('\n');
    }

    private static List<AbstractCard> sortedByName(List<AbstractCard> cards) {
        List<AbstractCard> sorted = new ArrayList<>(cards);
        sorted.sort((a, b) -> a.name.compareTo(b.name));
        return sorted;
    }

    /** "Strike x5, Defend x4, Bash" in first-seen order. */
    public static String groupedNames(List<AbstractCard> cards) {
        if (cards.isEmpty()) return "none";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AbstractCard card : cards) counts.merge(card.name, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey());
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- map

    /**
     * One line per row, listing only nodes still reachable from the current position:
     * "y3: 0M>0,1 *2?>3" means column 0 is a monster leading to columns 0 and 1 of the next row.
     */
    public static void renderMap(StringBuilder out) {
        ArrayList<ArrayList<MapRoomNode>> map = AbstractDungeon.map;
        if (map == null || map.isEmpty()) return;
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        int lastRow = map.size() - 1;

        Set<MapRoomNode> reachable = new HashSet<>();
        Deque<MapRoomNode> queue = new ArrayDeque<>();
        boolean started = AbstractDungeon.firstRoomChosen && current != null && current.y >= 0;
        if (started) {
            queue.add(current);
        } else {
            for (MapRoomNode node : map.get(0)) if (node.hasEdges()) queue.add(node);
        }
        while (!queue.isEmpty()) {
            MapRoomNode node = queue.poll();
            if (!reachable.add(node)) continue;
            for (MapEdge edge : node.getEdges()) {
                if (edge.dstY >= 0 && edge.dstY <= lastRow) queue.add(map.get(edge.dstY).get(edge.dstX));
            }
        }

        out.append("Map act ").append(AbstractDungeon.actNum);
        if (!AbstractDungeon.bossList.isEmpty()) out.append(", boss ").append(AbstractDungeon.bossList.get(0));
        out.append(". Row y: column+room>next columns; M monster, E elite, R rest, $ shop, T treasure, ? event, * you\n");
        int firstRow = started ? current.y : 0;
        for (int y = firstRow; y <= lastRow; y++) {
            StringBuilder row = new StringBuilder();
            for (MapRoomNode node : map.get(y)) {
                if (!reachable.contains(node)) continue;
                row.append(' ');
                if (node == current) row.append('*');
                row.append(node.x).append(node.getRoomSymbol(true));
                if (node.hasEmeraldKey) row.append("(key)");
                Set<Integer> next = new java.util.TreeSet<>();
                boolean boss = false;
                for (MapEdge edge : node.getEdges()) {
                    if (edge.dstY > lastRow) boss = true;
                    else next.add(edge.dstX);
                }
                if (boss) row.append(">boss");
                else if (!next.isEmpty()) row.append('>').append(joinInts(next));
            }
            if (row.length() > 0) out.append('y').append(y).append(':').append(row).append('\n');
        }
    }

    private static String joinInts(Set<Integer> values) {
        StringBuilder s = new StringBuilder();
        for (int v : values) {
            if (s.length() > 0) s.append(',');
            s.append(v);
        }
        return s.toString();
    }

    private static void renderMapChoices(StringBuilder out) {
        out.append("Next:");
        if (ChoiceScreenUtils.bossNodeAvailable()) {
            out.append(" 1. Boss\n");
            return;
        }
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        ArrayList<MapRoomNode> nodes = ChoiceScreenUtils.getMapScreenNodeChoices();
        for (int i = 0; i < nodes.size(); i++) {
            MapRoomNode node = nodes.get(i);
            out.append(' ').append(i + 1).append(". y").append(node.y).append(' ').append(node.x)
                .append(node.getRoomSymbol(true)).append(" (").append(roomName(node)).append(')');
            if (AbstractDungeon.firstRoomChosen && current != null && !current.isConnectedTo(node)) {
                out.append(" [winged boots]");
            }
        }
        out.append('\n');
    }

    private static String roomName(MapRoomNode node) {
        switch (node.getRoomSymbol(true)) {
            case "M": return "monster";
            case "E": return node.hasEmeraldKey ? "burning elite" : "elite";
            case "R": return "rest";
            case "$": return "shop";
            case "T": return "treasure";
            case "?": return "event";
            default: return node.getRoomSymbol(true);
        }
    }

    // ---------------------------------------------------------------- util

    public static String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        String lower = s.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
