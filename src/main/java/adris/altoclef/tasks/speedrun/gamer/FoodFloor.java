package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;

import java.util.List;

// a food need for the phases that have none of their own. FoodChain only eats, its own hunting reads two settings (the minimum
// and the amount to collect) that the gamer leaves at 0, so a bot walking to the stronghold on a bad bag just ran down to nothing.
// same floor and same top-up the IRON phase uses (minHeldFoodUnits starts it, minFoodUnits ends it), overworld only: there is
// nothing to hunt in the nether or the End. the pure rule is static, the instance only holds the latch and the runner
public final class FoodFloor {
    private final KitRunner runner = new KitRunner();
    private boolean active;

    public void reset() {
        runner.reset();
        active = false;
    }

    // below the floor it starts, and it carries on up to the full amount (flipping at the line would stop the hunt halfway)
    public static boolean next(boolean active, int held, OverworldConfig cfg) {
        if (held >= cfg.minFoodUnits) {
            return false;
        }
        return active || held < cfg.minHeldFoodUnits;
    }

    // the task that gets food while the floor is breached, null when the phase should carry on with its own work
    public Task tick(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        OverworldConfig cfg = ctx.cfg().overworld;
        boolean was = active;
        active = f.dimension() == Dimension.OVERWORLD && next(active, KitPlanner.foodHeld(f, cfg, ctx.cfg().end.beds), cfg);
        if (!active) {
            if (was) {
                runner.reset();
            }
            return null;
        }
        return runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, cfg.minFoodUnits)));
    }

    public boolean active() {
        return active;
    }

    public String hud() {
        return runner.hud();
    }
}
