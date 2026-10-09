package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PrepSupport;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Decision;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Trip;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;

import java.util.List;

// wood, table, stone tools, furnace, first food. the kit comes from KitPlanner so this never re-collects what we hold
public class GatherPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    private final PrepSupport support = new PrepSupport(false);
    // the smoker the food cooks in (the same watch IRON uses for its furnace, it does not care what is cooking)
    private final FurnaceWatch furnaces = new FurnaceWatch();
    // the need we are in the middle of while the food cooks, a different first need is the boundary where we turn around
    private KitNeed committed;
    // what the user had for altoAsyncSmelting, null when we did not touch it
    private Boolean userAsync;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.GATHER;
    }

    @Override
    public String hud() {
        return GamerPhase.GATHER.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public KitRunner kitRunner() {
        return runner;
    }

    @Override
    public PrepSupport support() {
        return support;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        // a table or furnace of ours still standing next to us is picked up first, this is the last chance (see StationPickup)
        // food still cooking is food we do not have, and the smoker coming down is part of the job
        return KitPlanner.gather(facts, cfg.overworld, cfg.end.beds).isEmpty() && facts.furnaceJobs().isEmpty()
                && !support.stationOwed() && !furnaces.pickingUp();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        support.onEnter(mod);
        furnaces.newPhase(true);
        committed = null;
        hudState = null;
        var async = Baritone.settings().altoAsyncSmelting;
        if (!SettingsOverrides.isHeld(async)) {
            userAsync = async.value;
        }
        SettingsOverrides.put(async, true);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        support.onExit(mod);
        ctx.state().currentNeed = null;
        // nobody outside the phases that come back for the smoker would collect from it, same rule as IRON
        if (userAsync != null) {
            SettingsOverrides.put(Baritone.settings().altoAsyncSmelting, userAsync);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        // the smoker we just emptied is coming down, a few seconds and it goes with us
        Task takingBack = furnaces.finishing(mod, ctx);
        if (takingBack != null) {
            hudState = furnaces.hud();
            return takingBack;
        }
        if (!ctx.facts().furnaceJobs().isEmpty()) {
            furnaces.housekeeping(mod, ctx);
            if (!ctx.facts().furnaceJobs().isEmpty()) {
                return cookingTick(mod, ctx);
            }
        }
        furnaces.reset();
        committed = null;
        List<KitNeed> needs = KitPlanner.gather(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds);
        Task side = support.tick(mod, ctx, needs);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        Task task = runner.run(ctx, needs);
        hudState = runner.hud();
        return task;
    }

    // food is cooking in a smoker somewhere. same order as IRON's cookingTick: side jobs, the trip that already started, then
    // the gather list (wood, stone, crafts: all surface work) or the smoker itself when SmeltFiller says it is time. with nothing left to do the bot stands by the smoker with the screen closed, which beats staring at the gui
    private Task cookingTick(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        List<KitNeed> plan = KitPlanner.gather(f, ctx.cfg().overworld, ctx.cfg().end.beds);
        List<KitNeed> runnable = SmeltFiller.gatherRunnable(f, ctx.cfg().overworld, ctx.cfg().end.beds);
        KitNeed head = runnable.isEmpty() ? null : runnable.get(0);
        Task side = support.tick(mod, ctx, runnable);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        Task trip = furnaces.active(mod, ctx);
        if (trip == null) {
            boolean boundary = head == null || !head.equals(committed);
            Decision what = SmeltFiller.decide(head != null, boundary, SmeltFiller.gatherBlocking(f, plan),
                    f.gameTime(), f.furnaceJobs(), ctx.cfg().overworld);
            if (what.trip() != Trip.FILLER) {
                committed = null;
                trip = furnaces.collect(mod, ctx, what.trip() == Trip.WAIT ? Mode.WAIT_ALL : Mode.NORMAL,
                        what.trip() == Trip.WAIT ? "nothing else to do, waiting it out" : what.why().text);
                if (what.trip() == Trip.WAIT && FurnaceJobs.waitIsHonest(f.furnaceJobs(), f.gameTime(), FurnaceJobs.WAIT_SLACK_TICKS)) {
                    // standing by the smoker (screen closed between looks) is the plan, not a stall. but only until the food
                    // is due: a smoker that never finishes used to keep this alive on every tick of the wait
                    ctx.progress("waiting for the smoker");
                }
            }
        }
        if (trip != null) {
            hudState = furnaces.hud();
            return trip;
        }
        if (head == null) {
            return null;
        }
        committed = head;
        Task task = runner.run(ctx, runnable);
        hudState = runner.hud() + " while the food cooks";
        return task;
    }

    // slow food (desert start, no animals around) must not end the run on its first phase: with both stone tools in
    // hand IRON carries on, its plan asks for the food again anyway
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (KitPlanner.stoneToolsMet(ctx.facts())) {
            return Timeout.SKIP;
        }
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }
}
