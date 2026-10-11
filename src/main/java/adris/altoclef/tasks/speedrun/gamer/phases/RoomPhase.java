package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdRoomSearchTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.world.FrameGeometry;

import java.util.ArrayList;
import java.util.Optional;

// dig down at the start chunk, walk the chunk spiral, find the 12 frames. when the spiral is dry once, the rays are
// thrown away and LOCATE runs again from a spot 150 blocks sideways (the regress is just "no strongholdStart")
public class RoomPhase implements PhaseHandler {
    private StrongholdRoomSearchTask task;
    private boolean keepTask;
    private int movingRetries;

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
        if (task == null) {
            task = new StrongholdRoomSearchTask(ctx);
        }
        if (task.exhausted()) {
            return handleExhausted(ctx);
        }
        return task;
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
