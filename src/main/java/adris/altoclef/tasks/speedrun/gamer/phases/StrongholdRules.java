package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import net.minecraft.world.item.Items;

import java.util.Optional;

// the "not enough eyes for the portal" rules of LOCATE, ROOM and OPEN. pure over facts + state so they are testable
public final class StrongholdRules {
    public static final int FRAMES = 12;
    // one eye can be in the air or on the floor while we count, never more (we pick up before the next throw)
    static final int IN_FLIGHT = 1;
    // 5 s: framesFilled lags the inventory by a tick or two while eyes go in, do not read that as starving
    static final long STARVED_GRACE_TICKS = 100;

    // eyes we could still craft right now: one pearl and one powder each, a rod makes two powder
    public static int craftableEyes(GamerFacts facts) {
        int powder = facts.count(Items.BLAZE_POWDER) + 2 * facts.count(Items.BLAZE_ROD);
        return Math.min(facts.count(Items.ENDER_PEARL), powder);
    }

    // LOCATE and ROOM: eyes held + eyes already in frames + eyes we can craft + the one in flight must reach 12
    public static Optional<GamerPhase> regressForEyes(GamerFacts facts, RunState state) {
        if (state.endPortalOpened || state.netherRevisits >= 1) {
            return Optional.empty();
        }
        int total = facts.count(Items.ENDER_EYE) + state.framesFilled + craftableEyes(facts) + IN_FLIGHT;
        return total < FRAMES ? Optional.of(GamerPhase.NETHER) : Optional.empty();
    }

    // OPEN: short of eyes for the frames that are still empty
    public static boolean starved(GamerFacts facts, RunState state) {
        if (state.endPortalOpened) {
            return false;
        }
        int need = FRAMES - state.framesFilled;
        return need > 0 && facts.count(Items.ENDER_EYE) + craftableEyes(facts) < need;
    }

    // OPEN regress: only after the shortage has stood for a few seconds (openNoEyesSince is kept by the handler tick)
    public static Optional<GamerPhase> regressForOpen(GamerFacts facts, RunState state) {
        if (state.netherRevisits >= 1 || state.openNoEyesSince == 0 || !starved(facts, state)) {
            return Optional.empty();
        }
        return facts.gameTime() - state.openNoEyesSince >= STARVED_GRACE_TICKS ? Optional.of(GamerPhase.NETHER) : Optional.empty();
    }

    // true when the shortage stood long enough and a nether trip is no longer allowed: the handler fails the phase
    public static boolean starvedForGood(GamerFacts facts, RunState state) {
        return state.netherRevisits >= 1 && state.openNoEyesSince != 0 && starved(facts, state)
                && facts.gameTime() - state.openNoEyesSince >= STARVED_GRACE_TICKS;
    }

    private StrongholdRules() {
    }
}
