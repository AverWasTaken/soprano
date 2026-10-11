package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToChunkTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.StrongholdConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.world.FrameGeometry;
import adris.altoclef.world.StrongholdRoomPlan;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// dig down at the start chunk until stone bricks show, then walk the chunk spiral looking for end portal frames.
// only frames we SEE count. once three of them agree on a ring the other nine are given (the ring is rigid)
public class StrongholdRoomSearchTask extends Task {
    private enum Stage {TO_START, DIG, SPIRAL, HINT, APPROACH}

    private static final Block[] BRICKS = {Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS};
    // the whole stronghold sits under this (top of the start staircase is around 50) and village bricks do not
    private static final int BRICK_MAX_Y = 56;
    private static final int BRICK_MAX_DIST = 140;
    private static final int SCAN_EVERY_TICKS = 5;
    private static final double SAVE_EVERY_SECONDS = 15;
    private static final double HINT_SECONDS = 40;
    private static final int APPROACH_RANGE = 4;

    private final GamerContext ctx;

    private StrongholdConfig cfg;
    private Stage stage = Stage.TO_START;
    private double stageSince;
    private int tickCounter;
    private boolean done;
    private boolean exhausted;
    private boolean bricksSeen;
    private boolean pushedBehaviour;
    private boolean started;
    // the y the stronghold is on as far as we can tell: where we were when the spiral began. chunks only count as
    // searched when we were near this level
    private double workY = Double.NaN;
    private boolean hintGivenUp;
    private int chunksReached;
    private boolean rearmChunkTimer;
    private double lastSave;
    private double startedAt;

    private Task goStartTask;
    private LocateLegPlanner.Leg startLeg;
    private Task digTask;
    private Task chunkTask;
    private StrongholdRoomPlan.Chunk chunkTarget;
    private double chunkSince;
    private Task hintTask;
    private BlockPos hintPos;
    private Task approachTask;
    private final SilverfishSpawnerBreaker spawnerBreaker = new SilverfishSpawnerBreaker();
    private int lastDigY = Integer.MAX_VALUE;
    private String step = "Searching the stronghold";

    public StrongholdRoomSearchTask(GamerContext ctx) {
        this.ctx = ctx;
    }

    public String step() {
        return step;
    }

    // chunks newly covered since the last newWindow (RoomPhase asks this when the budget runs out)
    public int chunksReached() {
        return chunksReached;
    }

    public void newWindow() {
        chunksReached = 0;
        // the chunk timer kept running while the phase retried, the chunk in progress gets its 90 s again
        rearmChunkTimer = true;
    }

    // the whole spiral came up empty
    public boolean exhausted() {
        return exhausted;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        mod.getBlockTracker().trackBlock(BRICKS);
        // marvion: with a diamond pickaxe tunnelling is cheap enough that the pathfinder should stop flinching at stone
        mod.getBehaviour().push();
        pushedBehaviour = true;
        if (mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            mod.getBehaviour().setBlockBreakAdditionalPenalty(0);
        }
        // an interrupt (mob defense, eating) stops and restarts us: keep the stage, the chunk we were on and what we
        // have seen. onStop popped the behaviour and untracked, so those two are redone above, the rest only once
        if (started) {
            return;
        }
        started = true;
        cfg = ctx.cfg().stronghold;
        done = false;
        exhausted = false;
        bricksSeen = false;
        startedAt = seconds(mod);
        lastSave = startedAt;
        enter(mod, ctx.state().endPortalCenter != null ? Stage.APPROACH : Stage.TO_START);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done || exhausted) {
            return null;
        }
        if (mod.getWorld() == null || WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            ctx.fail("not in the overworld while searching the stronghold");
            return null;
        }
        tickCounter++;
        if (tickCounter % SCAN_EVERY_TICKS == 0) {
            scan(mod);
            if (done) {
                return null;
            }
        }
        Task spawner = spawnerBreaker.next(mod, cfg.breakSilverfishSpawner, tickCounter);
        if (spawner != null) {
            step = "Breaking the silverfish spawner";
            setDebugState("Breaking the silverfish spawner", step);
            return spawner;
        }
        return switch (stage) {
            case TO_START -> toStart(mod);
            case DIG -> dig(mod);
            case SPIRAL -> spiral(mod);
            case HINT -> hint(mod);
            case APPROACH -> approach(mod);
        };
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        mod.getBlockTracker().stopTracking(BRICKS);
        if (pushedBehaviour) {
            mod.getBehaviour().pop();
            pushedBehaviour = false;
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof StrongholdRoomSearchTask;
    }

