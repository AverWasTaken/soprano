package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Inv;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Kind;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Scoop;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Step;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import baritone.cache.FasterWorldScanner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// the lava bucket portal (see LPortalPlan for the picture). this class is the hands: it asks the plan what comes next
// from nothing but what the world looks like, does that one step, and asks again. a fight or a snack in the middle
// just means the next tick reads the world and carries on.
//
// it only ever makes the portal faster. anything that goes wrong (no site, a cell that won't cast, the clock) ends in
// failed(), and the phase falls back to the old per block cast
public class LPortalTask extends Task {
    // the search walks to lava and wanders when there is none. walks and legs of 6+ blocks feed the phase's 120 s stall clock,
    // waiting and a stuck wander don't (that ends in the phase's free retry instead of this cap). it counts our own ticks and
    // not the wall clock, so a snack or a fight in the middle doesn't eat it
    private static final int SEARCH_TICKS = 20 * 300;
    private static final int SCAN_GAP = 20 * 3;
    private static final int WANDER_LEG = 64;
    // the finder's source cap, a big lake is a lot of cells and each is 16 layouts
    private static final int FINDER_SOURCES = 80;
    // the lake around the seed we walked to, read live, because the scan only keeps a few cells of each chunk
    private static final int NEAR_SEED = 6;
    private static final int FINDER_REACH = 90;
    private static final int BUILD_TICKS = 20 * 85;
    private static final int CLEANUP_TICKS = 20 * 8;
    private static final int SCOOP_MISSES = 12;
    // one step that does not change the world for this long is stuck
    private static final int STEP_TICKS = 20 * 14;
    // the water has this long to show up next to a cell. the fall from the source and the spill into the pit take seconds
    private static final int WAIT_TICKS = 20 * 12;
    // strikes are per cell, one bad cell shouldn't use up the budget of the others
    private static final int CELL_STRIKES = 3;
    private static final int REACH_TICKS = 20;
    private static final int BLOCKED_READS = 5;
    private static final int CLICK_GAP = 8;

    private int tick;
    private int searchStart = -1;
    private int buildStart = -1;
    private Layout layout;
    private List<Layout> pending = new ArrayList<>();
    private int pendingAt;
    private long lastScan = -100000;
    private String failReason;
    // why we are giving up, while the loose water and lava come back in the buckets first. failReason is set once that is done
    private String failing;
    private int failingSince;
    // lava sources we could not scoop from where we stood, and how many times that happened
    private final Set<P> badSources = new HashSet<>();
    private int scoopMisses;
    private boolean finished;
    private String stepKey = "";
    private int stepSince;
    private final Map<String, Integer> strikes = new HashMap<>();
    private TimeoutWanderTask unstick;
    private int lastClickTick = -100;
    // the click we hand out from a stand, kept as the same object so its click status is the one it really had last tick
    private InteractWithBlockTask reachChild;
    private String reachKey = "";
    private int reachLastTick = -1;
    private String standKey = "";
    private int standSince;
    private boolean reachClicked;
    private int blockedReads;
    private String lastDebug = "";
    private List<P> lavaScan;
    private int lavaScanTick;
    // the lava hunt. the scans run on the baritone executor and hand their seeds over through the volatiles, never on the tick
    private final LavaHunt hunt = new LavaHunt();
    private volatile List<P> bandSeeds = List.of();
    private volatile List<P> deepSeeds = List.of();
    private volatile boolean scanning;
    private volatile boolean bandScanned;
    private int lastKick = -100000;
    private int finderOpen;
    private TimeoutWanderTask wander;
    private String huntHud = "";

    public String failReason() {
        return failReason;
    }

    public boolean failed() {
        return failReason != null;
    }

    public boolean searching() {
        return layout == null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
        // the walls and the cast frame are the whole point, nothing gets to path through them
        mod.getBehaviour().avoidBlockBreaking(p -> layout != null && (isWall(p) || isCastMade(mod, p)));
        // the cells the water and the frame need empty: no scaffolding in them
        mod.getBehaviour().avoidBlockPlacing(p -> layout != null && isReserved(p));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
    }

    private boolean isWall(BlockPos p) {
        if (layout.cell(LPortalPlan.ARM).equals(toP(p))) {
            return true;
        }
        for (int[] c : LPortalPlan.WALLS) {
            if (layout.cell(c).equals(toP(p))) {
                return true;
            }
        }
        return false;
    }

