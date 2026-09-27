package mcpthespire;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.util.ArrayList;
import java.util.List;

public final class HandSelectionTracker {
    private static final ArrayList<AbstractCard> handBeforeSelection = new ArrayList<>();

    private HandSelectionTracker() {
    }

    public static void capture() {
        handBeforeSelection.clear();
        if (AbstractDungeon.player != null) {
            handBeforeSelection.addAll(AbstractDungeon.player.hand.group);
        }
    }

    public static List<AbstractCard> getHandBeforeSelection() {
        return new ArrayList<>(handBeforeSelection);
    }
}
