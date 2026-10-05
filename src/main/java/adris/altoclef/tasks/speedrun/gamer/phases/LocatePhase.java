package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.LocateStrongholdTask;
import adris.altoclef.tasksystem.Task;

import java.util.Optional;

// throw eyes, walk, throw eyes until the estimator says the stronghold is right under us (or a frame shows up).
// the rays live in RunState so a relog keeps them
public class LocatePhase implements PhaseHandler {
    private LocateStrongholdTask task;

    @Override
    public GamerPhase phase() {
        return GamerPhase.LOCATE;
    }

    @Override
    public String hud() {
        return GamerPhase.LOCATE.hud();
    }

    @Override
    public String hudState() {
        return task == null ? null : task.step();
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.strongholdStart != null || state.endPortalCenter != null;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return StrongholdRules.regressForEyes(facts, state);
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        task = null;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (task == null) {
            task = new LocateStrongholdTask(ctx);
        }
        return task;
    }
}
