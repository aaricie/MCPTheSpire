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
import java.util.HashMap;
import java.util.List;

public final class ProfileStateConverter {
    private ProfileStateConverter() {
    }

    public static HashMap<String, Object> getProfileProgress(boolean includeCards, boolean includeAchievements) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("profile_slot", CardCrawlGame.saveSlot);
        result.put("profile_name", CardCrawlGame.playerName);
        result.put("in_game", CommandExecutor.isInDungeon());
        if (CardCrawlGame.characterManager == null || UnlockTracker.unlockProgress == null ||
            CardLibrary.cards == null) {
            result.put("available", false);
            return result;
        }

        result.put("available", true);
        result.put("completion_percentage", UnlockTracker.getCompletionPercentage());
        if (UnlockTracker.lockedCharacters != null) {
            result.put("locked_characters", new ArrayList<>(UnlockTracker.lockedCharacters));
        }
        ArrayList<Object> characters = new ArrayList<>();
        int totalWins = 0;
        int totalLosses = 0;
        long totalPlaytime = 0;
        for (AbstractPlayer player : CardCrawlGame.characterManager.getAllCharacters()) {
            HashMap<String, Object> character = getCharacterProgress(player, includeCards);
            characters.add(character);
            HashMap<String, Object> stats = (HashMap<String, Object>) character.get("stats");
            totalWins += (Integer) stats.get("victories");
            totalLosses += (Integer) stats.get("deaths");
            totalPlaytime += (Long) stats.get("playtime_seconds");
        }
        result.put("characters", characters);

        HashMap<String, Object> overall = new HashMap<>();
        overall.put("victories", totalWins);
        overall.put("deaths", totalLosses);
        overall.put("playtime_seconds", totalPlaytime);
        result.put("overall", overall);

