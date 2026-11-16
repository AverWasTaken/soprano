package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import baritone.api.utils.Dimension;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalKeepOutTest {

    @Test
    public void theKitRebuildPhasesKeepOutOfThePortal() {
        assertTrue(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.GATHER, false, null));
        assertTrue(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.IRON, false, null));
    }

    @Test
    public void thePhasesThatMeanToGoThroughAreLeftAlone() {
        // PORTAL builds and enters it, NETHER walks back to it from the overworld (HomePortalWalk)
        assertFalse(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.PORTAL, false, null));
        assertFalse(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.NETHER, false, null));
        // and the overworld phases after the nether never come near it on purpose, nothing to guard there
        for (GamerPhase p : new GamerPhase[]{GamerPhase.EYES, GamerPhase.RETURN, GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.OPEN,
                GamerPhase.END_PREP, GamerPhase.DRAGON}) {
            assertFalse(p.name(), PortalKeepOut.on(Dimension.OVERWORLD, p, false, null));
        }
    }

    @Test
    public void theTripsGearStagesKeepOutAndItsPortalStageGoesIn() {
        for (Stage s : new Stage[]{Stage.BLOCKS, Stage.PICK, Stage.FOOD, Stage.HEAL}) {
            assertTrue(s.name(), PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.NETHER, true, s));
        }
        assertFalse(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.NETHER, true, Stage.PORTAL));
        // the trip's word beats the phase: a PORTAL stage during an IRON phase still goes through
        assertFalse(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.IRON, true, Stage.PORTAL));
        // and the nether stages are not about this portal, nor is a stage we cannot read
        for (Stage s : new Stage[]{Stage.WALK, Stage.RECOVER, Stage.WEAR, Stage.HOME}) {
            assertFalse(s.name(), PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.IRON, true, s));
        }
        assertFalse(PortalKeepOut.on(Dimension.OVERWORLD, GamerPhase.IRON, true, null));
    }

    @Test
    public void onlyTheOverworldPortalIsKeptOutOf() {
        // the nether side is how the walk home leaves, and the end has no such portal
        assertFalse(PortalKeepOut.on(Dimension.NETHER, GamerPhase.IRON, false, null));
        assertFalse(PortalKeepOut.on(Dimension.NETHER, GamerPhase.NETHER, true, Stage.BLOCKS));
        assertFalse(PortalKeepOut.on(Dimension.END, GamerPhase.GATHER, false, null));
    }
}
