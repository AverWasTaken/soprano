package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

// the gather chops the whole wood budget up front (KitPlanner.woodNeed), and a furnace with logs in the bag burned the batch
// it just cut. this works out how many logs and planks are surplus to that budget and tells FuelPolicy to keep the rest. the
// budget itself is KitPlanner's: a log is surplus while woodNeed still says 0 with it gone, so the margin it builds in stays
// ours too. pure on facts, FuelPolicy is the only side effect
public final class WoodReserve {
    private WoodReserve() {
    }

    public record Keep(int logs, int planks) {
    }

    // called every engine tick, a handful of woodNeed evaluations
    public static void update(GamerFacts f, OverworldConfig cfg, int endBeds) {
        if (f.dimension() != Dimension.OVERWORLD) {
            // the run lets wood burn (GamerTask.withWoodFuel) for the overworld cook. nothing down there budgets it, so a clear
            // reserve would let one smelt eat every log in the bag: keep all of it, coal still burns
            FuelPolicy.set(f.count(ItemHelper.LOG), f.count(ItemHelper.PLANKS), true);
            return;
        }
        Keep keep = keep(f, cfg, endBeds);
        FuelPolicy.set(keep.logs(), keep.planks(), true);
    }

    public static Keep keep(GamerFacts f, OverworldConfig cfg, int endBeds) {
        int logs = f.count(ItemHelper.LOG);
        int planks = f.count(ItemHelper.PLANKS);
        if (KitPlanner.woodNeed(f, cfg, endBeds) > 0) {
            // still short of the budget: every piece of wood is spoken for
            return new Keep(logs, planks);
        }
        // woodNeed only falls as we hold more, so the biggest k with "still 0 after losing k" is a binary search
        int spareLogs = most(logs, k -> KitPlanner.woodNeed(without(f, ItemHelper.LOG, k), cfg, endBeds) == 0);
        GamerFacts afterLogs = without(f, ItemHelper.LOG, spareLogs);
        int sparePlanks = most(planks, k -> KitPlanner.woodNeed(without(afterLogs, ItemHelper.PLANKS, k), cfg, endBeds) == 0);
        return new Keep(logs - spareLogs, planks - sparePlanks);
    }

    // largest k in [0, max] the predicate holds for, given it holds for 0 and only gets harder as k grows
    static int most(int max, IntPredicate ok) {
        int lo = 0;
        int hi = max;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (ok.test(mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // the same facts with k fewer of a wood group, taken from the species in list order
    static GamerFacts without(GamerFacts base, Item[] group, int k) {
        Map<Item, Integer> reduced = new HashMap<>();
        int left = k;
        for (Item item : group) {
            int have = base.count(item);
            int take = Math.min(have, left);
            left -= take;
            reduced.put(item, have - take);
        }
        return new Reduced(base, reduced);
    }

    private record Reduced(GamerFacts base, Map<Item, Integer> counts) implements GamerFacts {
        @Override
        public Dimension dimension() {
            return base.dimension();
        }

        @Override
        public int count(Item item) {
            Integer c = counts.get(item);
            return c != null ? c : base.count(item);
        }

        @Override
        public int spent(Item item) {
            return base.spent(item);
        }

        @Override
        public boolean armorEquipped(Item item) {
            return base.armorEquipped(item);
        }

        @Override
        public int armorPoints() {
            return base.armorPoints();
        }

        @Override
        public int foodUnits() {
            return base.foodUnits();
        }

        @Override
        public int junkFoodUnits() {
            return base.junkFoodUnits();
        }

        @Override
        public int buildBlocks() {
            return base.buildBlocks();
        }

        @Override
        public int x() {
            return base.x();
        }

        @Override
        public int y() {
            return base.y();
        }

        @Override
        public int z() {
            return base.z();
        }

        @Override
        public long gameTime() {
            return base.gameTime();
        }

        @Override
        public List<RunState.FurnaceJob> furnaceJobs() {
            return base.furnaceJobs();
        }

        @Override
        public int pendingOutput(Item item) {
            return base.pendingOutput(item);
        }

        @Override
        public boolean tablePlacedNearby() {
            return base.tablePlacedNearby();
        }

        @Override
        public boolean furnacePlacedNearby() {
            return base.furnacePlacedNearby();
        }

        @Override
        public boolean smokerPlacedNearby() {
            return base.smokerPlacedNearby();
        }

        @Override
        public boolean creditsShown() {
            return base.creditsShown();
        }

        @Override
        public int inventoryFingerprint() {
            return base.inventoryFingerprint();
        }
    }
}
