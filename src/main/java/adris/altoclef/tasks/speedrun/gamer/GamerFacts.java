package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.List;

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

    // how many of this we carry but left out of count() because they are about to break (see KitPlanner.wornOut). the
    // planner needs them to size its targets, since the catalogue counts everything in the bag
    default int spent(Item item) {
        return 0;
    }

    boolean armorEquipped(Item item);

    // sum of armor points of what is worn
    int armorPoints();

    // nutrition we could eat right now (cooked and raw edible stuff, no poison)
    int foodUnits();

    // nutrition of the food foodUnits() leaves out (rotten flesh, gapples, spider eyes...). CollectFoodTask counts all of it,
    // so a task that asks it for N units has to add this or it thinks it is done while foodUnits() still says short
    default int junkFoodUnits() {
        return 0;
    }

    // throwaway blocks we can pillar/bridge with
    int buildBlocks();

    int x();

    int y();

    int z();

    long gameTime();

    // furnaces cooking something for us in THIS dimension (RunState.furnaceJobs, filtered). a job in the nether is not
    // something we can walk to from the overworld
    default List<RunState.FurnaceJob> furnaceJobs() {
        return List.of();
    }

    // how many of this item the jobs above will have produced once they are done. counts as held for the planner,
    // the item is not in the inventory yet
    default int pendingOutput(Item item) {
        return FurnaceJobs.pending(furnaceJobs(), BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    // a crafting table of ours is standing close enough to walk back to (and is still a table). it is on its way back to the
    // bag, so for planning it is a held table: the moment it leaves the bag the kit used to ask for table planks, the head need
    // flipped to log, and the pickup rules read that as the run moving on from the table it had just put down
    default boolean tablePlacedNearby() {
        return false;
    }

    // nutrition cooking in a smoker or furnace for us right now. foodUnits() stays what is in the bag, the planner counts the
    // two together so a loaded smoker is not "no food", and the phases hold their end until the jobs are collected
    default int pendingFoodUnits() {
        return FurnaceJobs.pendingUnits(furnaceJobs());
    }

    // true once the win screen has been shown (or the run saw the dragon die and came home)
    boolean creditsShown();

    // changes whenever what we carry changes, for the watchdog's "did anything happen" (0 = this snapshot cannot tell)
    default int inventoryFingerprint() {
        return 0;
    }
}
