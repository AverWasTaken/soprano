package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;

import java.util.ArrayList;
import java.util.List;

// a food need for the phases that have none of their own. FoodChain only eats, its own hunting reads two settings (the minimum
// and the amount to collect) that the gamer leaves at 0, so a bot walking to the stronghold on a bad bag just ran down to nothing.
// same floor and same top-up the IRON phase uses (the floor starts it, the overworld minimum ends it, FoodPlan.floorNext),
// overworld only: there is nothing to hunt in the nether or the End. the instance only holds the latch and the runner
public final class FoodFloor {
    // the blocks a nether trip tops the bag up to. the portal gate wants the full entry stack (netherEntryBlocks), a trip home is
    // a short hop for a stack that the nether digs back for itself, so it is not worth a 128 block mining session
    public static final int TRIP_BLOCKS = 64;

    private final KitRunner runner = new KitRunner();
    private boolean active;

    public void reset() {
        runner.reset();
        active = false;
    }

    // the task that gets food while the floor is breached, null when the phase should carry on with its own work
    public Task tick(GamerContext ctx) {
        FoodPlan food = ctx.food();
        boolean was = active;
        active = ctx.facts().dimension() == Dimension.OVERWORLD && food.floorNext(active);
        if (!active) {
            if (was) {
                runner.reset();
            }
            return null;
        }
        return runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, food.overworldMinimum())));
    }

    // the same hunt for a nether trip: the bag is nowhere near the floor by then, so it runs from the first overworld tick
    // to the minimum and not only under the floor. one runner over whichever needs are short: food to the minimum, and on a blocks
    // trip the TRIP_BLOCKS stack (emitted straight, no BuildBlocks band; KitRunner still notes a refill, which the next phase entry wipes). null = none short
    public Task tickTrip(GamerContext ctx, boolean wantBlocks) {
        boolean overworld = ctx.facts().dimension() == Dimension.OVERWORLD;
        FoodPlan food = overworld ? ctx.food() : null;
        List<KitNeed> needs = new ArrayList<>();
        if (food != null && food.shortOfMinimum()) {
            needs.add(new KitNeed(KitNeed.FOOD, food.overworldMinimum()));
        }
        if (overworld && wantBlocks && blocksShort(ctx)) {
            needs.add(new KitNeed(KitNeed.BUILD_BLOCKS, TRIP_BLOCKS));
        }
        boolean was = active;
        active = !needs.isEmpty();
        if (!active) {
            if (was) {
                runner.reset();
            }
            return null;
        }
        return runner.run(ctx, needs);
    }

    public static boolean blocksShort(GamerContext ctx) {
        return ctx.facts().buildBlocks() < TRIP_BLOCKS;
    }

    public boolean active() {
        return active;
    }

    public String hud() {
        return runner.hud();
    }
}