    private boolean isCastMade(AltoClef mod, BlockPos p) {
        boolean obsidian = mod.getWorld().getBlockState(p).getBlock() == Blocks.OBSIDIAN;
        if (obsidian && (layout.cell(LPortalPlan.CORNER_LEFT).equals(toP(p)) || layout.cell(LPortalPlan.CORNER_RIGHT).equals(toP(p))
                || layout.cell(LPortalPlan.PLATFORM_LEFT).equals(toP(p)) || layout.cell(LPortalPlan.PLATFORM_RIGHT).equals(toP(p)))) {
            return true;
        }
        for (LPortalPlan.Round round : LPortalPlan.ROUNDS) {
            for (LPortalPlan.Cast c : round.casts()) {
                if (obsidian && layout.cell(c.t()).equals(toP(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isReserved(BlockPos p) {
        P q = toP(p);
        for (int[] c : LPortalPlan.keepClear()) {
            if (layout.cell(c).equals(q)) {
                return true;
            }
        }
        return layout.cell(LPortalPlan.BASE_WATER).equals(q);
    }

    private static Direction faceOf(int dx, int dy, int dz) {
        for (Direction d : Direction.values()) {
            if (d.getUnitVec3i().getX() == dx && d.getUnitVec3i().getY() == dy && d.getUnitVec3i().getZ() == dz) {
                return d;
            }
        }
        throw new IllegalArgumentException("not a face: " + dx + " " + dy + " " + dz);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static P toP(BlockPos p) {
        return new P(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos toPos(P p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        tick++;
        if (failReason != null || finished) {
            return null;
        }
        if (failing != null) {
            return cleanUp(mod);
        }
        if (unstick != null && unstick.isActive() && !unstick.isFinished(mod)) {
            setDebugState("shaking loose");
            return unstick;
        }
        if (layout == null) {
            return search(mod);
        }
        if (tick - buildStart > BUILD_TICKS) {
            return giveUp("ran out of time");
        }
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        List<P> lava = lavaCells(mod);
        var eyes = mod.getPlayer().getEyePosition(1.0F);
        Scoop spot = LPortalPlan.scoopSpot(terrain, lava, layout, eyes.x, eyes.y, eyes.z, badSources);
        int empty = mod.getItemStorage().getItemCount(Items.BUCKET);
        Inv inv = new Inv(mod.getItemStorage().getItemCount(Items.LAVA_BUCKET), mod.getItemStorage().hasItem(Items.WATER_BUCKET), empty,
                blocksInBag(mod), spot != null);
        Step step = LPortalPlan.next(layout, terrain, inv);
        lastDebug = step.toString();
        watchdog(step, spot);
        if (failing != null) {
            return null;
        }
        setDebugState(step.toString());
        return doStep(mod, step, spot);
    }

    // the first throwaway block we are holding
    private static Item stackBlock(AltoClef mod) {
        for (Item i : mod.getClientBaritoneSettings().acceptableThrowawayItems.value) {
            if (mod.getItemStorage().getItemCount(i) > 0) {
                return i;
            }
        }
        return null;
    }

    private static int blocksInBag(AltoClef mod) {
        int n = 0;
        for (Item i : mod.getClientBaritoneSettings().acceptableThrowawayItems.value) {
            n += mod.getItemStorage().getItemCount(i);
        }
        return n;
    }

    // once the build is on its own we read the row layer around it ourselves, the scan's seeds are only a few cells per chunk
    private List<P> lavaCells(AltoClef mod) {
        if (layout != null) {
            if (tick - lavaScanTick >= 8 || lavaScan == null) {
                lavaScanTick = tick;
                lavaScan = new ArrayList<>();
                P mid = layout.cell(1, 0, 0);
                for (int x = mid.x() - LPortalPlan.SCAN; x <= mid.x() + LPortalPlan.SCAN; x++) {
                    for (int z = mid.z() - LPortalPlan.SCAN; z <= mid.z() + LPortalPlan.SCAN; z++) {
                        BlockPos p = new BlockPos(x, mid.y(), z);
                        if (mod.getWorld().isLoaded(p) && mod.getWorld().getBlockState(p).is(Blocks.LAVA)) {
                            lavaScan.add(toP(p));
                        }
                    }
                }
            }
            return lavaScan;
        }
        return List.of();
    }

    // ---- finding the spot ----

    private Task search(AltoClef mod) {
        if (searchStart < 0) {
            searchStart = tick;
        }
        if (tick - searchStart > SEARCH_TICKS) {
            return giveUp("no 4 long lava row with a shore");
        }
        kickScan(mod);
        BlockPos me = mod.getPlayer().blockPosition();
        List<P> seeds = new ArrayList<>(bandSeeds);
        seeds.addAll(deepSeeds);
        List<P> usable = hunt.usable(seeds, me.getX(), me.getY(), me.getZ());
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        if (tick - lastScan > 40 && pendingAt >= pending.size()) {
            lastScan = tick;
            hunt.passStarted();
            finderOpen = hunt.pass();
            // the lake we walked to first, then the seeds cheapest first. the terrain is read live, so a seed that
            // stopped being lava drops out here
            Set<P> found = new LinkedHashSet<>();
            P target = hunt.target();
            if (target != null && LavaHunt.arrived(target, me.getX(), me.getY(), me.getZ())) {
                for (int dx = -NEAR_SEED; dx <= NEAR_SEED; dx++) {
                    for (int dz = -NEAR_SEED; dz <= NEAR_SEED; dz++) {
                        found.add(new P(target.x() + dx, target.y(), target.z() + dz));
                    }
                }
            }
            // seeds near us only, a row that fits 60 blocks down or 300 away would start the build clock a long walk from the
            // site. the lake we walked to gets its own grid above once we are standing at it
            for (P p : usable) {
                if (Math.abs(p.y() - me.getY()) <= LavaHunt.BAND && sq(p.x() - me.getX()) + sq(p.z() - me.getZ()) <= FINDER_REACH * FINDER_REACH) {
                    found.add(p);
                }
            }
            List<P> sources = new ArrayList<>();
            for (P p : found) {
                if (terrain.at(p.x(), p.y(), p.z()) == Mat.LAVA && terrain.at(p.x(), p.y() + 1, p.z()) == Mat.AIR) {
                    sources.add(p);
                    if (sources.size() >= FINDER_SOURCES) {
                        break;
                    }
                }
            }
            pending = LPortalPlan.candidates(terrain, sources, me.getX(), me.getZ());
            pendingAt = 0;
        }
        // a few a tick, each full check is a couple hundred reads
        for (int i = 0; i < 6 && pendingAt < pending.size(); i++) {
            Layout l = pending.get(pendingAt++);
            if (LPortalPlan.fits(l, terrain)) {
                layout = l;
                buildStart = tick;
                return null;
            }
        }
        if (finderOpen > 0 && pendingAt >= pending.size()) {
            hunt.passDone(finderOpen);
            finderOpen = 0;
        }
        switch (hunt.decide(seeds, me.getX(), me.getY(), me.getZ(), tick)) {
            case WALK -> {
                wander = null;
                huntHud = "Going to lava";
                setDebugState("walking to lava " + hunt.target());
                // near the lava cell and never on it, a goal inside lava is a goal baritone refuses
                return new GetWithinRangeOfBlockTask(toPos(hunt.target()).above(), 4);
            }
            case WAIT -> {
                wander = null;
                huntHud = "Looking for a lava row";
                setDebugState("looking for a lava row");
                return null;
            }
            default -> {
                huntHud = "Looking for lava";
                if (wander != null && wander.isActive() && wander.isFinished(mod)) {
                    hunt.legDone(bandScanned, usable.isEmpty());
                    wander = null;
                }
                if (wander == null) {
                    wander = new TimeoutWanderTask(WANDER_LEG);
                }
                setDebugState("no lava in sight, wandering");
                return wander;
            }
        }
    }

    // the lava seeds come from a scan of everything loaded, off the tick. the band scan is the normal one, the deep one
    // (no height limit) only runs once the band came up empty and wandering didn't help
    private void kickScan(AltoClef mod) {
        if (scanning || tick - lastKick < SCAN_GAP) {
            return;
        }
        lastKick = tick;
        scanning = true;
        int py = mod.getPlayer().blockPosition().getY();
        boolean deep = hunt.deepAllowed();
        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Baritone.getExecutor().execute(() -> {
            try {
                bandSeeds = scanSeeds(ctx, py, true);
                bandScanned = true;
                if (deep) {
                    deepSeeds = scanSeeds(ctx, py, false);
                }
            } catch (RuntimeException e) {
                // a chunk going away under the scan. the next one will do
            } finally {
                scanning = false;
            }
        });
    }

    private static List<P> scanSeeds(IPlayerContext ctx, int py, boolean banded) {
        // the cap is counted in the filter, the scanner stops a chunk at max so a count afterwards is too late
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        List<BlockPos> hits = FasterWorldScanner.INSTANCE.scanNearest(ctx, new BlockOptionalMetaLookup(Blocks.LAVA), LavaHunt.SEED_MAX,
                BlockTracker.FLUID_SCAN_CHUNK_RADIUS, (chunk, x, y, z) -> {
                    if (banded && Math.abs(y - py) > LavaHunt.BAND) {
                        return false;
                    }
                    // a full chunk is the cheap no, before the fluid reads
                    if (cap.full(x, z)) {
                        return false;
                    }
                    return FluidSources.exposedSource(chunk.getFluidState(x, y, z), chunk.getFluidState(x, y + 1, z))
                            && chunk.getBlockState(new BlockPos(x, y + 1, z)).isAir() && cap.take(x, z);
                });
        List<P> out = new ArrayList<>(hits.size());
        for (BlockPos p : hits) {
            out.add(toP(p));
        }
        return out;
    }

    // ---- one step ----

    private Task doStep(AltoClef mod, Step step, Scoop spot) {
        switch (step.kind()) {
            case DONE -> {
                finished = true;
                return null;
            }
            case WAIT -> {
                return null;
            }
            case STUCK -> {
                return giveUp(step.why());
            }
            case SCOOP -> {
                return scoopLava(mod, step, spot);
            }
            case PLACE_BLOCK -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new PlaceStructureBlockTask(toPos(step.cell()));
            }
            case BREAK -> {
                Task near = step.stand() == null ? null : goStand(mod, step.stand());
                if (near != null) {
                    return near;
                }
                BlockPos junk = toPos(step.cell());
                if (WorldHelper.tooSlowToBreak(mod, junk)) {
                    return giveUp("something too hard to break at " + junk.toShortString());
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new DestroyBlockTask(junk);
            }
            case LAVA_PLACE, WATER_PLACE -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                if (tick - lastClickTick < CLICK_GAP) {
                    return null;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                Item bucket = step.kind() == Kind.LAVA_PLACE ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
                Direction face = faceOf(step.fx(), step.fy(), step.fz());
                return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(bucket, 1), face, toPos(step.support()), false).stayPut());
            }
            case PLACE_STACK -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                Item block = stackBlock(mod);
                if (block == null) {
                    return giveUp("no block for the placeholder");
                }
                if (tick - lastClickTick < CLICK_GAP) {
                    return null;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(block, 1), faceOf(step.fx(), step.fy(), step.fz()), toPos(step.support()), false).stayPut());
            }
            case WATER_SCOOP, LAVA_UNDO -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
                boolean lava = step.kind() == Kind.LAVA_UNDO;
                return watchReach(mod, step, () -> (lava ? InteractWithBlockTask.scoop(new ItemTarget(Items.BUCKET, 1), toPos(step.cell()), false)
                        : new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), toPos(step.cell()), false)).stayPut());
            }
            case LIGHT -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1),
                        Direction.UP, toPos(step.support()), true);
            }
            default -> {
                return giveUp("unknown step " + step);
            }
        }
    }

    // a bucket on a lava source, from where we stand when it is in reach, else from the stand the plan picked. no spot means
    // the plan shouldn't have asked. the scoop click, so it doesn't sneak and a scoop the server never took gets caught
    private Task scoopLava(AltoClef mod, Step step, Scoop spot) {
        if (spot == null) {
            return null;
        }
        Task there = spot.stand() == null ? null : goStand(mod, spot.stand());
        if (there != null) {
            return there;
        }
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        Step keyed = new Step(Kind.SCOOP, spot.source(), null, 0, 0, 0, spot.stand(), "lava");
        return watchReach(mod, keyed, () -> InteractWithBlockTask.scoop(new ItemTarget(Items.BUCKET, 1), toPos(spot.source()), false).stayPut());
    }

    // the click from a stand, which never walks (stayPut). if it can't reach or can't aim for REACH_TICKS from the first
    // tick we stood there, that's a strike on this cell and we shake loose and stand again
    private Task watchReach(AltoClef mod, Step step, Supplier<InteractWithBlockTask> make) {
        String key = step.kind() + String.valueOf(step.cell());
        if (!key.equals(standKey)) {
            standKey = key;
            standSince = tick;
            reachClicked = false;
        }
        if (reachChild == null || !key.equals(reachKey) || reachLastTick != tick - 1) {
            reachChild = make.get();
            reachKey = key;
            blockedReads = 0;
        } else {
            InteractWithBlockTask.ClickResponse r = reachChild.getClickStatus();
            if (r == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED) {
                reachClicked = true;
                lastClickTick = tick;
            }
            boolean blocked = r == InteractWithBlockTask.ClickResponse.CANT_REACH || reachChild.stillAiming();
            blockedReads = blocked ? blockedReads + 1 : 0;
            if (blockedReads >= BLOCKED_READS && !reachClicked && tick - standSince >= REACH_TICKS) {
                reachLastTick = -1;
                standKey = "";
                if (step.kind() == Kind.SCOOP) {
                    // not that one then, the next tick picks another (or walks to the shore for it). no shaking loose here, we may be
                    // standing on the platform
                    badSources.add(step.cell());
                    return ++scoopMisses > SCOOP_MISSES ? giveUp("no lava we can reach") : null;
                }
                return strike(key, "cant reach");
            }
        }
        reachLastTick = tick;
        return reachChild;
    }

    // per cell: one stubborn cell must not use up the budget of the others
    private Task strike(String key, String why) {
        int n = strikes.merge(key, 1, Integer::sum);
        if (n >= CELL_STRIKES) {
            return giveUp(why + " " + key);
        }
        unstick = new TimeoutWanderTask(3);
        return null;
    }

    // null when we are already on the cell, otherwise the walk there
    private Task goStand(AltoClef mod, P stand) {
        if (stand == null) {
            return null;
        }
        BlockPos me = mod.getPlayer().blockPosition();
        if (me.getX() == stand.x() && me.getZ() == stand.z() && Math.abs(me.getY() - stand.y()) <= 1) {
            return null;
        }
        setDebugState("walking to " + stand);
        return new GetToBlockTask(toPos(stand), false);
    }

    // ---- when a step goes nowhere ----

    private void watchdog(Step step, Scoop spot) {
        // a lava trip has no cell of its own, the source it is for stands in for it so a new trip is a new step
        String where = step.kind() == Kind.SCOOP && spot != null ? String.valueOf(spot.source()) : String.valueOf(step.cell());
        String key = step.kind() + where + step.why();
        if (!key.equals(stepKey)) {
            stepKey = key;
            stepSince = tick;
            return;
        }
        int limit = step.kind() == Kind.WAIT ? WAIT_TICKS : STEP_TICKS;
        if (tick - stepSince < limit) {
            return;
        }
        stepSince = tick;
        if (step.kind() == Kind.WAIT) {
            giveUp(step.why());
            return;
        }
        if (step.kind() == Kind.SCOOP && spot != null) {
            // a lava click that never filled: not that source then, and no shaking loose on the platform
            badSources.add(spot.source());
            if (++scoopMisses > SCOOP_MISSES) {
                giveUp("no lava we can take");
            }
            return;
        }
        strike(step.kind() + where, "not going anywhere");
    }

    // the water and lava we put out must not be left running at the cast site, so the buckets take them back first (a short
    // while only), and then the phase gets the failure
    private Task giveUp(String why) {
        if (failReason == null && failing == null) {
            failing = why;
            failingSince = tick;
        }
        return null;
    }

    private Task cleanUp(AltoClef mod) {
        if (layout == null || tick - failingSince > CLEANUP_TICKS) {
            failReason = failing;
            return null;
        }
        Step loose = LPortalPlan.cleanup(layout, new LiveTerrain(mod.getWorld()), mod.getItemStorage().getItemCount(Items.BUCKET));
        if (loose == null) {
            failReason = failing;
            return null;
        }
        setDebugState("taking the loose " + loose.why() + " back");
        return doStep(mod, loose, null);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LPortalTask;
    }

    @Override
    protected String toHudString() {
        return searching() ? (huntHud.isEmpty() ? "Looking for a lava row" : huntHud) : "Building a lava bucket portal";
    }

    @Override
    protected String toDebugString() {
        return "Lava bucket portal " + (layout == null ? "(searching)" : lastDebug);
    }

    // the live level as a plan terrain. unloaded chunks read as rock so nothing gets planned into them
    private static final class LiveTerrain implements LPortalPlan.Terrain {
        private final ClientLevel level;

        LiveTerrain(ClientLevel level) {
            this.level = level;
        }

        @Override
        public Mat at(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return Mat.SOLID;
            }
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) {
                return Mat.AIR;
            }
            if (s.is(Blocks.LAVA)) {
                return s.getFluidState().isSource() ? Mat.LAVA : Mat.LAVA_FLOW;
            }
            if (s.is(Blocks.WATER)) {
                return s.getFluidState().isSource() ? Mat.WATER : Mat.WATER_FLOW;
            }
            if (s.is(Blocks.OBSIDIAN)) {
                return Mat.OBSIDIAN;
            }
            if (s.is(Blocks.NETHER_PORTAL)) {
                return Mat.PORTAL;
            }
            if (!s.getFluidState().isEmpty()) {
                // waterlogged things and kelp: wet, not ours to build in
                return Mat.WATER_FLOW;
            }
            if (s.getCollisionShape(level, pos).isEmpty()) {
                return Mat.AIR;
            }
            return Mat.SOLID;
        }
    }
}