    @Override
    protected String toHudString() {
        return step;
    }

    @Override
    protected String toDebugString() {
        return "Searching the stronghold for the portal room (" + stage + ")";
    }

    private double seconds(AltoClef mod) {
        return mod.getWorld().getGameTime() / 20.0;
    }

    private void enter(AltoClef mod, Stage next) {
        if (next == Stage.TO_START) {
            // coming back from a hint walk: the old leg is long expired and would skip the walk, then we dig at the hint spot
            goStartTask = null;
            startLeg = null;
        }
        stage = next;
        stageSince = seconds(mod);
    }

    private void enterSpiral(AltoClef mod) {
        if (Double.isNaN(workY)) {
            workY = mod.getPlayer().getY();
        }
        enter(mod, Stage.SPIRAL);
    }

    // frames: centre if three agree, a hint if fewer. bricks: are we inside yet
    private void scan(AltoClef mod) {
        RunState state = ctx.state();
        List<int[]> frames = StrongholdScan.seenFrames(mod);
        if (state.endPortalCenter == null && !frames.isEmpty()) {
            Optional<int[]> centre = StrongholdScan.centre(frames);
            if (centre.isPresent()) {
                int[] c = centre.get();
                state.endPortalCenter = new RunState.Pos(c[0], c[1], c[2]);
                refreshFilled(mod, c);
                ctx.progress("portal ring found");
                ctx.log("found the end portal room at " + c[0] + ", " + c[2]);
                ctx.save();
                enter(mod, Stage.APPROACH);
            } else if (stage != Stage.HINT && stage != Stage.APPROACH && !hintGivenUp) {
                hintPos = nearestFrame(mod, frames);
                hintTask = new GetWithinRangeOfBlockTask(hintPos, APPROACH_RANGE);
                enter(mod, Stage.HINT);
            }
        }
        if (state.endPortalCenter != null) {
            countSeen(mod, frames);
        }
        if (!bricksSeen) {
            bricksSeen = anyBrickSeen(mod);
        }
    }

