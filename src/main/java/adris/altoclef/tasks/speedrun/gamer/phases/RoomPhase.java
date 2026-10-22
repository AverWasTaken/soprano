package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdRoomSearchTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.world.FrameGeometry;
import baritone.api.utils.Dimension;

import java.util.ArrayList;
import java.util.Optional;

// dig down at the start chunk, walk the chunk spiral, find the 12 frames. when the spiral is dry once, the rays are
// thrown away and LOCATE runs again from a spot 150 blocks sideways (the regress is just "no strongholdStart")
public class RoomPhase implements PhaseHandler {
    private StrongholdRoomSearchTask task;
    private boolean keepTask;
    private int movingRetries;
    // the bed by the stronghold start (Respawn.wantRoomBed): decided once on arrival, never again mid dig
    private PlaceBedAndSetSpawnTask roomBed;
    private boolean roomBedDecided;
    private double roomBedSince;
    // a bed that will not go down (no spot, mobs on it) does not get to hold up the search
    private static final double ROOM_BED_SECONDS = 60;

    @Override
    public GamerPhase phase() {
        return GamerPhase.ROOM;
    }

    @Override
    public String hud() {
        return GamerPhase.ROOM.hud();
    }

    @Override
    public String hudState() {
        return task == null ? null : task.step();
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.endPortalCenter != null && state.framesSeen >= FrameGeometry.FRAME_COUNT;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        if (state.strongholdStart == null && state.endPortalCenter == null) {
            return Optional.of(GamerPhase.LOCATE);
        }
        return StrongholdRules.regressForEyes(facts, state);
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        // a retry because the spiral is still working keeps the task: a fresh one walks back to the start chunk and forgets
        // the level it searches at. anything else (first entry, a regress back here) starts clean
        if (keepTask && task != null) {
            task.newWindow();
        } else {
            task = null;
            movingRetries = 0;
        }
        keepTask = false;
        roomBed = null;
        roomBedDecided = false;
    }

    // the budget is one window: a spiral that reached new chunks in it is a long legit search, not a stuck one
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (task != null && StrongholdRules.roomStillMoving(task.chunksReached(), task.exhausted(), reason)) {
            keepTask = true;
            movingRetries++;
            return Timeout.RETRY;
        }
        // PhaseMachine bumps the attempt on every retry, the moving ones were free and must not eat the real ones
        return PhaseHandler.super.onTimeout(ctx, Math.max(1, attempt - movingRetries), reason);
    }
    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        Task bed = roomBedStep(mod, ctx);
        if (bed != null) {
            return bed;
        }
        if (task == null) {
            task = new StrongholdRoomSearchTask(ctx);
        }
        if (task.exhausted()) {
            return handleExhausted(ctx);
        }
        return task;
    }

    // the dig is where the kit gets lost, so a respawn bed goes down on the surface right before it when nothing holds our
    // respawn near here. the search task itself is not touched, this runs ahead of it while we stand at the start
    private Task roomBedStep(AltoClef mod, GamerContext ctx) {
        if (roomBedDecided && roomBed == null) {
            return null;
        }
        if (roomBed != null) {
            if (roomBed.isFinished(mod) || ctx.secondsInPhase() - roomBedSince > ROOM_BED_SECONDS) {
                // (done or out of time, either way the stall clock should not blame the dig for the minute the bed took)
                ctx.progress("respawn bed");
                roomBed = null;
                return null;
            }
            return roomBed;
        }
        RunState state = ctx.state();
        RunState.Pos start = state.strongholdStart;
        var player = mod.getPlayer();
        if (start == null || player == null || ctx.facts().dimension() != Dimension.OVERWORLD
                || player.getY() <= ctx.cfg().stronghold.roomMinY) {
            return null;
        }
        boolean atStart = Math.hypot(start.x - player.getX(), start.z - player.getZ()) <= ctx.cfg().stronghold.startReachBlocks;
        if (!atStart) {
            return null;
        }
        roomBedDecided = true;
        if (!Respawn.wantRoomBed(state, true, ctx.facts().count(ItemHelper.BED))) {
            return null;
        }
        roomBed = new PlaceBedAndSetSpawnTask();
        roomBedSince = ctx.secondsInPhase();
        return roomBed;
    }

    @Override
    public double stallSeconds() {
        // digging through stone is slow, a chunk takes up to 90 s
        return 180;
    }

    private Task handleExhausted(GamerContext ctx) {
        RunState state = ctx.state();
        if (state.roomRetries >= 1 || state.strongholdStart == null) {
            ctx.fail("searched every chunk around the stronghold and found no portal room");
            return null;
        }
        state.roomRetries++;
        state.relocateFrom = new RunState.Pos(state.strongholdStart.x, 0, state.strongholdStart.z);
        state.strongholdRays = new ArrayList<>();
        state.strongholdEstimate = null;
        state.strongholdRadius = 0;
        state.strongholdStart = null;
        state.roomChunksVisited.clear();
        // the throw cap is per locate attempt (the ledger starts counting from this number), the relocated try would
        // otherwise inherit whatever the first one burned and fail after a throw or two
        state.eyeThrows = 0;
        ctx.log("no portal room in the stronghold chunks, throwing eyes again from somewhere else");
        ctx.save();
        task = null;
        return null;
    }
}
