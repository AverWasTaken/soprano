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
    public int fingerprint;
    public boolean earlyIronPick = true;
    // the jobs of this dimension, set by hand
    public final java.util.List<RunState.FurnaceJob> jobs = new java.util.ArrayList<>();

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
    public boolean earlyIronPick() {
        return earlyIronPick;
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