        ArrayList<Object> lockedRelics = new ArrayList<>();
        if (UnlockTracker.lockedRelics != null) {
            for (String id : UnlockTracker.lockedRelics) {
                HashMap<String, Object> relic = new HashMap<>();
                relic.put("id", id);
                AbstractRelic definition = RelicLibrary.getRelic(id);
                relic.put("name", definition == null ? id : definition.name);
                lockedRelics.add(relic);
            }
        }
        result.put("locked_relics", lockedRelics);
        result.put("locked_relic_count", lockedRelics.size());
        if (includeAchievements) result.put("achievements", getAchievements());
        return result;
    }

    private static HashMap<String, Object> getCharacterProgress(AbstractPlayer player, boolean includeCards) {
        HashMap<String, Object> result = new HashMap<>();
        AbstractPlayer.PlayerClass playerClass = player.chosenClass;
        result.put("character", playerClass.name());
        result.put("name", player.getLocalizedCharacterName());

        CharStat stat = player.getCharStat();
        HashMap<String, Object> stats = new HashMap<>();
        if (stat == null) {
            stats.put("victories", 0);
            stats.put("deaths", 0);
            stats.put("playtime_seconds", 0L);
        } else {
            stats.put("victories", stat.getVictoryCount());
            stats.put("deaths", stat.getDeathCount());
            stats.put("highest_score", stat.highestScore);
            stats.put("highest_daily_score", stat.highestDaily);
            stats.put("highest_ascension", stat.furthestAscent);
            stats.put("current_win_streak", stat.winStreak);
            stats.put("best_win_streak", stat.bestWinStreak);
            stats.put("enemies_killed", stat.enemyKilled);
            stats.put("bosses_killed", stat.bossKilled);
            stats.put("playtime_seconds", stat.playTime);
            stats.put("fastest_victory_seconds", stat.fastestTime);
            addPrivateStat(stats, stat, "totalFloorsClimbed", "total_floors_climbed");
            addPrivateStat(stats, stat, "cardsUnlocked", "cards_unlocked_on_stats_page");
            addPrivateStat(stats, stat, "relicsUnlocked", "relics_unlocked_on_stats_page");
            addPrivateStat(stats, stat, "cardsDiscovered", "cards_discovered_on_stats_page");
            addPrivateStat(stats, stat, "cardsToDiscover", "cards_to_discover_on_stats_page");
        }
        result.put("stats", stats);

        try {
            int level = UnlockTracker.getUnlockLevel(playerClass);
            int progress = UnlockTracker.getCurrentProgress(playerClass);
            int cost = UnlockTracker.getCurrentScoreCost(playerClass);
            result.put("unlock_level", level);
            result.put("unlock_progress", progress);
            result.put("next_unlock_cost", cost);
            ArrayList<Object> nextBundle = new ArrayList<>();
            List<AbstractUnlock> bundle = UnlockTracker.getUnlockBundle(playerClass, level);
            if (bundle != null) {
                for (AbstractUnlock unlock : bundle) {
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("type", unlock.type.name());
                    entry.put("id", unlock.key);
                    entry.put("name", unlock.title);
                    nextBundle.add(entry);
                }
            }
            result.put("next_unlocks", nextBundle);
            result.put("unlock_progress_complete", nextBundle.isEmpty());
            if (!nextBundle.isEmpty()) {
                result.put("points_to_next_unlock", Math.max(0, cost - progress));
            }
        } catch (RuntimeException e) {
            result.put("unlock_progress_available", false);
        }

        ArrayList<AbstractCard> cards = new ArrayList<>();
        for (AbstractCard card : CardLibrary.cards.values()) {
            if (card.color == player.getCardColor() && card.rarity != AbstractCard.CardRarity.SPECIAL) {
                cards.add(card);
            }
        }
        cards.sort(Comparator.comparing((AbstractCard card) -> card.name)
            .thenComparing(card -> card.cardID));
        ArrayList<Object> locked = new ArrayList<>();
        ArrayList<Object> all = new ArrayList<>();
        int seenCount = 0;
        for (AbstractCard card : cards) {
            boolean isLocked = UnlockTracker.isCardLocked(card.cardID);
            boolean isSeen = UnlockTracker.isCardSeen(card.cardID);
            if (isSeen) seenCount++;
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("id", card.cardID);
            entry.put("name", card.name);
            entry.put("rarity", card.rarity.name());
            entry.put("unlocked", !isLocked);
            entry.put("seen", isSeen);
            if (isLocked) locked.add(entry);
            if (includeCards) all.add(entry);
        }
        result.put("card_catalog_count", cards.size());
        result.put("cards_available", cards.size() - locked.size());
        result.put("cards_locked", locked);
        result.put("cards_seen", seenCount);
        if (includeCards) result.put("cards", all);
        return result;
    }

    private static void addPrivateStat(HashMap<String, Object> stats, CharStat stat, String field, String key) {
        try {
            Object value = ReflectionHacks.getPrivate(stat, CharStat.class, field);
            if (value != null) stats.put(key, value);
        } catch (RuntimeException ignored) {
            // Older or modded game versions may not carry every Statistics field.
        }
    }

    private static ArrayList<Object> getAchievements() {
        ArrayList<Object> achievements = new ArrayList<>();
        if (StatsScreen.achievements == null || StatsScreen.achievements.items == null) return achievements;
        for (AchievementItem item : StatsScreen.achievements.items) {
            HashMap<String, Object> achievement = new HashMap<>();
            achievement.put("id", item.key);
            achievement.put("unlocked", UnlockTracker.isAchievementUnlocked(item.key));
            try {
                achievement.put("name", ReflectionHacks.getPrivate(item, AchievementItem.class, "title"));
                achievement.put("description", ReflectionHacks.getPrivate(item, AchievementItem.class, "desc"));
            } catch (RuntimeException ignored) {
                // The ID and unlock state remain useful without localized text.
            }
            achievements.add(achievement);
        }
        return achievements;
    }
}
