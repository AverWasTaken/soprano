package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.gamer.EyeMath;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.CampPinger;
import adris.altoclef.tasks.speedrun.gamer.tasks.FindNetherStructureTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.GhastWatch;
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
    // close enough to the remembered overworld portal that the tracker can see it and the default task takes over
    private static final double PORTAL_NEAR_BLOCKS = 8;
    private static final double FLEE_SECONDS = 10;
    private static final int FLEE_BLOCKS = 48;

    private NetherSweepPlanner planner;
    private RodsWatch rodsWatch;
    private CampPinger campPinger;
    private GhastWatch ghastWatch;
    private FindNetherStructureTask findFortress;
    private FindNetherStructureTask findWarped;
    private CollectBlazeRodsTask rodsTask;
    private int rodsTaskTarget;
    private PearlHuntTask hunt;
    private int huntTarget;
    private Task goNether;
    private GetToBlockTask walkToPortal;
    private RunState.Pos walkingTo;
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
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return EyeMath.canLeaveNether(facts, cfg, budgetOver(facts, state, cfg) || state.netherRodsGaveUp);
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
        if (EyeMath.canLeaveNether(ctx.facts(), ctx.cfg(), true)) {
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
        findFortress = new FindNetherStructureTask(planner, Goal.FORTRESS, ctx::secondsInPhase);
        findWarped = new FindNetherStructureTask(planner, Goal.WARPED, ctx::secondsInPhase);
        goNether = new DefaultGoToDimensionTask(Dimension.NETHER);
        walkToPortal = null;
        walkingTo = null;
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
        ensureTracking(mod);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
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
            return toTheNether(mod, ctx.state());
        }
        ensureTracking(mod);
        ReturnPhase.recordPortal(mod, ctx.state());
        double now = ctx.secondsInPhase();
        Task cover = ghastCover(mod, now);
        if (cover != null) {
            return cover;
        }
        if (tickCounter++ % SCAN_EVERY_TICKS == 0) {
            scanSights(mod, ctx);
        }
        return nextStep(mod, ctx, now);
    }

    // back in the overworld (a regress from the stronghold, a death): walk to the portal we built first. the default task
    // only knows portals the block tracker has loaded and otherwise builds a brand new one, a thousand blocks from here
    private Task toTheNether(AltoClef mod, RunState state) {
        RunState.Pos home = state.overworldPortal;
        if (home != null && !portalInView(mod)) {
            BlockPos at = new BlockPos(home.x, home.y, home.z);
            if (!at.closerToCenterThan(mod.getPlayer().position(), PORTAL_NEAR_BLOCKS)) {
                if (!home.equals(walkingTo)) {
                    walkingTo = home;
                    walkToPortal = new GetToBlockTask(at);
                }
                hudState = "Walking back to the portal";
                return walkToPortal;
            }
        }
        hudState = "Heading to the Nether";
        return goNether;
    }

    private static boolean portalInView(AltoClef mod) {
        return mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD).isPresent()
                || mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL);
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

    private Task nextStep(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        RunState s = ctx.state();
        GamerConfig cfg = ctx.cfg();
        int eyes = EyeMath.eyes(f);
        int rodsNeed = EyeMath.rodsNeeded(cfg.targetEyes, eyes, f.count(Items.BLAZE_POWDER));
        int rods = f.count(Items.BLAZE_ROD);
        if (rods < rodsNeed && !s.netherRodsGaveUp) {
            return rodsStep(mod, ctx, now, rods, rodsNeed);
        }
        int pearlsNeed = EyeMath.pearlsNeeded(cfg.targetEyes, eyes);
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
        if (camping && s.spawner == null) {
            mod.getBlockTracker().getNearestTracking(Blocks.SPAWNER)
                    .ifPresent(p -> s.spawner = new RunState.Pos(p.getX(), p.getY(), p.getZ()));
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
        hudState = "Collecting Blaze Rods";
        return rodsTask;
    }

    // the rods task remembers its spawner and would walk straight back to it, so the dud gets blacklisted in the block
    // tracker and the task is thrown away
    private Task giveUpSpawner(AltoClef mod, GamerContext ctx, double now) {
        RunState s = ctx.state();
        ctx.log("no rods from this spawner, looking for another fortress");
        BlockPos dud = s.spawner != null ? new BlockPos(s.spawner.x, s.spawner.y, s.spawner.z)
                : mod.getBlockTracker().getNearestTracking(Blocks.SPAWNER).orElse(null);
        if (dud != null) {
            mod.getBlockTracker().requestBlockUnreachable(dud, 0);
        }
        planner.giveUpFortress();
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
        boolean endermanHere = mod.getEntityTracker().entityFound(EnderMan.class);
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
                && f.count(Items.GOLD_INGOT) >= goldWanted + (f.count(Items.GOLDEN_BOOTS) > 0 ? 0 : 4)
                && mod.getEntityTracker().entityFound(Piglin.class);
        if (!goodToGo) {
            barterTickAt = -1;
            return null;
        }
        if (barterTickAt >= 0) {
            barterSpent += Math.min(now - barterTickAt, 1.0);
        }
        barterTickAt = now;
        // piglins ignore us only while the boots are on
        if (!f.armorEquipped(Items.GOLDEN_BOOTS)) {
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
