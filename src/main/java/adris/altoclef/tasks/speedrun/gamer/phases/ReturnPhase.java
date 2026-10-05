package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// back through the portal we came in by. DefaultGoToDimensionTask does the real work (last used portal, else builds one
// from obsidian); the one thing it cannot do is remember the portal over a relog, so RunState keeps it and we walk
// there first when the game forgot it
public class ReturnPhase implements PhaseHandler {
    // close enough that the portal blocks are loaded and the tracker can take over
    private static final double PORTAL_NEAR_BLOCKS = 8;

    private Task goOverworld;
    private GetToBlockTask walkToPortal;
    private RunState.Pos walkingTo;
    private boolean failedAlready;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.RETURN;
    }

    @Override
    public String hud() {
        return GamerPhase.RETURN.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.OVERWORLD;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        goOverworld = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        walkToPortal = null;
        walkingTo = null;
        failedAlready = false;
        hudState = null;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (goOverworld == null) {
            onEnter(mod, ctx);
        }
        recordPortal(mod, ctx.state());
        if (!failedAlready && ctx.secondsInPhase() > ctx.cfg().nether.returnGiveUpMinutes * 60) {
            failedAlready = true;
            ctx.fail("no way back to the overworld");
            return null;
        }
        Task walk = walkToRememberedPortal(mod, ctx.state());
        if (walk != null) {
            hudState = "Walking back to the portal";
            return walk;
        }
        hudState = "Going through the portal";
        return goOverworld;
    }

    // the nether side portal we arrived through, so a later trip (or a relog) knows where home is
    static void recordPortal(AltoClef mod, RunState state) {
        if (state.netherPortal != null) {
            return;
        }
        Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        used.ifPresent(p -> state.netherPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ()));
    }

    // only when the game has no portal in mind and none is in view: go where we remember one
    private Task walkToRememberedPortal(AltoClef mod, RunState state) {
        RunState.Pos home = state.netherPortal;
        if (home == null || mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER).isPresent()
                || mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL)) {
            return null;
        }
        BlockPos at = new BlockPos(home.x, home.y, home.z);
        if (at.closerToCenterThan(mod.getPlayer().position(), PORTAL_NEAR_BLOCKS)) {
            return null;
        }
        if (!home.equals(walkingTo)) {
            walkingTo = home;
            walkToPortal = new GetToBlockTask(at);
        }
        return walkToPortal;
    }
}
