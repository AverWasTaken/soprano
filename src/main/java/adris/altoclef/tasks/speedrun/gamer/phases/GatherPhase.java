package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PrepSupport;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;

import java.util.List;

// wood, table, stone tools, furnace, first food. the kit comes from KitPlanner so this never re-collects what we hold
public class GatherPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    private final PrepSupport support = new PrepSupport(false);
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.GATHER;
    }

    @Override
    public String hud() {
        return GamerPhase.GATHER.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return KitPlanner.gather(facts, cfg.overworld).isEmpty();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        support.onEnter(mod);
        hudState = null;
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        support.onExit(mod);
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        List<KitNeed> needs = KitPlanner.gather(ctx.facts(), ctx.cfg().overworld);
        Task side = support.tick(mod, ctx, needs.isEmpty() ? null : needs.get(0));
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        Task task = runner.run(ctx, needs);
        hudState = runner.hud();
        return task;
    }
}
