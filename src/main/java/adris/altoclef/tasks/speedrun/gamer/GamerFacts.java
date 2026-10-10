package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.SmeltSplit;
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

    // diagnostics only: the pickaxes the last scan saw and the open screen, as one greppable tail (PickDiag.scan). the fakes
    // have no inventory to scan
    default String pickScan() {
        return "no scan";
    }

    boolean armorEquipped(Item item);

    // sum of armor points of what is worn
    int armorPoints();

    // nutrition we could eat right now (cooked and raw edible stuff, no poison, raw meat at its cooked value). the building block,
    // not the answer: FoodPlan makes the one "how much food do we hold" out of it, and the gates read that
    int foodUnits();

    // nutrition of the food foodUnits() leaves out (rotten flesh, gapples, spider eyes...). CollectFoodTask counts all of it,
    // so a task that asks it for N units has to add this or it thinks it is done while foodUnits() still says short
    default int junkFoodUnits() {
        return 0;
    }

    // log numbers only (FoodPlan.line): the meat in the open furnace or smoker screen that foodUnits() counts, and the
    // meat in it that was left out because it was already there when the food task opened the screen (FoodPlan.leftover)
    default int stationFoodUnits() {
        return 0;
    }

    default int stationFoodSkipped() {
        return 0;
    }

    // close to the open sky in the overworld (SmeltSurface.shallow), where a food trip is a walk and not a climb out of a mine.
    // false in a test that does not say, so a stub bag never starts the surface top-up behind a test's back
    default boolean nearSurface() {
        return false;
    }

    // no empty slot and no junk to throw for one (StorageHelper.getJunkSlot): more food would cost something we carry on purpose
    default boolean bagFull() {
        return false;
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

    // a crafting table of ours is standing within WorkbenchRules.NEAR of us (a straight line, height counts) and is still a table,
    // or further out where the craft would walk back to it (Workbenches.plannerWalksBack, StationChoice.oursReach). it is on its
    // way back to the bag too, so for planning it is a held table: the moment it leaves the bag the kit used to ask for table planks
    // and the head need flipped to log
    default boolean tablePlacedNearby() {
        return false;
    }

    // same for a furnace of ours (RunState.placedFurnaces), within NEAR, or further out wherever the smelt would walk back to it
    // (Workbenches.plannerWalksBack: WALK_BACK with the cobble in the bag, the forget line without). the cobble floor reads it: the 8 cobble it cost are not
    // owed to a furnace that is already on the ground
    default boolean furnacePlacedNearby() {
        return false;
    }

    // the split smelt still loading (SmeltSplit.active), null when there is none
    default SmeltSplit.Batch smeltBatch() {
        return null;
    }

    // and a smoker of ours (RunState.placedSmokers), within NEAR or walked back to like the furnace. the cook need reads it: a smoker standing is the cheapest place to
    // put meat, and it beats a furnace (WorkbenchRules.cookInSmoker)
    default boolean smokerPlacedNearby() {
        return false;
    }

    // the cook task gave up a while ago (no fuel to be found, no spot for a smoker...), see FurnacePlan.cookSuspended. the planner stops asking
    // for the cook until it is over, or a stuck smoker would hold the phase for ever
    default boolean cookSuspended() {
        return false;
    }

    // the station a running cook picked ("smoker", "furnace"), null when none is running. CookGate keeps it while the craft and
    // the placing eat the very things that picked it
    default String cookStation() {
        return null;
    }

    // may a furnace burn this item at all (altoSupportedFuels). the cook gate counts fuel with this, so it never says yes to a
    // stack of logs the smelt task is not allowed to touch (the smoker that got meat and then refused the logs, 13:11)
    default boolean burnable(Item item) {
        return true;
    }

    // nutrition cooking in a smoker or furnace for us right now. foodUnits() stays what is in the bag, the planner counts the
    // two together so a loaded smoker is not "no food", and the phases hold their end until the jobs are collected
    default int pendingFoodUnits() {
        return FurnaceJobs.pendingUnits(furnaceJobs());
    }

    // altoEarlyIronPick: make the iron pickaxe from the first three raw iron (EarlyIronPick). a setting, so the real facts read it
    default boolean earlyIronPick() {
        return true;
    }

    // the first three raw iron started into a furnace and the job is not recorded yet (FurnacePlan.earlyLoadInFlight). the bag no
    // longer shows the ore by then, this is what keeps the early need alive until it is lit
    default boolean earlyLoadInFlight() {
        return false;
    }

    // true once the win screen has been shown (or the run saw the dragon die and came home)
    boolean creditsShown();

    // changes whenever what we carry changes, for the watchdog's "did anything happen" (0 = this snapshot cannot tell)
    default int inventoryFingerprint() {
        return 0;
    }
}
