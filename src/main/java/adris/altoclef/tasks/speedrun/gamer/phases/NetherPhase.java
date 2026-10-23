package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.gamer.DetourSpec;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.EyeMath;
import adris.altoclef.tasks.speedrun.gamer.FoodFloor;
import adris.altoclef.tasks.speedrun.gamer.FoodTripRules;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
import adris.altoclef.tasks.speedrun.gamer.NetherPick;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PiglinGold;
import adris.altoclef.tasks.speedrun.gamer.ResourceDetour;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.CampPinger;
import adris.altoclef.tasks.speedrun.gamer.tasks.FindNetherStructureTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.GhastWatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.HoldLatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.HomePortalWalk;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherGround;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherGoldTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherPickTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.tasks.speedrun.gamer.tasks.PearlHuntTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.RodsWatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasks.speedrun.gamer.tasks.WearFromBag;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.SeenFilter;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// fortress -> blaze rods -> ender pearls, all inside the Nether. the order matters: rods are the part that can be
// camped for, pearls are the part that can be hunted for. what we SAW (fortress, bastions, a warped forest) is kept in
// RunState so a relog or a retry does not forget it; the sub step this tick is derived from the inventory and that memory,
// not stored, so there is no step to get out of sync with
public class NetherPhase implements PhaseHandler {
    private static final Block[] FORTRESS = {Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS};
    // plain blackstone is not here on purpose: basalt deltas are full of it and would read as bastions all day
    private static final Block[] BASTION = {Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE};
    private static final Block[] WARPED = {Blocks.WARPED_NYLIUM};
    private static final Block[] TRACKED = {Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.WARPED_NYLIUM, Blocks.NETHER_GOLD_ORE};
    // blocks in #guarded_by_piglins that sit around the nether (the tag also has chests and gold blocks, nobody breaks those by accident)
    private static final Block[] GUARDED = {Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE};
    // the known-ore look is a walk over every tracked gold ore, twice a second is plenty
    private static final int ORE_LOOK_TICKS = 10;
    private static final int GUARD_HOLD_TICKS = 20;

    private static final int SCAN_EVERY_TICKS = 6;
    // the nearest this many tracked blocks of a kind get asked about, the tracker can hold thousands of bricks
    private static final int SCAN_CANDIDATES = 40;
    private static final double CAMP_PING_SECONDS = 30;
    // an enderman that ducked out of view still counts for this long, so the pearl step does not flip every other tick
    private static final double ENDERMAN_HOLD_SECONDS = 6;
    // a known fortress further than this from us is worth a walk before the rods task starts wandering for bricks
    private static final double FORTRESS_NEAR_BLOCKS = 48;
    private static final double FORTRESS_WALK_SECONDS = 150;
    private static final double FLEE_SECONDS = 10;
    private static final int FLEE_BLOCKS = 48;

    private NetherSweepPlanner planner;
    private RodsWatch rodsWatch;
    private CampPinger campPinger;
    private GhastWatch ghastWatch;
    private HoldLatch endermanLatch;
    // the latch above as of this tick, read by everything that asks "is an enderman about" (updated once at the top of tick)
    private boolean endermanHere;
    // game time as of the last tick, for the hunt's freshness check (stallSeconds gets no context)
    private long lastGameTime;
    private GetToXZTask walkToFortress;
    private RunState.Pos fortressWalkTo;
    private double fortressWalkSince;
    private RunState.Pos fortressWalkGivenUp;
    private FindNetherStructureTask findFortress;
    private FindNetherStructureTask findWarped;
    private CollectBlazeRodsTask rodsTask;
    private int rodsTaskTarget;
    private PearlHuntTask hunt;
    private int huntTarget;
    private final HomePortalWalk homeWalk = new HomePortalWalk();
    // the bag ran dry out here: home through the portal, the overworld's food floor, and back (FoodTripRules). rods, pearls and the
    // sweep all live in RunState, so none of it is lost on the way
    private final FoodFloor foodFloor = new FoodFloor();
    private DefaultGoToDimensionTask goHome;
    private boolean foodTrip;
    // why the live trip started, and the trips used per reason: a blocks trip that came back empty handed leaves the food budget
    // alone and the other way round
    private FoodTripRules.Reason tripReason = FoodTripRules.Reason.FOOD;
    private int foodTrips;
    private int blockTrips;
    // last game tick a blaze could see us, we were burning or hurt (FoodTripRules.unsafe). a food trip waits for it to be 3 s old.
    // read off the blazes directly: the rods task is not always the one ticking, so BlazeFight's mode can be stale or absent
    private long lastUnsafeTick = -1;
    // the overworld half's clock, set by what was short when we got up there
    private double tripSeconds = FoodTripRules.MAX_SECONDS;
    // the overworld half also brings blocks: a blocks trip always, a food trip when the bag was empty of them on the way out (or it
    // would come back and go straight home again)
    private boolean tripBlocks;
    private double foodTripStart;
    private boolean foodTripUp;
    private int foodDeathsSeen;
    // flint from soul sand valley gravel, only while it is still owed (no flint and steel, fire charge or flint on us)
    private final ResourceDetour gravel = ResourceDetour.gravel();
    // a barter ticked this recently is still going (see gravelSide)
    private static final double TRADE_FRESH_SECONDS = 1;
    // a pick craft that fell over (an input went missing, or it hung) is not tried again for this long
    private static final double PICK_RETRY_SECONDS = 60;
    private static final int PICK_TRIES = 3;
    private Task boots;
    // the pick we are making from the bag (see pickSide) and whether we already said there is nothing to make one from
    private NetherPickTask pickTask;
    private double pickRetryAt;
    private int pickFails;
    private boolean noPickSaid;
    private TradeWithPiglinsTask trade;
    private int tradeTarget;
    // the gold piece fallback (goldSide): up to NetherGold.MAX_TRIES tries a visit, a latched one re-arms NetherGold.RETRY_SECONDS later and a death resets
    // them. goldOver = this try is done or gave up, goldStartedAt is the try's clock (-1 =
    // not begun), guarding = gold ore is on baritone's don't-break list because a piglin is close enough to be angered by it
    private NetherGoldTask goldTask;
    private int goldNeed;
    private Task goldWear;
    private Item goldWearing;
    private double goldStartedAt;
    private boolean goldOver;
    private boolean guarding;
    private int guardHold;
    private BlockPos goldAnchor;
    private boolean oreLeft;
    private int lastNuggets;
    // when goldOver latched (-1 = not yet noted), tries begun this visit, and the death count the tries were counted under
    private double goldOverAt = -1;
    private int goldTries;
    private int goldDeathsSeen;