    private BlockPos nearestFrame(AltoClef mod, List<int[]> frames) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] f : frames) {
            BlockPos p = new BlockPos(f[0], f[1], f[2]);
            double d = p.distSqr(mod.getPlayer().blockPosition());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void countSeen(AltoClef mod, List<int[]> frames) {
        int[] c = centreOf();
        int seen = FrameGeometry.seenCount(c, p -> {
            for (int[] f : frames) {
                if (f[0] == p[0] && f[1] == p[1] && f[2] == p[2]) {
                    return true;
                }
            }
            return false;
        });
        RunState state = ctx.state();
        if (seen > state.framesSeen) {
            state.framesSeen = seen;
        }
        refreshFilled(mod, c);
        if (state.framesSeen >= FrameGeometry.FRAME_COUNT) {
            finish();
        }
    }

    // same guard as OpenPhase.refresh: an unloaded frame reads as empty, so the count only moves when all 12 are loaded
    private void refreshFilled(AltoClef mod, int[] c) {
        for (int[] p : FrameGeometry.positions(c)) {
            if (!StrongholdScan.isFrame(mod, p)) {
                return;
            }
        }
        ctx.state().framesFilled = FrameGeometry.filledCount(c, p -> StrongholdScan.frameHasEye(mod, p));
    }

    private int[] centreOf() {
        RunState.Pos p = ctx.state().endPortalCenter;
        return new int[]{p.x, p.y, p.z};
    }

    private void finish() {
        ctx.state().framesSeen = FrameGeometry.FRAME_COUNT;
        ctx.save();
        done = true;
    }

    private boolean anyBrickSeen(AltoClef mod) {
        return !seenBricks(mod, 1).isEmpty();
    }

    // seen stone bricks of the stronghold family, nearest first, at most limit of them. tracker order is scan order,
    // so sort before asking the seen filter or the same few hidden ones eat its budget forever
    private List<BlockPos> seenBricks(AltoClef mod, int limit) {
        RunState.Pos start = ctx.state().strongholdStart;
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : StrongholdScan.nearest(mod.getBlockTracker().getKnownLocations(BRICKS),
                mod.getPlayer().blockPosition(), StrongholdScan.MAX_SCAN)) {
            if (pos.getY() > BRICK_MAX_Y) {
                continue;
            }
            if (start != null && Math.hypot(pos.getX() - start.x, pos.getZ() - start.z) > BRICK_MAX_DIST) {
                continue;
            }
            if (SeenFilter.isSeen(mod, pos)) {
                out.add(pos);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private Task toStart(AltoClef mod) {
        RunState.Pos start = ctx.state().strongholdStart;
        if (start == null) {
            ctx.fail("no stronghold start to search from");
            return null;
        }
        double dist = Math.hypot(start.x - mod.getPlayer().getX(), start.z - mod.getPlayer().getZ());
        if (goStartTask == null) {
            goStartTask = new GetToXZTask(start.x, start.z, Dimension.OVERWORLD);
            startLeg = new LocateLegPlanner.Leg(seconds(mod), dist);
        }
        startLeg.update(seconds(mod), dist);
        if (dist <= cfg.startReachBlocks || startLeg.expired(seconds(mod))) {
            // frames seen on the way in are handled by scan(), so heading down is the only thing left to decide
            if (mod.getPlayer().getY() > cfg.roomMinY && !bricksSeen) {
                enter(mod, Stage.DIG);
            } else {
                enterSpiral(mod);
            }
            return null;
        }
        step = "Walking to the stronghold";
        setDebugState("Walking to the start chunk", step);
        return goStartTask;
    }

    private Task dig(AltoClef mod) {
        step = "Digging down";
        setDebugState("Digging down at the start chunk", step);
        int y = (int) Math.floor(mod.getPlayer().getY());
        if (y < lastDigY) {
            lastDigY = y;
            ctx.progress("dug down");
        }
        boolean deepEnough = mod.getPlayer().getY() <= cfg.roomMinY + 2;
        if (bricksSeen || deepEnough || seconds(mod) - stageSince > cfg.digDownSeconds) {
            Debug.logMessage("done digging down (bricks " + bricksSeen + ", y " + (int) mod.getPlayer().getY() + ")");
            enterSpiral(mod);
            return null;
        }
        if (digTask == null) {
            digTask = new GetToYTask(cfg.roomMinY);
        }
        return digTask;
    }

    private Task spiral(AltoClef mod) {
        step = "Searching the stronghold";
        RunState state = ctx.state();
        ChunkPos here = mod.getPlayer().chunkPosition();
        markCovered(mod, here, state);
        if (rearmChunkTimer) {
            rearmChunkTimer = false;
            chunkSince = seconds(mod);
        }
        boolean arrived = chunkTarget != null && here.x == chunkTarget.cx() && here.z == chunkTarget.cz();
        if (chunkTarget != null && (arrived || seconds(mod) - chunkSince >= cfg.perChunkSeconds)) {
            // giving up on a chunk after the timer is not reaching it: only arrivals count as the spiral moving
            if (state.roomChunksVisited.add(chunkTarget.key()) && arrived) {
                chunksReached++;
            }
            ctx.progress("chunk searched");
            chunkTarget = null;
            chunkTask = null;
        }
        if (chunkTarget == null && !pickNextChunk(mod, here)) {
            return null;
        }
        setDebugState("Searching chunk " + chunkTarget.cx() + ", " + chunkTarget.cz(), step);
        return chunkTask;
    }

    // standing in a dry tunnel is not searching the chunk (the room is only found by line of sight): the chunks around
    // us count once we are within ~24 blocks of their middle and near the level the stronghold is on
    private void markCovered(AltoClef mod, ChunkPos here, RunState state) {
        double px = mod.getPlayer().getX();
        double py = mod.getPlayer().getY();
        double pz = mod.getPlayer().getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = here.x + dx;
                int cz = here.z + dz;
                if (RoomCoverage.covers(px, py, pz, cx, cz, workY) && state.roomChunksVisited.add(cx + "," + cz)) {
                    chunksReached++;
                    maybeSave(mod);
                }
            }
        }
    }

    private boolean pickNextChunk(AltoClef mod, ChunkPos here) {
        RunState.Pos start = ctx.state().strongholdStart;
        StrongholdRoomPlan.Chunk startChunk = new StrongholdRoomPlan.Chunk(Math.floorDiv(start.x, 16), Math.floorDiv(start.z, 16));
        // bricks we have already seen say where corridors are: follow those before walking the blind spiral
        Optional<StrongholdRoomPlan.Chunk> next = guidedChunk(mod, startChunk);
        if (next.isPresent()) {
            step = "Following the stronghold corridors";
        } else {
            next = StrongholdRoomPlan.next(startChunk, cfg.spiralRadiusChunks,
                    ctx.state().roomChunksVisited, new StrongholdRoomPlan.Chunk(here.x, here.z));
        }
        if (next.isEmpty()) {
            Debug.logMessage("stronghold spiral exhausted after " + ctx.state().roomChunksVisited.size() + " chunks");
            ctx.save();
            exhausted = true;
            return false;
        }
        chunkTarget = next.get();
        chunkTask = new GetToChunkTask(new ChunkPos(chunkTarget.cx(), chunkTarget.cz()));
        chunkSince = seconds(mod);
        return true;
    }

    private Optional<StrongholdRoomPlan.Chunk> guidedChunk(AltoClef mod, StrongholdRoomPlan.Chunk startChunk) {
        List<int[]> xz = new ArrayList<>();
        for (BlockPos p : seenBricks(mod, StrongholdScan.MAX_SCAN)) {
            xz.add(new int[]{p.getX(), p.getZ()});
        }
        return RoomCoverage.nearestUnvisitedBrickChunk(xz, ctx.state().roomChunksVisited, mod.getPlayer().getX(),
                mod.getPlayer().getZ(), startChunk.cx(), startChunk.cz(), cfg.spiralRadiusChunks)
                .map(c -> new StrongholdRoomPlan.Chunk(c[0], c[1]));
    }

    private void maybeSave(AltoClef mod) {
        if (seconds(mod) - lastSave >= SAVE_EVERY_SECONDS) {
            lastSave = seconds(mod);
            ctx.save();
        }
    }

    // one or two frames seen, not enough for a ring: go closer and look
    private Task hint(AltoClef mod) {
        step = "Looking at the portal frames";
        setDebugState("Walking up to a seen frame", step);
        if (seconds(mod) - stageSince > HINT_SECONDS || mod.getPlayer().blockPosition().closerThan(hintPos, APPROACH_RANGE + 1)) {
            hintGivenUp = true;
            if (bricksSeen) {
                enterSpiral(mod);
            } else {
                enter(mod, Stage.TO_START);
            }
            return null;
        }
        return hintTask;
    }

    // ring known: go and see the rest. baritone stays out of lava, and we never swim it
    private Task approach(AltoClef mod) {
        step = "Looking at the portal frames";
        setDebugState("Approaching the end portal", step);
        if (approachTask == null) {
            approachTask = new GetWithinRangeOfBlockTask(new BlockPos(centreOf()[0], centreOf()[1], centreOf()[2]), APPROACH_RANGE);
        }
        if (seconds(mod) - stageSince >= cfg.approachSeconds) {
            Debug.logMessage("taking the frames we have not seen as given, the ring is rigid");
            finish();
            return null;
        }
        return approachTask;
    }
}
