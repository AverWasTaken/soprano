package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.speedrun.gamer.FoodFloor;
import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.FoodTripRules;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.LocateFoodBand;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.LocateStrongholdTask;
import adris.altoclef.tasksystem.Task;

import java.util.List;
import java.util.Optional;

// throw eyes, walk, throw eyes until the estimator says the stronghold is right under us (or a frame shows up).
// the rays live in RunState so a relog keeps them
public class LocatePhase implements PhaseHandler {
    private LocateStrongholdTask task;
    // the walk to the stronghold is the longest stretch with no food need of its own, the floor sends us hunting when the bag runs low
    private final FoodFloor food = new FoodFloor();
    // and over the floor but under the End's want, a soft top-up while an animal is right there (LocateFoodBand)
    private final LocateFoodBand topUp = new LocateFoodBand();
    private final KitRunner topUpRunner = new KitRunner();
    // the hunt we last handed out, from either band. Task.tick keeps the running instance when the next one is equal
    // (CollectFoodTask goes by units), so the floor's hunt can be the one with meat in the smoker while the band's runner holds a
    // twin that never ran
    private Task lastHunt;
    private boolean hopelessSaid;

    @Override
    public GamerPhase phase() {
        return GamerPhase.LOCATE;
    }

    @Override
    public String hud() {
        return GamerPhase.LOCATE.hud();
    }

    @Override
    public String hudState() {
        if (food.active()) {
            return food.hud();
        }
        if (topUp.active()) {
            return topUpRunner.hud();
        }
        return task == null ? null : task.step();
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.strongholdStart != null || state.endPortalCenter != null;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        Optional<GamerPhase> back = StrongholdRules.regressForEyes(facts, state);
        // polled every tick, said once per visit (the cooldown in PhaseRules decides when it actually goes)
        if (back.isPresent() && !hopelessSaid) {
            hopelessSaid = true;
            Debug.logInternal(StrongholdRules.hopelessLine(facts, state));
        }
        return back;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        hopelessSaid = false;
        task = null;
        food.reset();
        // "not again this visit" means the visit, not the attempt (same as RoomPhase's first-attempt resets)
        if (ctx.attempt() <= 1) {
            topUp.reset();
        } else {
            topUp.standDown();
        }
        topUpRunner.reset();
        lastHunt = null;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        Task hunt = food.tick(ctx);
        if (hunt != null) {
            stopTopUp(ctx, topUp.drop(ctx.food().held()));
            return handOut(hunt);
        }
        Task more = topUpTick(ctx);
        if (more != null) {
            return handOut(more);
        }
        lastHunt = null;
        if (task == null) {
            task = new LocateStrongholdTask(ctx);
        }
        return task;
    }

    private Task topUpTick(GamerContext ctx) {
        FoodPlan f = ctx.food();
        // persisted at the throw and cleared once the eye is back or gone, so this also covers an eye from before an interrupt
        boolean eyeOut = ctx.state().eyeBaseline > 0;
        // the cook is synchronous here (async smelting is IRON's and GATHER's), so dropping the hunt mid batch strands the meat
        Task running = lastHunt != null && lastHunt.isActive() ? lastHunt : topUpRunner.task();
        boolean cooking = topUp.active() && running instanceof CollectFoodTask collect && collect.isActive() && collect.cooking();
        boolean was = topUp.active();
        String line = topUp.tick(f.held(), FoodTripRules.units(f), f.floor(), ctx.cfg().end.wantFoodUnits, f.overworldMinimum(),
                ctx.facts().preyNear(), eyeOut, cooking, ctx.facts().gameTime());
        if (!topUp.active()) {
            if (was) {
                stopTopUp(ctx, line);
            }
            return null;
        }
        if (line != null) {
            ctx.log(line);
        }
        // and straight to the same task while it cooks: run() rebuilds the hunt when its target moves, and the meat counting in at
        // the smoker moves it
        if (cooking) {
            return running;
        }
        return topUpRunner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, f.overworldMinimum())));
    }

    private Task handOut(Task hunt) {
        if (lastHunt == null || !lastHunt.isActive() || !lastHunt.equals(hunt)) {
            lastHunt = hunt;
        }
        return lastHunt;
    }

    private void stopTopUp(GamerContext ctx, String line) {
        if (line != null) {
            ctx.log(line);
            topUpRunner.reset();
        }
    }
}
