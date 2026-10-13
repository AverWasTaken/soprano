package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.FillPortalFramesTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.world.FrameGeometry;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

// put an eye in every empty frame. the portal opens by itself on the 12th, we only notice it
public class OpenPhase implements PhaseHandler {
    private FillPortalFramesTask fillTask;
    private Task craftTask;
    private int craftTarget = -1;
    private String hudState = "Opening the End portal";

    @Override
    public GamerPhase phase() {
        return GamerPhase.OPEN;
    }

    @Override
    public String hud() {
        return GamerPhase.OPEN.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.endPortalOpened;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return StrongholdRules.regressForOpen(facts, state);
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        // the end prep phase turns this back on when it is time to walk in
        ctx.walkOnEndPortal(false);
        fillTask = null;
        craftTask = null;
        craftTarget = -1;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        if (state.endPortalCenter == null) {
            ctx.fail("no end portal ring known, nothing to open");
            return null;
        }
        int[] centre = {state.endPortalCenter.x, state.endPortalCenter.y, state.endPortalCenter.z};
        refresh(mod, ctx, centre);
        hudState = "Putting in Eyes of Ender (" + state.framesFilled + " of " + FrameGeometry.FRAME_COUNT + ")";
        if (state.endPortalOpened) {
            StrongholdScan.swordAfterEye(mod);
            return null;
        }
        GamerFacts facts = ctx.facts();
        trackShortage(facts, state);
        if (StrongholdRules.starvedForGood(facts, state)) {
            ctx.fail("not enough Eyes of Ender for the empty frames and no way to make more");
            return null;
        }
        int eyes = facts.count(Items.ENDER_EYE);
        int need = FrameGeometry.FRAME_COUNT - state.framesFilled;
        if (eyes == 0 && need > 0 && StrongholdRules.craftableEyes(facts) > 0) {
            return craft(mod, Math.min(StrongholdRules.craftableEyes(facts), need));
        }
        if (eyes == 0) {
            StrongholdScan.swordAfterEye(mod);
            return null;
        }
        if (fillTask == null) {
            fillTask = new FillPortalFramesTask(state.endPortalCenter, ctx.cfg().stronghold.breakSilverfishSpawner);
        }
        return fillTask;
    }

    // frames filled and portal state straight from the blocks, only when all 12 are loaded (else the count would dip)
    private void refresh(AltoClef mod, GamerContext ctx, int[] centre) {
        RunState state = ctx.state();
        List<int[]> ring = FrameGeometry.positions(centre);
        for (int[] p : ring) {
            if (!StrongholdScan.isFrame(mod, p)) {
                // a frame that is not there (unloaded or something is off): keep what we knew
                if (StrongholdScan.portalIsOpen(mod, centre)) {
                    state.endPortalOpened = true;
                    state.framesFilled = FrameGeometry.FRAME_COUNT;
                    ctx.save();
                }
                return;
            }
        }
        int filled = FrameGeometry.filledCount(centre, p -> StrongholdScan.frameHasEye(mod, p));
        if (filled != state.framesFilled) {
            state.framesFilled = filled;
            ctx.progress("eye placed");
        }
        if (StrongholdScan.portalIsOpen(mod, centre)) {
            state.endPortalOpened = true;
            ctx.log("the End portal is open");
            ctx.save();
        }
    }

    // pure rule needs a start time for the shortage, this keeps it (0 = no shortage)
    private void trackShortage(GamerFacts facts, RunState state) {
        if (StrongholdRules.starved(facts, state)) {
            if (state.openNoEyesSince == 0) {
                state.openNoEyesSince = Math.max(1, facts.gameTime());
            }
        } else {
            state.openNoEyesSince = 0;
        }
    }

    // top up from pearls + powder in the bag. cached per target so the child is not rebuilt every tick
    private Task craft(AltoClef mod, int count) {
        if (craftTask == null || craftTarget != count) {
            craftTarget = count;
            craftTask = TaskCatalogue.getItemTask(Items.ENDER_EYE, count);
        }
        hudState = "Making Eyes of Ender";
        return craftTask;
    }
}
