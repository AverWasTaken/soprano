package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import baritone.api.utils.Dimension;

// when the home portal is a hole in the floor and not a door. the respawn bed sits right next to it, and the naked work after a
// nether death (dirt by hand, the stone pick, the meal, the kit rebuild) happens on that same patch of grass. a miner standing
// in the portal for four seconds is a trip into whatever just killed us, with no armor on. pure, GamerTask feeds it
public final class PortalKeepOut {

    private PortalKeepOut() {
    }

    // true = we are not supposed to be in a portal right now: the wall goes up and standing in one gets us walked out.
    // the trip decides while it runs (its PORTAL stage is the one that means to go through), otherwise the phase does: GATHER and
    // IRON never go to the nether, PORTAL and NETHER do. later overworld phases have no portal bed to work beside, they are left alone
    public static boolean on(Dimension now, GamerPhase phase, boolean tripOn, NetherTripRules.Stage tripStage) {
        if (now != Dimension.OVERWORLD) {
            return false;
        }
        if (tripOn) {
            return gearStage(tripStage);
        }
        return phase == GamerPhase.GATHER || phase == GamerPhase.IRON;
    }

    // the overworld stages before the portal walk. a stage we cannot read is left alone, the trip knows better than us
    static boolean gearStage(NetherTripRules.Stage stage) {
        return stage == NetherTripRules.Stage.BLOCKS || stage == NetherTripRules.Stage.PICK || stage == NetherTripRules.Stage.FOOD
                || stage == NetherTripRules.Stage.HEAL;
    }
}
