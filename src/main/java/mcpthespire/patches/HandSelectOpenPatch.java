package mcpthespire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import mcpthespire.HandSelectionTracker;

@SpirePatch(
    clz = HandCardSelectScreen.class,
    method = "open",
    paramtypez = {String.class, int.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class}
)
public class HandSelectOpenPatch {
    public static void Prefix(HandCardSelectScreen _instance) {
        HandSelectionTracker.capture();
    }
}
