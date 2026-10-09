package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;

import java.util.List;

// a food need for the phases that have none of their own. FoodChain only eats, its own hunting reads two settings (the minimum
// and the amount to collect) that the gamer leaves at 0, so a bot walking to the stronghold on a bad bag just ran down to nothing.
// same floor and same top-up the IRON phase uses (the floor starts it, the overworld minimum ends it, FoodPlan.floorNext),
// overworld only: there is nothing to hunt in the nether or the End. the instance only holds the latch and the runner
public final class FoodFloor {
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

    public boolean active() {
        return active;
    }

    public String hud() {
        return runner.hud();
    }
}
