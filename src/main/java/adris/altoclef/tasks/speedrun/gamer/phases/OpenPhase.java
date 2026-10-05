package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;

// STUB, owner: W5 stronghold. replace the whole file. keep the class name and a public no-arg constructor,
// GamerTask builds one of each
public class OpenPhase implements PhaseHandler {
    @Override
    public GamerPhase phase() {
        return GamerPhase.OPEN;
    }

    @Override
    public String hud() {
        return GamerPhase.OPEN.hud();
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return false;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        return null;
    }
}