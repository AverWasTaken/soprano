package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
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
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Trip;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.resources.FoodHunt;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;

import java.util.List;

// iron gear, armor, wool for the beds, food top up. KitPlanner sizes ONE iron ingot need for everything missing so we
// mine and smelt once. the engine turns altoUseBlastFurnace off for the run so we never craft a blast furnace, but a
// standing one (village armorer) within altoNearbyBlastFurnaceRange still gets used, the plain furnace is the fallback.
// the smelting itself is fire and forget (altoAsyncSmelting, on for this phase only): the iron goes in, the bot does other
// things on a leash around the furnace, and comes back between two needs (or when it has run out of things to do)
public class IronPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    private final PrepSupport support = new PrepSupport(true);
    private final FurnaceWatch furnaces = new FurnaceWatch();
    private final SmeltSurface surface = new SmeltSurface();
    // the need we are in the middle of while a furnace cooks. a different first need means the last one is done, which is
    // the only time a finished furnace gets fetched
    private KitNeed committed;
    // what the user had for altoAsyncSmelting, null when we did not touch it
    private Boolean userAsync;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.IRON;
    }

    @Override
    public String hud() {
        return GamerPhase.IRON.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        // a table or furnace of ours still standing next to us is picked up first, this is the last chance (see StationPickup)
        // iron still cooking is iron we do not have, however empty the plan looks
        return KitPlanner.plan(facts, cfg.overworld, cfg.end.beds).isEmpty() && facts.furnaceJobs().isEmpty() && !support.stationOwed() && !furnaces.pickingUp();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        support.onEnter(mod);
        furnaces.newPhase(true);
        surface.reset();
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
        FoodHunt.setWoolWanted(false);
        ctx.state().currentNeed = null;
        // other phases smelt too and nobody there would come back for the furnace, so the setting is ours for this phase only
        if (userAsync != null) {
            SettingsOverrides.put(Baritone.settings().altoAsyncSmelting, userAsync);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        // the food task is another tree entirely, this is how it learns that a sheep is worth more shorn than eaten
        FoodHunt.setWoolWanted(KitPlanner.woolShortfall(ctx.facts(), ctx.cfg().end.beds) > 0);
        // the furnace we just emptied is coming down, a few seconds and it goes with us to the next work site
        Task takingBack = furnaces.finishing(mod, ctx);
        if (takingBack != null) {
            hudState = furnaces.hud();
            return takingBack;
        }
        if (!ctx.facts().furnaceJobs().isEmpty()) {
            return cookingTick(mod, ctx);
        }
        // nothing in a furnace (or it just got collected): the plain kit loop
        furnaces.reset();
        committed = null;
        List<KitNeed> needs = KitPlanner.plan(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds);
        KitNeed first = needs.isEmpty() ? null : needs.get(0);
        Task side = support.tick(mod, ctx, first);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // all the ore is mined and we are still down the mine: up first, then the smelt places its furnace in the open
        Task up = surface.tick(mod, ctx, first);
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        Task task = runner.run(ctx, needs);
        hudState = runner.hud();
        return task;
    }

    // iron is cooking somewhere. the side jobs still go first (a golem fight in progress outranks everything), then a trip
    // to the furnace that already started, then the leash, then the plan: the runnable list, or the furnace itself when
    // SmeltFiller says it is time
    private Task cookingTick(AltoClef mod, GamerContext ctx) {
        furnaces.housekeeping(mod, ctx);
        GamerFacts f = ctx.facts();
        if (f.furnaceJobs().isEmpty()) {
            return null;
        }
        Schedule schedule = SmeltFiller.schedule(f, ctx.cfg().overworld, ctx.cfg().end.beds, furnaces.nearby(mod, ctx),
                SmeltFiller.capped(furnaces.pullbacks(), ctx.cfg().overworld));
        KitNeed head = schedule.runnable().isEmpty() ? null : schedule.runnable().get(0);
        Task side = support.tick(mod, ctx, head);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        Task trip = furnaces.active(mod, ctx);
        if (trip == null) {
            trip = furnaces.pullBack(mod, ctx);
        }
        if (trip == null) {
            boolean boundary = head == null || !head.equals(committed);
            Trip what = SmeltFiller.trip(head != null, boundary, f.gameTime(), f.furnaceJobs(), ctx.cfg().overworld);
            if (what != Trip.FILLER) {
                committed = null;
                trip = furnaces.collect(mod, ctx, what == Trip.WAIT ? Mode.WAIT_ALL : Mode.NORMAL);
                if (what == Trip.WAIT) {
                    // standing next to the furnace (screen closed between looks) is the plan, not a stall
                    ctx.progress("waiting for the furnace");
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
        Task up = surface.tick(mod, ctx, head);
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        committed = head;
        Task task = runner.run(ctx, schedule.runnable());
        hudState = runner.hud() + " while iron cooks";
        return task;
    }

    // iron is the one phase where "mostly done" is fine: with the pickaxe, a light and two buckets the portal can be
    // built, the armor and wool are nice to have. anything less and we stop instead of walking into the nether naked
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (attempt < ctx.cfg().maxAttempts) {
            return Timeout.RETRY;
        }
        return KitPlanner.essentialsMet(ctx.facts(), ctx.cfg().overworld) ? Timeout.SKIP : Timeout.STUCK;
    }
}
