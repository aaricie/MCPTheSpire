package mcpthespire;

import basemod.ReflectionHacks;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.screens.stats.AchievementItem;
import com.megacrit.cardcrawl.screens.stats.CharStat;
import com.megacrit.cardcrawl.screens.stats.StatsScreen;
import com.megacrit.cardcrawl.unlock.AbstractUnlock;
import com.megacrit.cardcrawl.unlock.UnlockTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Renders the active profile's statistics and unlock progress as compact text. */
public final class ProfileStateConverter {
    private ProfileStateConverter() {
    }

    public static String getProfileProgress(boolean includeCards, boolean includeAchievements) {
        StringBuilder out = new StringBuilder();
        out.append("Profile ").append(CardCrawlGame.playerName).append(" (slot ").append(CardCrawlGame.saveSlot).append(')');
        if (CardCrawlGame.characterManager == null || UnlockTracker.unlockProgress == null || CardLibrary.cards == null) {
            return out.append(": progress not loaded yet").toString();
        }
        out.append(", ").append(Math.round(UnlockTracker.getCompletionPercentage())).append("% complete\n");
        if (UnlockTracker.lockedCharacters != null && !UnlockTracker.lockedCharacters.isEmpty()) {
            out.append("Locked characters: ").append(String.join(", ", UnlockTracker.lockedCharacters)).append('\n');
        }

        int wins = 0, deaths = 0;
        long playtime = 0;
        StringBuilder characters = new StringBuilder();
        for (AbstractPlayer player : CardCrawlGame.characterManager.getAllCharacters()) {
            CharStat stat = player.getCharStat();
            if (stat != null) {
                wins += stat.getVictoryCount();
                deaths += stat.getDeathCount();
                playtime += stat.playTime;
            }
            renderCharacter(characters, player, stat, includeCards);
        }
        out.append("Overall: ").append(wins).append(" wins, ").append(deaths).append(" deaths, ")
            .append(hours(playtime)).append(" played\n").append(characters);

        List<String> lockedRelics = new ArrayList<>();
        if (UnlockTracker.lockedRelics != null) {
            for (String id : UnlockTracker.lockedRelics) {
                AbstractRelic relic = RelicLibrary.getRelic(id);
                lockedRelics.add(relic == null ? id : relic.name);
            }
        }
        out.append("Locked relics (").append(lockedRelics.size()).append("): ")
            .append(lockedRelics.isEmpty() ? "none" : String.join(", ", lockedRelics)).append('\n');
        if (includeAchievements) renderAchievements(out);
        return out.toString().trim();
    }

    private static void renderCharacter(StringBuilder out, AbstractPlayer player, CharStat stat, boolean includeCards) {
        AbstractPlayer.PlayerClass playerClass = player.chosenClass;
        out.append(playerClass.name()).append(": ");
        if (stat == null) {
            out.append("no stats");
        } else {
            out.append(stat.getVictoryCount()).append("W/").append(stat.getDeathCount()).append("L");
            int ascension = maxAscension(player);
            if (ascension > 0) out.append(", ascension unlocked ").append(ascension);
            out.append(", furthest floor ").append(stat.furthestAscent)
                .append(", best score ").append(stat.highestScore)
                .append(", streak ").append(stat.winStreak).append(" (best ").append(stat.bestWinStreak).append(')')
                .append(", ").append(hours(stat.playTime));
            if (stat.fastestTime > 0) out.append(", fastest win ").append(minutes(stat.fastestTime));
        }
        out.append('\n');

        try {
            int level = UnlockTracker.getUnlockLevel(playerClass);
            int progress = UnlockTracker.getCurrentProgress(playerClass);
            int cost = UnlockTracker.getCurrentScoreCost(playerClass);
            List<String> bundle = new ArrayList<>();
            List<AbstractUnlock> unlocks = UnlockTracker.getUnlockBundle(playerClass, level);
            if (unlocks != null) {
                for (AbstractUnlock unlock : unlocks) bundle.add(unlock.title + " (" + unlock.type.name().toLowerCase() + ")");
            }
            out.append("  Unlock level ").append(level);
            if (bundle.isEmpty()) {
                out.append(", all unlocks earned");
            } else {
                out.append(", next unlock at ").append(progress).append('/').append(cost).append(" pts: ")
                    .append(String.join(", ", bundle));
            }
            out.append('\n');
        } catch (RuntimeException e) {
            out.append("  Unlock progress unavailable\n");
        }

        ArrayList<AbstractCard> cards = new ArrayList<>();
        for (AbstractCard card : CardLibrary.cards.values()) {
            if (card.color == player.getCardColor() && card.rarity != AbstractCard.CardRarity.SPECIAL) cards.add(card);
        }
        cards.sort(Comparator.comparing((AbstractCard card) -> card.name).thenComparing(card -> card.cardID));
        List<String> locked = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        int seen = 0;
        for (AbstractCard card : cards) {
            boolean isSeen = UnlockTracker.isCardSeen(card.cardID);
            if (isSeen) seen++;
            String label = card.name + (isSeen ? "" : " (unseen)");
            if (UnlockTracker.isCardLocked(card.cardID)) locked.add(label);
            else unlocked.add(label);
        }
        out.append("  Cards: ").append(cards.size() - locked.size()).append('/').append(cards.size())
            .append(" unlocked, ").append(seen).append(" seen");
        if (!locked.isEmpty()) out.append(". Locked: ").append(String.join(", ", locked));
        out.append('\n');
        if (includeCards) out.append("  Unlocked cards: ").append(String.join(", ", unlocked)).append('\n');
    }

    private static int maxAscension(AbstractPlayer player) {
        try {
            return player.getPrefs().getInteger(CharStat.ASCENSION_LEVEL, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void renderAchievements(StringBuilder out) {
        if (StatsScreen.achievements == null || StatsScreen.achievements.items == null) return;
        out.append("Achievements:\n");
        for (AchievementItem item : StatsScreen.achievements.items) {
            String name = item.key;
            String desc = "";
            try {
                name = String.valueOf(ReflectionHacks.getPrivate(item, AchievementItem.class, "title"));
                desc = String.valueOf(ReflectionHacks.getPrivate(item, AchievementItem.class, "desc"));
            } catch (RuntimeException ignored) {
                // The key and unlock state remain useful without localized text.
            }
            out.append(UnlockTracker.isAchievementUnlocked(item.key) ? " [x] " : " [ ] ").append(name);
            if (!desc.isEmpty()) out.append(": ").append(desc);
            out.append('\n');
        }
    }

    private static String hours(long seconds) {
        return String.format("%.1fh", seconds / 3600.0);
    }

    private static String minutes(long seconds) {
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }
}