    private boolean tracking;
    private boolean failedAlready;
    private boolean warpedSearchOver;
    private int tickCounter;
    private int rotation;
    private int lastFortressCells;
    private int lastWarpedCells;
    private int lastPearls;
    private double huntWanderSince = -1;
    private double barterSpent;
    private double barterTickAt = -1;
    private int lastHurt;
    private Task flee;
    private double fleeUntil;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.NETHER;
    }

    @Override
    public String hud() {
        return GamerPhase.NETHER.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    // pure: what we hold decides, plus "the budget is nearly over" which only needs the game clock
    @Override
    public ResourceDetour detour() {
        return gravel;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return EyeMath.canLeaveNether(facts, cfg, state.framesFilled, budgetOver(facts, state, cfg) || state.netherRodsGaveUp);
    }

    static boolean budgetOver(GamerFacts facts, RunState state, GamerConfig cfg) {
        if (state.phaseEnteredGameTime <= 0) {
            return false;
        }
        double limit = cfg.budgets.nether * 60 - cfg.nether.budgetGraceSeconds;
        return (facts.gameTime() - state.phaseEnteredGameTime) / 20.0 >= limit;
    }

    // a death in the Nether leaves us in the overworld with an empty bag: re-plan the kit instead of walking into the
    // Nether again with nothing
    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return NetherRegress.fromOverworld(facts, cfg);
    }

    // the budget or the stall timer fired: leave with the floor if we have it, else try again, else give up
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (EyeMath.canLeaveNether(ctx.facts(), ctx.cfg(), ctx.state().framesFilled, true)) {
            return Timeout.SKIP;
        }
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        RunState s = ctx.state();
        NetherConfig n = ctx.cfg().nether;
        s.netherRodsGaveUp = false;
        planner = new NetherSweepPlanner(s, n);
        planner.setGround(new NetherGround(mod, () -> n.waypointShiftBlocks));
        planner.setLog(Debug::logInternal);
        rodsWatch = new RodsWatch(n.spawnerCampMinutes * 60, n.spawnerDryMinutes * 60, n.rodsBudgetMinutes * 60);
        campPinger = new CampPinger(CAMP_PING_SECONDS);
        ghastWatch = new GhastWatch(2, n.ghastWindowSeconds);
        endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);
        endermanHere = false;
        walkToFortress = null;
        fortressWalkTo = null;
        fortressWalkGivenUp = null;
        findFortress = new FindNetherStructureTask(planner, Goal.FORTRESS, ctx::secondsInPhase);
        findWarped = new FindNetherStructureTask(planner, Goal.WARPED, ctx::secondsInPhase);
        homeWalk.reset();
        foodFloor.reset();
        goHome = null;
        foodTrip = false;
        tripReason = FoodTripRules.Reason.FOOD;
        tripBlocks = false;
        foodTrips = 0;
        lastUnsafeTick = -1;
        blockTrips = 0;
        foodTripUp = false;
        foodDeathsSeen = ctx.state().deaths.size();
        rodsTask = null;
        hunt = null;
        trade = null;
        boots = null;
        pickTask = null;
        pickRetryAt = 0;
        pickFails = 0;
        noPickSaid = false;
        goldTask = null;
        goldWear = null;
        goldWearing = null;
        goldStartedAt = -1;
        goldOver = false;
        goldOverAt = -1;
        goldTries = 0;
        goldDeathsSeen = ctx.state().deaths.size();
        goldAnchor = null;
        oreLeft = false;
        lastNuggets = 0;
        unguard(mod);
        flee = null;
        fleeUntil = 0;
        lastHurt = 0;
        lastPearls = 0;
        rotation = 0;
        failedAlready = false;
        warpedSearchOver = false;
        huntWanderSince = -1;
        barterSpent = 0;
        barterTickAt = -1;
        lastFortressCells = 0;
        lastWarpedCells = 0;
        hudState = null;
        gravel.reset();
        ensureTracking(mod);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        unguard(mod);
        homeWalk.stopTracking(mod);
        gravel.reset();
        if (tracking) {
            mod.getBlockTracker().stopTracking(TRACKED);
            tracking = false;
        }
    }

    // a pearl hunt from the nook stands in one hole waiting for endermen, which is not standing still for nothing. kills ping
    // the watchdog (see PearlHuntTask), stares do not
    @Override
    public double stallSeconds() {
        return EndermanNook.stallSeconds(hunt != null && hunt.inNook(lastGameTime), PhaseHandler.super.stallSeconds());
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (planner == null) {
            onEnter(mod, ctx);
        }
        noteRespawn(ctx);
        lastGameTime = ctx.facts().gameTime();
        if (ctx.facts().dimension() != Dimension.NETHER) {
            // a death or a trip home does not run onExit, the gold ore must not stay off limits in the overworld
            unguard(mod);
            if (foodTrip) {
                // the hunt's clock starts when we are up here, not when we left the fortress
                if (!foodTripUp) {
                    foodTripUp = true;
                    foodTripStart = ctx.secondsInPhase();
                    tripSeconds = FoodTripRules.seconds(ctx.food().shortOfMinimum(), tripBlocks && FoodFloor.blocksShort(ctx));
                }
                // over when nothing is short any more: food at the overworld minimum and, on a blocks trip, the entry stack held
                Task hunt = foodFloor.tickTrip(ctx, tripBlocks);
                if (!FoodTripRules.tripOver(hunt != null, ctx.secondsInPhase() - foodTripStart, tripSeconds)) {
                    hudState = foodFloor.hud();
                    return hunt;
                }
                foodTrip = false;
                foodTripUp = false;
                foodFloor.reset();
                ctx.progress(tripBlocks ? "blocks trip done" : "food trip done");
            }
            return toTheNether(mod, ctx);
        }
        homeWalk.stopTracking(mod);
        ensureTracking(mod);
        ReturnPhase.recordPortal(mod, ctx.state());
        rememberOverworldPortal(mod, ctx);
        double now = ctx.secondsInPhase();
        // once per tick before anything reads it: the pick and gravel detours ask too, off the raw ray they flipped every tick
        endermanHere = endermanLatch.update(PearlHuntTask.endermanNear(mod), now);
        // before the cover, whatever path that one walks must not break gold either
        boolean piglins = guardGold(mod);
        noteUnsafe(mod, ctx);
        Task cover = ghastCover(mod, now);
        if (cover != null) {
            gravel.preempted(ctx.facts().gameTime());
            return cover;
        }
        Task home = foodTripHome(mod, ctx);
        if (home != null) {
            return home;
        }
        if (tickCounter++ % SCAN_EVERY_TICKS == 0) {
            scanSights(mod, ctx);
        }
        Task flint = gravelSide(mod, ctx, now);
        if (flint != null) {
            return flint;
        }
        Task pick = pickSide(mod, ctx, now);
        if (pick != null) {
            return pick;
        }
        Task gold = goldSide(mod, ctx, now, piglins);
        if (gold != null) {
            return gold;
        }
        return nextStep(mod, ctx, now);
    }

    // flat blocks to the way home, 0 when it is not known (the trip does not start then anyway)
    private static double portalDistance(AltoClef mod, GamerContext ctx) {
        Player p = mod.getPlayer();
        RunState.Pos known = ctx.state().netherPortal;
        double px;
        double pz;
        if (known != null) {
            px = known.x;
            pz = known.z;
        } else {
            Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
            if (used.isEmpty()) {
                return 0;
            }
            px = used.get().getX();
            pz = used.get().getZ();
        }
        return Math.hypot(p.getX() - px, p.getZ() - pz);
    }

    // runs every tick off the trip so the "3 s calm" has something to measure. only the line crossed matters, but the blaze that
    // saw us a second before it was crossed counts. same call and range as BlazeFight's scan
    private void noteUnsafe(AltoClef mod, GamerContext ctx) {
        if (foodTrip || foodTrips >= FoodTripRules.MAX_TRIPS) {
            return;
        }
        Player p = mod.getPlayer();
        if (FoodTripRules.unsafe(blazeSeesUs(mod, p), p.isOnFire(), p.getHealth())) {
            lastUnsafeTick = ctx.facts().gameTime();
        }
    }

    private static boolean blazeSeesUs(AltoClef mod, Player p) {
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return false;
        }
        for (Blaze blaze : blazes) {
            if (blaze.isAlive() && p.distanceTo(blaze) <= BlazeFightRules.SIGHT_RANGE
                    && LookHelper.seesPlayer(blaze, p, BlazeFightRules.SIGHT_RANGE)) {
                return true;
            }
        }
        return false;
    }

    // the walk home for food. the fight rules stay the combat chain's, this only picks the portal. no known home portal = today's behaviour
    private Task foodTripHome(AltoClef mod, GamerContext ctx) {
        double now = ctx.secondsInPhase();
        if (!foodTrip) {
            // the nether side of the pair is the way home (ReturnPhase.recordPortal keeps state.netherPortal), the overworld one is
            // where we come out
            boolean portalKnown = ctx.state().netherPortal != null
                    || mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER).isPresent();
            boolean deathTrip = ctx.state().netherTrip != null;
            FoodTripRules.Reason why = null;
            double portalDistance = portalDistance(mod, ctx);
            int units = FoodTripRules.units(ctx.food());
            // due is not the same as go: walking out of cover mid fight is how the last trip died, so it waits for a calm spell
            // unless the bag is nearly empty. waiting touches nothing (no trip, no budget, no clock), it just falls through. a
            // blocks trip is the same walk home (and tops the food up on the way), so it waits too and a food trip that is
            // waiting does not hand over to it
            long tick = ctx.facts().gameTime();
            if (FoodTripRules.goHome(units, portalDistance, portalKnown, deathTrip, foodTrips)) {
                if (FoodTripRules.mayStart(FoodTripRules.underFloor(units, portalDistance), tick, lastUnsafeTick)) {
                    why = FoodTripRules.Reason.FOOD;
                }
            } else if (FoodTripRules.goHomeForBlocks(FindNetherStructureTask.heldThrowaways(mod),
                    Math.max(findFortress.exhaustedEnds(), findWarped.exhaustedEnds()), portalKnown, deathTrip, blockTrips)
                    && FoodTripRules.mayStart(false, tick, lastUnsafeTick)) {
                why = FoodTripRules.Reason.BLOCKS;
            }
            if (why == null) {
                return null;
            }
            foodTrip = true;
            tripReason = why;
            tripBlocks = why == FoodTripRules.Reason.BLOCKS || FindNetherStructureTask.heldThrowaways(mod) < FoodTripRules.BLOCKS_LOW;
            if (tripBlocks) {
                // a new bag: the dry digs counted so far say nothing about what the next dig finds. a food trip that brings no
                // blocks keeps the count, the band is just as empty when we come back
                findFortress.clearExhaustedEnds();
                findWarped.clearExhaustedEnds();
            }
            foodTripUp = false;
            foodTripStart = now;
            goHome = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            if (why == FoodTripRules.Reason.FOOD) {
                foodTrips++;
                ctx.log("nether: out of food, going home for more");
                ctx.progress("food trip");
            } else {
                blockTrips++;
                ctx.log("nether: out of building blocks, going home for more");
                ctx.progress("blocks trip");
            }
        }
        if (FoodTripRules.tripOver(true, now - foodTripStart, FoodTripRules.MAX_SECONDS)) {
            // the walk home ate its whole clock: this portal is not getting us there (the walk crosses the same lava with the
            // same empty bag, so a blocks trip can end up here), no second try for this kind of trip
            foodTrip = false;
            if (blocksTrip()) {
                blockTrips = FoodTripRules.MAX_TRIPS;
            } else {
                foodTrips = FoodTripRules.MAX_TRIPS;
            }
            return null;
        }
        hudState = tripBlocks ? "Going home for blocks" : "Going home for food";
        return goHome;
    }

    private boolean blocksTrip() {
        return tripReason == FoodTripRules.Reason.BLOCKS;
    }

    // a respawn is a new body: whatever trip we were on went with the old one, wherever the death happened (the overworld half
    // included, it used to latch and walk the new body into a hunt it had no business in)
    private void noteRespawn(GamerContext ctx) {
        int deaths = ctx.state().deaths.size();
        if (deaths != foodDeathsSeen) {
            foodDeathsSeen = deaths;
            foodTrip = false;
            foodTripUp = false;
            foodFloor.reset();
            findFortress.clearExhaustedEnds();
            findWarped.clearExhaustedEnds();
        }
    }

    // a piglin close enough to be angered by a gold block breaking (NetherGold.angerBox): the whole box around the player, no sight
    // check, so one behind a wall counts. checked on the client's entity list, which is what we can see of it
    private static boolean piglinInBox(AltoClef mod) {
        Player p = mod.getPlayer();
        return !mod.getWorld().getEntitiesOfClass(Piglin.class, NetherGold.angerBox(p.getBoundingBox()), Entity::isAlive).isEmpty();
    }

    // while a piglin is that close, nothing may break gold ore as a side block of some other dig (the gravel, the way through a wall):
    // the hit angers all of them. blocksToDisallowBreaking and not the avoid list, which only makes a break ten times dearer and
    // lets it through when the way round is dearer still. a piglin on the edge of the box would flip the list (and the path tables
    // that copy it) every few ticks, so it stays on for a second after the last one. returns whether it is on
    private boolean guardGold(AltoClef mod) {
        if (piglinInBox(mod)) {
            guardHold = GUARD_HOLD_TICKS;
            if (!guarding) {
                for (Block b : GUARDED) {
                    baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.add(b);
                }
                guarding = true;
            }
        } else if (guarding && --guardHold <= 0) {
            unguard(mod);
        }
        return guarding;
    }

    private void unguard(AltoClef mod) {
        if (guarding) {
            for (Block b : GUARDED) {
                baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.remove(b);
            }
            guarding = false;
        }
    }

    // no gold piece on, and nothing to wear: piglins will jump us on sight, so make one out of nether gold ore. a try a visit (a few, see
    // NetherGold.mayRetry), each under the stall timer (NetherGold.CAP_SECONDS), and only from what the bag holds and ore the tracker already knows about.
    // a piglin in the box a block break would anger pauses the mining (guardGold keeps baritone off the ore meanwhile), the
    // crafts and the wearing go on. below the ghast cover and the other side jobs, it never walks into a fight
    private Task goldSide(AltoClef mod, GamerContext ctx, double now, boolean piglins) {
        GamerFacts f = ctx.facts();
        if (!ctx.cfg().overworld.goldSide) {
            return null;
        }
        int deaths = ctx.state().deaths.size();
        if (NetherGold.resetOnDeath(goldDeathsSeen, deaths)) {
            // a respawn is a new body with an empty bag, the visit's tries start over
            goldDeathsSeen = deaths;
            goldOver = false;
            goldOverAt = -1;
            goldStartedAt = -1;
            goldTask = null;
            goldWear = null;
            goldAnchor = null;
            goldTries = 0;
        }
        if (PiglinGold.worn(f)) {
            // not a latch: if the piece breaks or comes off there is a try waiting right away, not one 60 s out
            goldOver = false;
            goldTask = null;
            goldWear = null;
            return null;
        }
        if (PiglinGold.holdsPiece(f)) {
            List<Item> equip = new ArrayList<>();
            PiglinGold.addToEquip(f, equip);
            if (equip.isEmpty() || WearFromBag.held(mod, equip).isEmpty()) {
                return null;
            }
            if (goldWear == null || !equip.get(0).equals(goldWearing)) {
                goldWearing = equip.get(0);
                goldWear = WearFromBag.task(mod, equip);
            }
            hudState = "Putting on gold for piglins";
            return goldWear;
        }
        if (goldOver) {
            if (goldOverAt < 0) {
                goldOverAt = now;
            }
            // the latch is not for the whole visit: a wander, a dry anchor or the clock gets another go from where we stand now
            BlockPos here = mod.getPlayer().blockPosition();
            if (tickCounter % ORE_LOOK_TICKS != 0
                    || !NetherGold.mayRetry(now - goldOverAt, goldTries, NetherPick.hasUsablePick(f) && oreKnown(mod, here))) {
                return null;
            }
            goldOver = false;
            goldOverAt = -1;
            goldStartedAt = -1;
            goldTask = null;
        }
        if (goldStartedAt >= 0 && NetherGold.overTime(now - goldStartedAt)) {
            goldOver = true;
            goldTask = null;
            return null;
        }
        if (goldTask != null && goldTask.failed()) {
            goldOver = true;
            goldTask = null;
            return null;
        }
        // blazes and ghasts, a spawner, a barter and a pearl hunt come first, the same list the pick craft waits for
        if (pickBusy(mod, ctx, now)) {
            return null;
        }
        NetherGold.Bag bag = goldBag(f);
        if (goldTask == null) {
            String slot = PiglinGold.netherSlot(f, KitPlanner.armorNames(ctx.cfg().overworld));
            int need = PiglinGold.ingotsFor(slot);
            // only the digging angers them. a piece folded from what the bag already holds is the thing that calms the piglins
            // standing right there, waiting for them to leave first is backwards
            if (piglins && NetherGold.stage(bag, need) == NetherGold.Stage.MINE) {
                return null;
            }
            BlockPos me = mod.getPlayer().blockPosition();
            // the cheap answers first, the walk over the tracker's gold ore is twice a second at most. digging takes a pick the bag
            // already has (pickSide makes one from the bag, the mining task would go and fetch one, and there is nothing to fetch)
            if (NetherGold.stage(bag, need) == NetherGold.Stage.ABORT || tickCounter % ORE_LOOK_TICKS != 0
                    || !NetherGold.mayStart(bag, need, NetherPick.hasUsablePick(f) && oreKnown(mod, me))) {
                return null;
            }
            goldTask = new NetherGoldTask("golden_" + slot, PiglinGold.piece(slot), need, () -> goldBag(ctx.facts()), me);
            goldNeed = need;
            goldAnchor = me;
            goldTries++;
            goldStartedAt = now;
            oreLeft = true;
            lastNuggets = bag.nuggets();
        }
        if (NetherGold.stage(bag, goldNeed) == NetherGold.Stage.MINE) {
            if (!NetherPick.hasUsablePick(f)) {
                goldOver = true;
                goldTask = null;
                return null;
            }
            if (piglins) {
                // wait it out: the clock keeps running, the cap ends it
                return null;
            }
            if (tickCounter % ORE_LOOK_TICKS == 0) {
                oreLeft = oreKnown(mod, goldAnchor);
            }
            if (!oreLeft) {
                goldOver = true;
                goldTask = null;
                return null;
            }
        }
        if (bag.nuggets() > lastNuggets) {
            ctx.progress("nether gold nuggets");
        }
        lastNuggets = bag.nuggets();
        hudState = "Mining gold for piglins";
        return goldTask;
    }

    // a table in the bag or standing next to us. not the planner's memory of one back in the overworld
    private static NetherGold.Bag goldBag(GamerFacts f) {
        NetherPick.Bag nb = NetherPick.bagOf(f);
        boolean table = f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby();
        return new NetherGold.Bag(f.count(Items.GOLD_NUGGET), f.count(Items.GOLD_INGOT), nb.planks(), nb.logs(), table);
    }

    // nether gold ore the tracker already holds, still standing, within the try's reach of `from` (NetherGold.oreInReach)
    private static boolean oreKnown(AltoClef mod, BlockPos from) {
        for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.NETHER_GOLD_ORE)) {
            if (NetherGold.oreInReach(p.getX(), p.getY(), p.getZ(), from.getX(), from.getY(), from.getZ())
                    && mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) {
                return true;
            }
        }
        return false;
    }

    // no pick that works and the bag can make one right now: make it, here, before the next netherrack is dug by hand. only from
    // what the bag holds (NetherPick checks every input, the task is dropped the tick one is short), so this never turns into a
    // collection trip in a dimension with no cobble and no oaks. not at a spawner, not with a blaze or a ghast in sight, not while a
    // trade or a pearl hunt is going: the same reasons the gravel detour stays out. a fight is the combat chain's and never asks us
    private Task pickSide(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        if (NetherPick.hasUsablePick(f)) {
            pickTask = null;
            noPickSaid = false;
            return null;
        }
        if (pickTask != null && pickTask.failed()) {
            // stuck or an input went missing under us: leave it alone for a while, the sweep goes on with what it has
            pickTask = null;
            pickRetryAt = now + PICK_RETRY_SECONDS;
            if (++pickFails >= PICK_TRIES) {
                ctx.log("nether: the pickaxe craft keeps failing, giving up on it");
            }
        }
        if (pickFails >= PICK_TRIES) {
            return null;
        }
        if (pickTask != null) {
            // under way: the task reads the bag itself every tick (and the bag reads odd for a moment when the table goes down)
            if (pickBusy(mod, ctx, now)) {
                return null;
            }
            hudState = "Making a pickaxe";
            return pickTask;
        }
        NetherPick.Bag bag = NetherPick.bagOf(f);
        NetherPick.Tier tier = NetherPick.choose(bag);
        if (tier == NetherPick.Tier.NONE) {
            if (!noPickSaid) {
                noPickSaid = true;
                ctx.log(NetherPick.shortLine(bag));
            }
            return null;
        }
        noPickSaid = false;
        if (pickBusy(mod, ctx, now) || now < pickRetryAt) {
            return null;
        }
        pickTask = new NetherPickTask(tier, () -> NetherPick.bagOf(ctx.facts()), () -> NetherPick.hasUsablePick(ctx.facts()));
        ctx.log(NetherPick.craftLine(tier));
        hudState = "Making a pickaxe";
        return pickTask;
    }

    private boolean pickBusy(AltoClef mod, GamerContext ctx, double now) {
        boolean atSpawner = rodsTask != null && (rodsTask.currentSpawner() != null || rodsTask.isCampingSpawner());
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        boolean hunting = pearlsNext(ctx) && endermanHere;
        return atSpawner || trading || hunting || mod.getEntityTracker().entityFound(Blaze.class, Ghast.class);
    }

    // the gravel detour, between the ghast cover and the next step. a spawner we are on or a trade that is going wins outright
    private Task gravelSide(AltoClef mod, GamerContext ctx, double now) {
        boolean atSpawner = rodsTask != null && rodsTask.currentSpawner() != null;
        // barterTickAt is only put back when barterStep says no, and once the pearls are in nobody asks barterStep again. so a
        // trade is "going" only while it was ticked a moment ago, or one finished barter would keep gravel off for the phase
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        // the pearl hunt like a picked spawner: pearls are what nextStep is on and an enderman is in sight (angry or not, it is
        // what we walk at). asked every tick off the phase latch and not off a hunt tick, so one that shows up halfway through a detour ends it (it holds a few seconds after the ray loses it).
        // the wander with no enderman around is a walk, gravel on the way is fair there
        boolean hunting = pearlsNext(ctx) && endermanHere;
        if (!DetourSpec.netherMayDetour(ctx.facts().dimension() == Dimension.NETHER, atSpawner, trading, hunting)) {
            gravel.preempted(ctx.facts().gameTime());
            return null;
        }
        Task flint = gravel.tick(mod, ctx, null);
        if (flint != null) {
            hudState = gravel.hud();
        }
        return flint;
    }

    // back in the overworld (a regress from the stronghold, a death): walk to the portal we built first. the default task
    // only knows portals the block tracker has loaded and otherwise builds a brand new one, a thousand blocks from here
    // the portal we just came through is the way home, also when the one we remembered was gone and the default task built a
    // new one (PortalPhase only fills this in the first time)
    private static void rememberOverworldPortal(AltoClef mod, GamerContext ctx) {
        if (ctx.state().overworldPortal != null) {
            return;
        }
        mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD)
                .ifPresent(p -> {
                    ctx.state().overworldPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ());
                    ctx.save();
                });
    }

    private Task toTheNether(AltoClef mod, GamerContext ctx) {
        Task task = homeWalk.toTheNether(mod, ctx.state(), ctx::save);
        hudState = homeWalk.hud();
        return task;
    }

    private void ensureTracking(AltoClef mod) {
        if (!tracking) {
            mod.getBlockTracker().trackBlock(TRACKED);
            tracking = true;
        }
    }

    // what the player has really seen. one kind per scan, rotating: the seen filter has a raycast budget per tick and
    // the Nether is full of bricks behind rock, so three kinds in one tick would let the first starve the other two
    private void scanSights(AltoClef mod, GamerContext ctx) {
        Sight sight = Sight.values()[rotation++ % Sight.values().length];
        if (planner.scan(s -> seen(mod, s), sight)) {
            ctx.progress("saw something");
            ctx.save();
        }
    }

    // the nearest tracked blocks of this kind that are really still there and in line of sight. the tracker hands them
    // out in scan order and keeps unloaded ones, so sort by distance and check the block itself
    private static Optional<RunState.Pos> seen(AltoClef mod, Sight sight) {
        Block[] blocks = switch (sight) {
            case FORTRESS -> FORTRESS;
            case BASTION -> BASTION;
            case WARPED -> WARPED;
        };
        List<BlockPos> near = StrongholdScan.nearest(mod.getBlockTracker().getKnownLocations(blocks),
                mod.getPlayer().blockPosition(), SCAN_CANDIDATES);
        for (BlockPos p : near) {
            BlockState state = mod.getWorld().getBlockState(p);
            if (isOneOf(state, blocks) && SeenFilter.isSeen(mod, p)) {
                return Optional.of(new RunState.Pos(p.getX(), p.getY(), p.getZ()));
            }
        }
        return Optional.empty();
    }

    private static boolean isOneOf(BlockState state, Block[] blocks) {
        for (Block b : blocks) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    // nextStep would go for pearls: the rods are in (or given up) and pearls are still short. same sums nextStep makes
    private static boolean pearlsNext(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        int eyes = EyeMath.eyes(f);
        int goal = EyeMath.targetGoal(ctx.cfg(), ctx.state().framesFilled);
        boolean rodsDone = f.count(Items.BLAZE_ROD) >= EyeMath.rodsNeeded(goal, eyes, f.count(Items.BLAZE_POWDER)) || ctx.state().netherRodsGaveUp;
        return rodsDone && f.count(Items.ENDER_PEARL) < EyeMath.pearlsNeeded(goal, eyes);
    }

    private Task nextStep(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        RunState s = ctx.state();
        GamerConfig cfg = ctx.cfg();
        int eyes = EyeMath.eyes(f);
        int goal = EyeMath.targetGoal(cfg, s.framesFilled);
        int rodsNeed = EyeMath.rodsNeeded(goal, eyes, f.count(Items.BLAZE_POWDER));
        int rods = f.count(Items.BLAZE_ROD);
        if (rods < rodsNeed && !s.netherRodsGaveUp) {
            return rodsStep(mod, ctx, now, rods, rodsNeed);
        }
        int pearlsNeed = EyeMath.pearlsNeeded(goal, eyes);
        int pearls = f.count(Items.ENDER_PEARL);
        if (pearls < pearlsNeed) {
            return pearlStep(mod, ctx, now, pearls, pearlsNeed);
        }
        if (rods < rodsNeed) {
            return fail(ctx, "only " + rods + " of " + rodsNeed + " blaze rods");
        }
        hudState = null;
        return null;
    }

    // ---- rods

    private Task rodsStep(AltoClef mod, GamerContext ctx, double now, int rods, int rodsNeed) {
        RunState s = ctx.state();
        if (s.fortress.isEmpty()) {
            return findStep(ctx, findFortress, "Looking for a fortress");
        }
        if (rodsTask == null || rodsTaskTarget != rodsNeed) {
            rodsTask = new CollectBlazeRodsTask(rodsNeed);
            rodsTaskTarget = rodsNeed;
        }
        boolean camping = rodsTask.isCampingSpawner();
        BlockPos camped = rodsTask.currentSpawner();
        if (camped != null) {
            // the one the task picked, not whatever the tracker calls nearest: they are not always the same spawner
            RunState.Pos mine = new RunState.Pos(camped.getX(), camped.getY(), camped.getZ());
            if (!mine.equals(s.spawner)) {
                s.spawner = mine;
                rodsWatch.spawnerChanged();
                ctx.save();
            }
        }
        RodsWatch.Verdict verdict = rodsWatch.update(now, rods, camping, camped != null);
        if (verdict == RodsWatch.Verdict.GIVE_UP_RODS) {
            s.netherRodsGaveUp = true;
            ctx.log("blaze rods are taking too long, moving on with " + rods);
            ctx.save();
            return null;
        }
        if (verdict == RodsWatch.Verdict.GIVE_UP_SPAWNER) {
            return giveUpSpawner(mod, ctx, now);
        }
        // hiding from or walking out to blazes at our spawner can stand still for a while too, and the rods watch has its
        // own clocks for a spawner that goes nowhere, so that is not a stall either. a stuck walk to the spawner still is
        if (campPinger.ping(now, camping || (camped != null && rodsTask.isBusyWithBlazes()))) {
            ctx.progress("waiting at the blaze spawner");
        }
        Task toFortress = walkToKnownFortress(mod, s, now);
        if (toFortress != null) {
            hudState = "Walking to the fortress";
            return toFortress;
        }
        // standing still at a spawner looks exactly like a frozen bot, so say what we're doing
        // and "waiting to spawn" with six blazes in view reads like a bug, so say which kind of waiting it is
        hudState = rodsHud(camping, rodsTask.blazesAround()) + " (" + rods + "/" + rodsNeed + ")";
        return rodsTask;
    }

    private static String rodsHud(boolean camping, int blazesAround) {
        if (!camping) return "Collecting blaze rods";
        return blazesAround > 0 ? "Waiting for blazes to come in reach" : "Waiting for blazes to spawn";
    }

    // a fortress we saw (an earlier visit, a relog, the next one after a dud spawner) and are nowhere near: the rods task
    // only wanders to whatever bricks are closest, so go to the one we know first. bounded, a fortress behind a lava sea
    // gets the wander instead of this walk for ever
    private Task walkToKnownFortress(AltoClef mod, RunState s, double now) {
        if (rodsTask.currentSpawner() != null || s.fortress.isEmpty()) {
            return null;
        }
        RunState.Pos spot = s.fortress.get(0);
        if (spot.equals(fortressWalkGivenUp)) {
            return null;
        }
        if (!spot.equals(fortressWalkTo)) {
            fortressWalkTo = spot;
            fortressWalkSince = now;
            walkToFortress = new GetToXZTask(spot.x, spot.z);
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        boolean there = dx * dx + dz * dz <= FORTRESS_NEAR_BLOCKS * FORTRESS_NEAR_BLOCKS;
        if (there || now - fortressWalkSince > FORTRESS_WALK_SECONDS) {
            fortressWalkGivenUp = spot;
            return null;
        }
        return walkToFortress;
    }

    // the rods task remembers its spawner and would walk straight back to it, so the dud gets blacklisted in the block
    // tracker and the task is thrown away
    private Task giveUpSpawner(AltoClef mod, GamerContext ctx, double now) {
        RunState s = ctx.state();
        ctx.log("no rods from this spawner, looking for another fortress");
        // the spawner the task was really camping first, the saved one can be a stale pick from before it re-searched
        BlockPos dud = rodsTask != null ? rodsTask.currentSpawner() : null;
        if (dud == null) {
            dud = s.spawner != null ? new BlockPos(s.spawner.x, s.spawner.y, s.spawner.z)
                    : mod.getBlockTracker().getNearestTracking(Blocks.SPAWNER).orElse(null);
        }
        if (dud != null) {
            mod.getBlockTracker().requestBlockUnreachable(dud, 0);
        }
        // only the fortress this spawner is in, a second one we saw is the next place to try
        planner.giveUpFortress(dud == null ? null : new RunState.Pos(dud.getX(), dud.getY(), dud.getZ()));
        fortressWalkGivenUp = null;
        fortressWalkTo = null;
        rodsTask = null;
        rodsWatch.spawnerGivenUp(now);
        ctx.save();
        return findStep(ctx, findFortress, "Looking for a fortress");
    }

    // ---- pearls

    private Task pearlStep(AltoClef mod, GamerContext ctx, double now, int pearls, int pearlsNeed) {
        Task barter = barterStep(mod, ctx, now, pearls, pearlsNeed);
        if (barter != null) {
            return barter;
        }
        RunState s = ctx.state();
        if (s.warpedForest == null && !endermanHere && !warpedSearchOver) {
            if (findWarped.failure() != null) {
                // swept what we are allowed to sweep, hunt whatever wanders by
                warpedSearchOver = true;
            } else {
                return findStep(ctx, findWarped, "Looking for a warped forest");
            }
        }
        return huntStep(mod, ctx, now, pearlsNeed);
    }

    private Task huntStep(AltoClef mod, GamerContext ctx, double now, int pearlsNeed) {
        RunState s = ctx.state();
        if (hunt == null || huntTarget != pearlsNeed) {
            hunt = new PearlHuntTask(pearlsNeed, Dimension.NETHER, () -> s.warpedForest,
                    () -> ctx.progress("enderman down"));
            huntTarget = pearlsNeed;
        }
        int pearls = ctx.facts().count(Items.ENDER_PEARL);
        if (huntWanderSince < 0 || pearls > lastPearls) {
            huntWanderSince = now;
        }
        lastPearls = pearls;
        if (s.warpedForest == null) {
            if (now - huntWanderSince > ctx.cfg().nether.huntWanderMinutes * 60) {
                return fail(ctx, "no endermen and no warped forest in the Nether");
            }
        }
        hudState = hunt.step();
        return hunt;
    }

    // gold already in the bag and a piglin already in view: worth a few minutes. never walks into a bastion for it and
    // never opens a chest or breaks gold, TradeWithPiglinsTask only talks to piglins
    private Task barterStep(AltoClef mod, GamerContext ctx, double now, int pearls, int pearlsNeed) {
        NetherConfig n = ctx.cfg().nether;
        GamerFacts f = ctx.facts();
        int missing = pearlsNeed - pearls;
        int goldWanted = n.barterGoldPerPearl * (n.pearlSource == NetherConfig.PearlSource.BARTER ? 1 : missing);
        // v1: only when asked for. AUTO is endermen, the gold for a barter run rarely exists and the boots swap puts the
        // iron boots in the bag where nothing puts them back
        boolean goodToGo = n.pearlSource == NetherConfig.PearlSource.BARTER
                && barterSpent < n.barterMinutes * 60
                && f.count(Items.GOLD_INGOT) >= goldWanted + (PiglinGold.worn(f) || f.count(Items.GOLDEN_BOOTS) > 0 ? 0 : 4)
                && mod.getEntityTracker().entityFound(Piglin.class);
        if (!goodToGo) {
            barterTickAt = -1;
            return null;
        }
        if (barterTickAt >= 0) {
            barterSpent += Math.min(now - barterTickAt, 1.0);
        }
        barterTickAt = now;
        // piglins ignore us while ANY gold piece is on, the helmet from the kit counts as much as boots do
        if (!PiglinGold.worn(f)) {
            if (boots == null) {
                boots = new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            hudState = "Putting on golden boots";
            return boots;
        }
        if (trade == null || tradeTarget != pearlsNeed) {
            trade = new TradeWithPiglinsTask(goldWanted, Items.ENDER_PEARL, pearlsNeed);
            tradeTarget = pearlsNeed;
        }
        hudState = "Trading with piglins";
        return trade;
    }

    // ---- shared

    private Task findStep(GamerContext ctx, FindNetherStructureTask task, String hud) {
        boolean fortress = task == findFortress;
        int cells = planner.cellsStarted(fortress ? Goal.FORTRESS : Goal.WARPED);
        if (cells != (fortress ? lastFortressCells : lastWarpedCells)) {
            ctx.progress("new search cell");
            if (fortress) {
                lastFortressCells = cells;
            } else {
                lastWarpedCells = cells;
            }
        }
        String failure = task.failure();
        if (failure != null && fortress) {
            return fail(ctx, "no fortress: " + failure);
        }
        hudState = hud;
        return task;
    }

    // one report per attempt, the engine decides what happens next (and onEnter resets this)
    private Task fail(GamerContext ctx, String reason) {
        if (!failedAlready) {
            failedAlready = true;
            ctx.fail(reason);
        }
        return null;
    }

    // two fireball hits inside the window: stop standing in the open and put some distance between us and the ghast
    private Task ghastCover(AltoClef mod, double now) {
        Player p = mod.getPlayer();
        boolean freshHit = p.hurtTime > lastHurt;
        lastHurt = p.hurtTime;
        if (freshHit && hitByGhast(p) && ghastWatch.onHit(now)) {
            Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(Ghast.class);
            BlockPos from = ghast.map(Entity::blockPosition).orElse(p.blockPosition());
            flee = new RunAwayFromPositionTask(FLEE_BLOCKS, from);
            fleeUntil = now + FLEE_SECONDS;
        }
        if (flee != null && now < fleeUntil) {
            hudState = "Dodging a ghast";
            return flee;
        }
        return null;
    }

    private static boolean hitByGhast(Player p) {
        DamageSource source = p.getLastDamageSource();
        return source != null && (source.getEntity() instanceof Ghast || source.getDirectEntity() instanceof LargeFireball);
    }
}
