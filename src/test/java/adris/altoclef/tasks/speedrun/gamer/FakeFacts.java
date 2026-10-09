package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

// a snapshot you set by hand. counts need Bootstrap (Items), everything else does not
public class FakeFacts implements GamerFacts {
    public final Map<Item, Integer> items = new HashMap<>();
    public final java.util.Set<Item> worn = new java.util.HashSet<>();
    // picks in the bag that are too worn to count (see KitPlanner.wornOut), held but not in items
    public final Map<Item, Integer> spent = new HashMap<>();
    public Dimension dimension = Dimension.OVERWORLD;
    public int armorPoints;
    public int foodUnits;
    public int junkFoodUnits;
    public int buildBlocks;
    public int x;
    public int y = 64;
    public int z;
    public long gameTime;
    public boolean credits;
    // one of our tables standing next to us (it is out of the bag, the planner still counts it)
    public boolean tablePlaced;
    // and a furnace of ours on the ground, not in the bag
    public boolean furnacePlaced;
    // and a smoker of ours
    public boolean smokerPlaced;
    // the cook task gave up a while ago
    public boolean cookSuspended;
    // the station a running cook committed to
    public String cookStation;
    public int fingerprint;
    public boolean earlyIronPick = true;
    // the first raw iron is in a furnace and the job is not recorded yet
    public boolean earlyLoad;
    // the jobs of this dimension, set by hand
    public final java.util.List<RunState.FurnaceJob> jobs = new java.util.ArrayList<>();
    // idle furnaces of ours within the walk back, and the split smelt that is loading (null = none)
    public int idleFurnaces;
    public adris.altoclef.tasks.container.SmeltSplit.Batch smeltBatch;

    @Override
    public int idleFurnaces() {
        return idleFurnaces;
    }

    @Override
    public adris.altoclef.tasks.container.SmeltSplit.Batch smeltBatch() {
        return smeltBatch;
    }

    // an iron smelt that finishes `seconds` from now with `count` ingots in it
    public FakeFacts cooking(String output, int count, double seconds) {
        jobs.add(new RunState.FurnaceJob(new RunState.Pos(0, 64, 0), dimension.name(), "furnace", "raw_iron", count, output,
                gameTime, gameTime + Math.round(seconds * 20)));
        return this;
    }

    // meat in a smoker, `count` items worth `unitsEach` nutrition apiece once done
    public FakeFacts cookingFood(String output, int count, int unitsEach, double seconds) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(5, 64, 0), dimension.name(), "smoker", "mutton", count,
                output, gameTime, gameTime + Math.round(seconds * 20));
        job.unitsEach = unitsEach;
        jobs.add(job);
        return this;
    }

    @Override
    public java.util.List<RunState.FurnaceJob> furnaceJobs() {
        return jobs;
    }

    // one game second
    public FakeFacts seconds(double s) {
        gameTime += (long) Math.round(s * 20);
        return this;
    }

    public FakeFacts give(Item item, int n) {
        items.merge(item, n, Integer::sum);
        fingerprint++;
        return this;
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return items.getOrDefault(item, 0);
    }

    @Override
    public int spent(Item item) {
        return spent.getOrDefault(item, 0);
    }

    @Override
    public boolean armorEquipped(Item item) {
        return worn.contains(item);
    }

    @Override
    public int armorPoints() {
        return armorPoints;
    }

    @Override
    public int foodUnits() {
        return foodUnits;
    }

    @Override
    public int junkFoodUnits() {
        return junkFoodUnits;
    }

    @Override
    public int buildBlocks() {
        return buildBlocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public boolean tablePlacedNearby() {
        return tablePlaced;
    }

    @Override
    public boolean smokerPlacedNearby() {
        return smokerPlaced;
    }

    @Override
    public boolean cookSuspended() {
        return cookSuspended;
    }

    @Override
    public String cookStation() {
        return cookStation;
    }

    // items a furnace may not burn (altoSupportedFuels without the wood)
    public final java.util.Set<Item> unburnable = new java.util.HashSet<>();

    @Override
    public boolean burnable(Item item) {
        return !unburnable.contains(item);
    }

    // the real default: coal and charcoal only, no wood
    public FakeFacts coalOnly() {
        for (Item[] group : new Item[][]{adris.altoclef.util.helpers.ItemHelper.LOG, adris.altoclef.util.helpers.ItemHelper.PLANKS}) {
            unburnable.addAll(java.util.Arrays.asList(group));
        }
        return this;
    }

    @Override
    public boolean furnacePlacedNearby() {
        return furnacePlaced;
    }

    @Override
    public boolean earlyIronPick() {
        return earlyIronPick;
    }

    @Override
    public boolean earlyLoadInFlight() {
        return earlyLoad;
    }

    @Override
    public boolean creditsShown() {
        return credits;
    }

    @Override
    public int inventoryFingerprint() {
        return fingerprint;
    }
}
