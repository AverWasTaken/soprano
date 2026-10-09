package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.gamer.DetourSpec;
import adris.altoclef.tasks.speedrun.gamer.EyeMath;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
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
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.tasks.speedrun.gamer.tasks.PearlHuntTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.RodsWatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SeenFilter;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.WARPED_NYLIUM};

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
    // flint from soul sand valley gravel, only while it is still owed (no flint and steel, fire charge or flint on us)
    private final ResourceDetour gravel = ResourceDetour.gravel();
    // a barter ticked this recently is still going (see gravelSide)
    private static final double TRADE_FRESH_SECONDS = 1;
    private Task boots;
    private TradeWithPiglinsTask trade;
    private int tradeTarget;

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
        rodsWatch = new RodsWatch(n.spawnerCampMinutes * 60, n.rodsBudgetMinutes * 60);
        campPinger = new CampPinger(CAMP_PING_SECONDS);
        ghastWatch = new GhastWatch(2, n.ghastWindowSeconds);
        endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);
        walkToFortress = null;
        fortressWalkTo = null;
        fortressWalkGivenUp = null;
        findFortress = new FindNetherStructureTask(planner, Goal.FORTRESS, ctx::secondsInPhase);
        findWarped = new FindNetherStructureTask(planner, Goal.WARPED, ctx::secondsInPhase);
        homeWalk.reset();
        rodsTask = null;
        hunt = null;
        trade = null;
        boots = null;
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
        homeWalk.stopTracking(mod);
        gravel.reset();
        if (tracking) {
            mod.getBlockTracker().stopTracking(TRACKED);
            tracking = false;
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (planner == null) {
            onEnter(mod, ctx);
        }
        if (ctx.facts().dimension() != Dimension.NETHER) {
            return toTheNether(mod, ctx);
        }
        homeWalk.stopTracking(mod);
        ensureTracking(mod);
        ReturnPhase.recordPortal(mod, ctx.state());
        rememberOverworldPortal(mod, ctx);
        double now = ctx.secondsInPhase();
        Task cover = ghastCover(mod, now);
        if (cover != null) {
            gravel.preempted(ctx.facts().gameTime());
            return cover;
        }
        if (tickCounter++ % SCAN_EVERY_TICKS == 0) {
            scanSights(mod, ctx);
        }
        Task flint = gravelSide(mod, ctx, now);
        if (flint != null) {
            return flint;
        }
        return nextStep(mod, ctx, now);
    }

    // the gravel detour, between the ghast cover and the next step. a spawner we are on or a trade that is going wins outright
    private Task gravelSide(AltoClef mod, GamerContext ctx, double now) {
        boolean atSpawner = rodsTask != null && rodsTask.currentSpawner() != null;
        // barterTickAt is only put back when barterStep says no, and once the pearls are in nobody asks barterStep again. so a
        // trade is "going" only while it was ticked a moment ago, or one finished barter would keep gravel off for the phase
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        // the pearl hunt like a picked spawner: pearls are what nextStep is on and an enderman is in sight (angry or not, it is
        // what we walk at). asked fresh every tick and not off a hunt tick, so one that shows up halfway through a detour ends it.
        // the wander with no enderman around is a walk, gravel on the way is fair there
        boolean hunting = pearlsNext(ctx) && mod.getEntityTracker().entityFound(EnderMan.class);
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
        if (camping && camped != null) {
            // the one the task picked, not whatever the tracker calls nearest: they are not always the same spawner
            RunState.Pos mine = new RunState.Pos(camped.getX(), camped.getY(), camped.getZ());
            if (!mine.equals(s.spawner)) {
                s.spawner = mine;
                ctx.save();
            }
        }
        RodsWatch.Verdict verdict = rodsWatch.update(now, rods, camping);
        if (verdict == RodsWatch.Verdict.GIVE_UP_RODS) {
            s.netherRodsGaveUp = true;
            ctx.log("blaze rods are taking too long, moving on with " + rods);
            ctx.save();
            return null;
        }
        if (verdict == RodsWatch.Verdict.GIVE_UP_SPAWNER) {
            return giveUpSpawner(mod, ctx, now);
        }
        if (campPinger.ping(now, camping)) {
            ctx.progress("waiting at the blaze spawner");
        }
        Task toFortress = walkToKnownFortress(mod, s, now);
        if (toFortress != null) {
            hudState = "Walking to the fortress";
            return toFortress;
        }
        // standing still at a spawner looks exactly like a frozen bot, so say what we're doing
        hudState = (camping ? "Waiting for blazes to spawn" : "Collecting blaze rods") + " (" + rods + "/" + rodsNeed + ")";
        return rodsTask;
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
        boolean endermanHere = endermanLatch.update(mod.getEntityTracker().entityFound(EnderMan.class), now);
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
            hunt = new PearlHuntTask(pearlsNeed, Dimension.NETHER, () -> s.warpedForest);
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
