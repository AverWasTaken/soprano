package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

// read only snapshot of what the player has right now. the pure parts of the gamer (isDone, regressTo, kit planning)
// only ever see this, so tests can fake it with a map and never touch a game
public interface GamerFacts {
    Dimension dimension();

    // inventory + hotbar + equipped armor + offhand (so an equipped shield or chestplate counts as owned)
    int count(Item item);

    default int count(Item... items) {
        int total = 0;
        for (Item item : items) {
            total += count(item);
        }
        return total;
    }

    default boolean has(Item item) {
        return count(item) > 0;
    }

    boolean armorEquipped(Item item);

    // sum of armor points of what is worn
    int armorPoints();

    // nutrition we could eat right now (cooked and raw edible stuff, no poison)
    int foodUnits();

    // throwaway blocks we can pillar/bridge with
    int buildBlocks();

    int x();

    int y();

    int z();

    long gameTime();

    // true once the win screen has been shown (or the run saw the dragon die and came home)
    boolean creditsShown();
}
