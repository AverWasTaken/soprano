package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.DragonWaitRules.Mode;
import adris.altoclef.tasks.speedrun.gamer.end.DragonWaitRules.Spot;
import org.junit.Test;

// the pillar or the ground, the ground ring and the breath sidestep. no minecraft
public class DragonWaitRulesTest {

    @Test
    public void aPearlInTheBagIsThePillarAndNoPearlIsTheGround() {
        assertEquals(Mode.PILLAR, DragonWaitRules.mode(1));
        assertEquals(Mode.PILLAR, DragonWaitRules.mode(12));
        assertEquals(Mode.GROUND, DragonWaitRules.mode(0));
    }

    @Test
    public void theGroundSpotIsInsideTheRingAndInsideTheBedTasksFifteen() {
        for (double angle = 0; angle < Math.PI * 2; angle += 0.3) {
            for (double r : new double[]{0, 3, 8, 10, 25, 40}) {
                Spot s = DragonWaitRules.groundSpot(Math.cos(angle) * r, Math.sin(angle) * r, 0, 0);
                double d = Math.hypot(s.x(), s.z());
                assertTrue("r " + r + " angle " + angle + " d " + d, d >= DragonWaitRules.GROUND_MIN && d <= DragonWaitRules.GROUND_MAX);
                assertTrue(d < 15);
            }
        }
    }

    @Test
    public void standingInTheRingAlreadyMeansStayingPut() {
        Spot s = DragonWaitRules.groundSpot(9, 3, 0, 0);
        assertEquals(9, s.x(), 1e-9);
        assertEquals(3, s.z(), 1e-9);
        assertTrue(DragonWaitRules.inGroundRing(9, 3, 0, 0));
        assertFalse(DragonWaitRules.inGroundRing(2, 0, 0, 0));
        assertFalse(DragonWaitRules.inGroundRing(30, 0, 0, 0));
    }

    @Test
    public void theRingIsAimedOnTheSideWeAreOnAndOffCenterForTheFountain() {
        Spot s = DragonWaitRules.groundSpot(130, 5, 100, 5);
        assertEquals(110, s.x(), 1e-9);
        assertEquals(5, s.z(), 1e-9);
        // standing on the fountain itself still picks a side
        Spot mid = DragonWaitRules.groundSpot(0, 0, 0, 0);
        assertEquals(DragonWaitRules.GROUND_AIM, Math.hypot(mid.x(), mid.z()), 1e-9);
    }

    @Test
    public void aCloudIsLeftWellPastItsRimAndALargerOneNeedsALongerWalk() {
        // radius 3 cloud at the origin, we stand 4 out: inside the trigger margin, sent out to radius + exit margin
        Spot s = DragonWaitRules.clearOfCloud(4, 0, 0, 0, 3);
        assertNotNull(s);
        assertEquals(3 + DragonWaitRules.CLOUD_EXIT_MARGIN, s.x(), 1e-9);
        assertEquals(0, s.z(), 1e-9);
        // the same spot against a grown cloud is further out
        Spot grown = DragonWaitRules.clearOfCloud(6, 0, 0, 0, 7);
        assertNotNull(grown);
        assertEquals(10, grown.x(), 1e-9);
        // and the exit spot is never inside the trigger, so the walk cannot turn around at the rim
        assertNull(DragonWaitRules.clearOfCloud(s.x(), s.z(), 0, 0, 3));
        assertNull(DragonWaitRules.clearOfCloud(10, 0, 0, 0, 3));
        // right on top of it still goes somewhere
        Spot on = DragonWaitRules.clearOfCloud(0, 0, 0, 0, 3);
        assertNotNull(on);
        assertEquals(6, Math.hypot(on.x(), on.z()), 1e-9);
    }

    @Test
    public void aFireballComingAtUsIsDodgedSidewaysNotAlongItsLine() {
        // ball at x=20 flying toward -x, we are at the origin right on its line
        Spot s = DragonWaitRules.dodgeFireball(0, 0, 20, 0, -1, 0);
        assertNotNull(s);
        assertEquals(0, s.x(), 1e-9);
        assertEquals(DragonWaitRules.FIREBALL_SIDESTEP, Math.abs(s.z()), 1e-9);
        // a ball whose line passes well to the side is not our problem
        assertNull(DragonWaitRules.dodgeFireball(0, 10, 20, 0, -1, 0));
        // one already past us, or standing still, is not either
        assertNull(DragonWaitRules.dodgeFireball(0, 0, -5, 0, -1, 0));
        assertNull(DragonWaitRules.dodgeFireball(0, 0, 20, 0, 0, 0));
        // off to one side of the line we step further to that side
        Spot left = DragonWaitRules.dodgeFireball(0, 2, 20, 0, -1, 0);
        assertNotNull(left);
        assertTrue(left.z() < -2 || left.z() > 2);
        Spot a2 = DragonWaitRules.dodgeFireball(0, 2, 20, 0, -1, 0);
        Spot b2 = DragonWaitRules.dodgeFireball(0, -2, 20, 0, -1, 0);
        assertNotNull(b2);
        assertTrue(Math.signum(a2.z()) != Math.signum(b2.z()));
    }
}
