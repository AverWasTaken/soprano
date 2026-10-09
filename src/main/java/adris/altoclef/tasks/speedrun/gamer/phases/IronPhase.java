package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.EarlyIronPick;
import adris.altoclef.tasks.speedrun.gamer.FoodGate;
import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.Workbenches;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules;
import adris.altoclef.tasks.speedrun.gamer.PackUp;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PickDiag;
import adris.altoclef.tasks.speedrun.gamer.PrepSupport;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.resources.FoodHunt;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// iron gear, armor, wool for the beds, food top up. KitPlanner sizes ONE iron ingot need for everything missing so we
// mine and smelt once. the engine turns altoUseBlastFurnace off for the run so we never craft a blast furnace, but a
// standing one (village armorer) within altoNearbyBlastFurnaceRange still gets used, the plain furnace is the fallback.
// the smelting itself is fire and forget (altoAsyncSmelting, on for this phase only): the iron goes in, the bot does other
// things wherever the work is, and comes back between two needs, when the next need is waiting on the iron, or when it has
// run out of things to do
public class IronPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    // the stations this phase puts down and takes back, shared by the side jobs and the furnace watch
    private final Workbenches benches = new Workbenches();
    private final PrepSupport support = new PrepSupport(true, benches);
    private final FurnaceWatch furnaces = new FurnaceWatch(benches);
    private final SmeltSurface surface = new SmeltSurface();
    // says so (once) when the plan stops owning its iron pickaxe, see PickDiag
    private final PickDiag pickDiag = new PickDiag();
    // furnaces we already made a pack-up trip to (PackUp), so a trip that left input cooking is not repeated
    private final Set<RunState.Pos> packed = new HashSet<>();
    // the need we are in the middle of while a furnace cooks. a different first need means the last one is done, which is
    // the only time a finished furnace gets fetched
    private KitNeed committed;
    // what the user had for altoAsyncSmelting, null when we did not touch it
    private Boolean userAsync;
    private String hudState;
    // FoodGate: a food top-up that started on the surface runs to the full amount, and the band the count was last in (-1 = unset)
    private boolean foodTopUp;
    private int foodBand = -1;
    // the food need led last tick (a screen that opens then is its own, FoodGate.cookBusy) and the soft top-up was called off
    // because the raw meat in the bag covers the hole (FoodPlan.covered), the second only so it is announced once
    private boolean foodLed;
    private boolean foodCovered;
    // CookGate: a cook that started early (surface, or between jobs) keeps the front until the meat is cooked
    private boolean cookLatch;

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
    public KitRunner kitRunner() {
        return runner;
    }

    @Override
    public PrepSupport support() {
        return support;
    }

    @Override
    public boolean ownsBenches() {
        return true;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        // a table or furnace of ours still standing is picked up first, this is the last chance (Workbenches.phaseMayEnd)
        // iron still cooking is iron we do not have, however empty the plan looks
        return KitPlanner.plan(facts, cfg.overworld, cfg.end.beds, FoodPlan.of(facts, cfg, GamerPhase.IRON)).isEmpty() && facts.furnaceJobs().isEmpty()
                && Workbenches.phaseMayEnd(state, facts.dimension().name(), facts.gameTime()) && !furnaces.cooking();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        support.onEnter(mod);
        furnaces.newPhase();
        surface.reset();
        pickDiag.reset();
        packed.clear();
        committed = null;
        hudState = null;
        foodTopUp = false;
        foodLed = false;
        foodCovered = false;
        cookLatch = false;
        foodBand = -1;
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
        // other phases smelt too and nobody there would come back for the furnace, so the setting is ours for this phase only
        if (userAsync != null) {
            SettingsOverrides.put(Baritone.settings().altoAsyncSmelting, userAsync);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        int pick = KitPlanner.have(ctx.facts(), "iron_pickaxe");
        if (pickDiag.lost(pick)) {
            Debug.logInternal(PickDiag.missing(pick, KitPlanner.held(ctx.facts(), "iron_pickaxe"), ctx.facts().spent(Items.IRON_PICKAXE),
                    ctx.facts().pickScan()));
        }
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
        List<KitNeed> needs = gateFood(mod, ctx, KitPlanner.plan(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food()));
        KitNeed first = needs.isEmpty() ? null : needs.get(0);
        Task side = support.tick(mod, ctx, needs);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // all the ore is mined and we are still down the mine: up first, then the smelt places its furnace in the open
        // (the early pick batch is three items and goes in right where the ore is, SmeltSurface leaves it alone)
        Task up = surface.tick(mod, ctx, first, second(needs));
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        // the first three raw iron are about to go in: from here the early need stays owed until the furnace is lit
        EarlyIronPick.track(ctx.state(), first, ctx.facts(), ctx.cfg().overworld);
        Task task = runner.run(ctx, needs);
        hudState = runner.hud();
        return task;
    }

    // iron is cooking somewhere. the side jobs still go first (a golem fight in progress outranks everything), then a trip
    // to the furnace that already started, then the plan: the runnable list, or the furnace itself when SmeltFiller says it
    // is time
    private Task cookingTick(AltoClef mod, GamerContext ctx) {
        furnaces.housekeeping(mod, ctx);
        GamerFacts f = ctx.facts();
        if (f.furnaceJobs().isEmpty()) {
            return null;
        }
        Schedule schedule = SmeltFiller.schedule(f, ctx.cfg().overworld, ctx.cfg().end.beds, SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod)),
                ctx.food());
        List<KitNeed> runnable = gateFood(mod, ctx, schedule.runnable());
        KitNeed head = runnable.isEmpty() ? null : runnable.get(0);
        // no pick yet and the early batch is done: the pick is worth the detour, the mining need would not end for 36 more
        // ingots. nothing else cuts a need short, an iron craft waiting on the output is not a reason to leave a ladder.
        // not in the middle of a cook's load either, that is a few seconds and the pick can wait for them
        boolean boundary = head == null || !head.equals(committed);
        boolean interrupt = EarlyIronPick.collectNow(f, ctx.cfg().overworld) && f.cookStation() == null;
        // a smoker cooks 5 s an item: standing at it for the 40 s a batch takes beats walking off to mine and back (mayStandBy)
        FurnacePlan.Moment moment = new FurnacePlan.Moment(head != null, boundary, interrupt, schedule.isStockUp(head), true);
        FurnacePlan.Plan plan = furnaces.plan(ctx, moment);
        Task side = plan.standingBy() ? support.tickStandBy(mod, ctx, runnable) : support.tick(mod, ctx, runnable);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // a furnace emptied down a mine is not a place to start the next batch of meat unless the work is down there too
        furnaces.mayCookHere(SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod))
                || SmeltSurface.nextWorkDown(head, f.count(Items.RAW_IRON), f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT)));
        // the trip waits until everything is out for a stand-by (the screen stays closed between looks), then FurnaceWatch reloads
        // or picks the smoker up as it always does, and the filler gets the bot back once no smoker is left
        Task trip = furnaces.trip(mod, ctx, plan, moment);
        if (trip != null) {
            if (furnaces.started()) {
                committed = null;
            }
            // standing next to the furnace (screen closed between looks) is the plan, not a stall. but only until the job is due plus
            // the patience: a furnace that never finishes must not keep the watchdog off
            if (furnaces.creditsWait(ctx)) {
                ctx.progress("waiting for the furnace");
            }
            hudState = furnaces.hud();
            return trip;
        }
        if (head == null) {
            return null;
        }
        // about to leave the mine: whatever is still cooking at the bottom of it comes with us first
        Task pack = packUp(mod, ctx, head, second(runnable));
        if (pack != null) {
            hudState = furnaces.hud();
            return pack;
        }
        Task up = surface.tick(mod, ctx, head, second(runnable));
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        committed = head;
        Task task = runner.run(ctx, runnable);
        hudState = runner.hud() + " while a batch cooks";
        return task;
    }

    private static KitNeed second(List<KitNeed> needs) {
        return needs.size() > 1 ? needs.get(1) : null;
    }

    // the climb to the surface is about to start and one of our furnaces down here still has a job: go to it first. nearly done
    // and we wait for it, otherwise its contents come back out and the station comes with us (FurnaceWatch takes it down after
    // the last collect). the planner re-smelts or re-cooks what came back on the surface. once per furnace, a trip that left
    // input behind (the cap) must not send us round again
    private Task packUp(AltoClef mod, GamerContext ctx, KitNeed head, KitNeed next) {
        GamerFacts f = ctx.facts();
        if (f.furnaceJobs().isEmpty() || !surface.aboutToClimb(mod, ctx, head, next)) {
            return null;
        }
        Vec3 me = mod.getPlayer().position();
        RunState.FurnaceJob job = PackUp.pick(f.furnaceJobs(), packed, j -> jobDepth(mod, j), j -> PackUp.walk(j, me.x, me.y, me.z));
        if (job == null) {
            return null;
        }
        packed.add(job.pos);
        return furnaces.leave(mod, ctx, job, FurnacePlan.Leaving.AREA, jobDepth(mod, job));
    }

    // blocks between the job's furnace and the open sky over it (over us, when its chunk is not loaded)
    private static int jobDepth(AltoClef mod, RunState.FurnaceJob job) {
        BlockPos at = new BlockPos(job.pos.x, job.pos.y, job.pos.z);
        BlockPos column = mod.getChunkTracker().isChunkLoaded(at) ? at : mod.getPlayer().blockPosition();
        return SmeltSurface.depth(mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), job.pos.y);
    }

    // food first, then the cook. the cook is already the last need of the plan (nothing leaves IRON with raw meat a furnace can
    // cook); this lets it go early, on the surface or between two jobs, once there is enough of it to be worth the stop
    private List<KitNeed> gateFood(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        List<KitNeed> gated = gateFoodNeed(mod, ctx, needs);
        int at = CookGate.index(gated);
        if (at < 0) {
            cookLatch = false;
            return gated;
        }
        if (at == 0) {
            return gated;
        }
        boolean surfaced = SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod));
        boolean loadBusy = otherLoadInFlight(mod, ctx);
        // a cook that is running is committed: the meat moving into the station is not a reason to hand the head to the iron
        boolean lead = CookGate.leads(gated, at, CookGate.raw(ctx.facts()), surfaced, cookLatch, loadBusy, ctx.facts().cookStation() != null);
        if (lead && !cookLatch) {
            Debug.logInternal("cook: " + CookGate.raw(ctx.facts()) + " raw meat in the bag, cooking it now instead of eating it raw");
        }
        // an iron load in flight only holds the cook back for its few ticks, the latch waits with it (clearing it sent the cook
        // back to "wait until we surface" for the rest of a cave trip)
        if (!loadBusy) {
            cookLatch = lead;
        }
        return lead ? CookGate.lead(gated, at) : gated;
    }

    // a furnace load is going on that is not the cook's: a screen of one is open or a smelt task had it a moment ago (the same
    // test the station pickup waits on). once the cook is the one running it owns that screen, so the answer is no
    private static boolean otherLoadInFlight(AltoClef mod, GamerContext ctx) {
        return ctx.facts().cookStation() == null && loadInFlight(mod, ctx);
    }

    private static boolean loadInFlight(AltoClef mod, GamerContext ctx) {
        return WorkbenchRules.loadInFlight(mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu, AsyncSmelting.lastWork(), ctx.facts().gameTime());
    }

    // the kit's own food need only leads when FoodGate says so, otherwise it waits behind the ore (it comes back the moment
    // we surface, and the food chain eats on its own in the meantime). the stock-up fillers
    // SmeltFiller adds are surface work and not touched
    private List<KitNeed> gateFoodNeed(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        // the planner's own count (bag, open screen, what a smoker is cooking, the raw meat nobody can cook at its raw value)
        FoodPlan food = ctx.food();
        int held = food.held();
        int band = food.band();
        if (band != foodBand) {
            // the user wants to see this one (an apple and six mutton was why the bot kept leaving the mine)
            if (foodBand >= 0) {
                Debug.logInternal("food: " + held + " units held, " + (band == 0 ? "under the floor of " + food.floor()
                        : band == 1 ? "over " + food.floor() + " but short of " + food.overworldMinimum() : "at the " + food.overworldMinimum() + " we want"));
            }
            foodBand = band;
        }
        int at = FoodGate.index(needs, food);
        if (at < 0) {
            foodTopUp = false;
            foodLed = false;
            foodCovered = false;
            return needs;
        }
        // the heightmap only matters between the two lines
        boolean surfaced = band == 1 && SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod));
        // a cook picked its station, or a smelt screen is in hand right now: the meat in that screen is not in the bag, not a job
        // yet and not in the held sum, which is how a half loaded smoker read as 56 units and sent the bot for cows. unless the
        // food need was the one leading: then the screen is the top-up's own and it must not cut itself off
        boolean busy = FoodGate.cookBusy(ctx.facts().cookStation() != null, loadInFlight(mod, ctx), foodLed);
        boolean lead = food.leads(surfaced, foodTopUp, busy);
        boolean covered = food.covered();
        if (covered && !foodCovered) {
            Debug.logInternal("food: " + held + " units held, short of " + food.overworldMinimum() + " but the raw meat in the bag covers it once cooked, no trip");
        }
        foodCovered = covered;
        boolean wasTopUp = foodTopUp;
        foodTopUp = food.nextTopUp(foodTopUp, lead);
        if (foodTopUp && !wasTopUp) {
            Debug.logInternal("food: topping up from " + held + " to " + food.overworldMinimum() + " now that it is cheap");
        }
        foodLed = lead;
        return lead ? needs : FoodGate.without(needs, at);
    }

    // iron is the one phase where "mostly done" is fine: with the pickaxe, a light and two buckets the portal can be
    // built, the armor and wool are nice to have. anything less and we stop instead of walking into the nether naked
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (attempt < ctx.cfg().maxAttempts) {
            return Timeout.RETRY;
        }
        // skipping with iron still in a furnace is fine: PORTAL takes a last look before it leaves the overworld (TAKE_ALL on
        // every job that is left, see PortalPhase.tick), so nothing gets stranded
        return KitPlanner.essentialsMet(ctx.facts(), ctx.cfg().overworld) ? Timeout.SKIP : Timeout.STUCK;
    }
}
