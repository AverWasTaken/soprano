package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.PillarBed.Cloud;
import adris.altoclef.tasks.speedrun.gamer.end.PillarBed.Step;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class PillarBedTest {
    private static final BlockPos TOP = new BlockPos(0, 67, 0);

    @Test
    public void theStandIsTuckedWestOfThePillarOneBelowItsTop() {
        assertEquals(new BlockPos(-1, 66, 0), PillarBed.stand(TOP));
        assertEquals(new BlockPos(0, 68, 0), PillarBed.bed(TOP));
    }

    @Test
    public void atTheStandIsFlatAndFeet() {
        BlockPos stand = PillarBed.stand(TOP);
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 66));
        assertTrue(PillarBed.atStand(stand, -0.2, 0.6, 66));
        // a jump is still at the stand
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 67));
        assertFalse(PillarBed.atStand(stand, 0.1, 0.5, 66));
        // the well floor right under it passes the flat check, but it isn't where we walk to
        assertFalse(PillarBed.atStand(stand, -0.5, 0.5, 65));
    }

    @Test
    public void sittingOrLowIsSat() {
        assertTrue(PillarBed.sat(true, 90, 67));
        assertTrue(PillarBed.sat(false, 68.9, 67));
        assertFalse(PillarBed.sat(false, 69, 67));
    }

    @Test
    public void flyingGoesToTheStandAndWaitsThere() {
        assertEquals(Step.STAND, PillarBed.step(false, false, false, false));
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, false));
        // a bed left over from the last landing waits too, no clicks in the air
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, true));
    }

    @Test
    public void landingPlacesThenClicksAndStillWalksIfItHasTo() {
        assertEquals(Step.STAND, PillarBed.step(false, true, false, false));
        assertEquals(Step.PLACE, PillarBed.step(false, true, true, false));
        assertEquals(Step.CLICK, PillarBed.step(false, true, true, true));
    }

    @Test
    public void satIsTheRingWhateverElseIsTrue() {
        assertEquals(Step.RING, PillarBed.step(true, false, true, true));
        assertEquals(Step.RING, PillarBed.step(true, true, false, false));
    }

    @Test
    public void placesOnlyFacingEastEveryOtherTick() {
        assertTrue(PillarBed.placeNow(Direction.EAST, 100));
        assertFalse(PillarBed.placeNow(Direction.EAST, 101));
        assertFalse(PillarBed.placeNow(Direction.NORTH, 100));
        assertFalse(PillarBed.placeNow(Direction.WEST, 100));
    }

    @Test
    public void onlyAnEastFootIsTheRightWay() {
        assertTrue(PillarBed.rightWay(Direction.EAST, true));
        for (Direction other : Direction.values()) {
            if (other != Direction.EAST) {
                assertFalse(other.name(), PillarBed.rightWay(other, true));
            }
        }
        // the head half on the pillar means the foot is hanging off it, whichever way that reads
        assertFalse(PillarBed.rightWay(Direction.EAST, false));
    }

    @Test
    public void clicksAreSpacedPastTheInvulnerableWindow() {
        assertTrue(PillarBed.clickNow(true, 5, false, 100, 89));
        assertFalse(PillarBed.clickNow(true, 5, false, 100, 90));
        assertTrue(PillarBed.clickNow(true, 5, false, 100, Long.MIN_VALUE / 2));
    }

    @Test
    public void clicksWantTheHeadCloseAndCover() {
        assertTrue(PillarBed.clickNow(true, 5.29, false, 100, 0));
        assertFalse(PillarBed.clickNow(true, 5.3, false, 100, 0));
        assertFalse(PillarBed.clickNow(false, 5, false, 100, 0));
    }

    @Test
    public void finishingForgetsTheHeadButNotCoverOrSpacing() {
        assertTrue(PillarBed.clickNow(true, 20, true, 100, 0));
        assertFalse(PillarBed.clickNow(false, 20, true, 100, 0));
        assertFalse(PillarBed.clickNow(true, 20, true, 100, 90));
    }

    @Test
    public void finishWantsTheBedsToBeEnoughAndTheBodyClose() {
        assertTrue(PillarBed.finish(28, 2, 4));
        assertTrue(PillarBed.finish(14, 1, 1));
        assertFalse(PillarBed.finish(28.5, 2, 3));
        assertFalse(PillarBed.finish(20, 2, 4.1));
        assertFalse(PillarBed.finish(10, 0, 1));
    }

    @Test
    public void theBlastIsTheMiddleOfTheHeadHalf() {
        assertEquals(new Vec3(1.5, 68.5, 0.5), PillarBed.blast(TOP));
    }

    // a standing player's box with its feet here
    private static double seenAt(double x, double y, double z) {
        return PillarBed.seen(TOP, x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    private static double gapAt(double x, double y, double z) {
        return PillarBed.reachGap(TOP, x, y + 1.62, z);
    }

    @Test
    public void theStandSeesNothingOfTheBlast() {
        assertEquals(0, seenAt(-0.5, 66, 0.5), 1e-9);
        // off centre on the same block is still behind it
        assertEquals(0, seenAt(-0.3, 66, 0.9), 1e-9);
    }

    @Test
    public void theFloorBesideThePillarIsBehindItToo() {
        assertEquals(0, seenAt(-1.5, 64, 0.5), 1e-9);
        assertEquals(0, seenAt(-0.5, 64, 0.5), 1e-9);
    }

    @Test
    public void outInTheOpenSeesAllOfIt() {
        // the east side has nothing between us and it
        assertEquals(1, seenAt(4.5, 66, 0.5), 1e-9);
        // west but over the pillar's top the rays never touch it
        assertEquals(1, seenAt(-3.5, 69, 0.5), 1e-9);
    }

    @Test
    public void aBlockAboveTheStandThePillarOnlyCoversOurLegs() {
        double partly = seenAt(-0.5, 67, 0.5);
        assertTrue(partly > PillarBed.SEEN_LIMIT);
        assertTrue(partly < 1);
        // whole rays out of the 45 the real grid shoots
        assertEquals(0, partly * 45 - Math.rint(partly * 45), 1e-9);
    }

    @Test
    public void theBedIsInReachFromTheStandAndTheFloor() {
        assertTrue(gapAt(-0.5, 66, 0.5) <= PillarBed.BED_REACH);
        assertTrue(gapAt(-1.5, 64, 0.5) <= PillarBed.BED_REACH);
        assertEquals(Math.hypot(1.5, 68 - 65.62), gapAt(-1.5, 64, 0.5), 1e-9);
        assertTrue(gapAt(-6.5, 64, 0.5) > PillarBed.BED_REACH);
    }

    @Test
    public void eastHasToBeWhatTheLookReadsAs() {
        assertTrue(PillarBed.looksEast(TOP, -0.5, 0.5));
        assertTrue(PillarBed.looksEast(TOP, -1.5, 1.5));
        assertFalse(PillarBed.looksEast(TOP, -1.5, 3.5));
        assertFalse(PillarBed.looksEast(TOP, 2.5, 0.5));
        assertFalse(PillarBed.looksEast(TOP, 0.5, 0.5));
    }

    @Test
    public void coverNeedsAllThree() {
        assertTrue(PillarBed.covered(true, 2.8, 0));
        assertTrue(PillarBed.covered(true, PillarBed.BED_REACH, PillarBed.SEEN_LIMIT));
        assertFalse(PillarBed.covered(false, 2.8, 0));
        assertFalse(PillarBed.covered(true, 4.6, 0));
        assertFalse(PillarBed.covered(true, 2.8, PillarBed.SEEN_LIMIT + 0.01));
    }

    @Test
    public void onlyANewBedCaresWhichWayWeLook() {
        assertTrue(PillarBed.fightHere(true, true, false));
        assertTrue(PillarBed.fightHere(true, false, true));
        assertFalse(PillarBed.fightHere(true, false, false));
        assertFalse(PillarBed.fightHere(false, true, true));
    }

    @Test
    public void shovedSidewaysABedThatsDownStillGetsClicked() {
        // behind the pillar and in reach, but too far round for a look at the top to read as east
        double x = -0.5;
        double y = 64;
        double z = 1.5;
        assertFalse(PillarBed.looksEast(TOP, x, z));
        boolean covered = PillarBed.covered(true, gapAt(x, y, z), seenAt(x, y, z));
        assertTrue(covered);
        assertEquals(Step.CLICK, PillarBed.step(false, true, PillarBed.fightHere(covered, true, false), true));
        // with nothing down we can't place from there, that's the walk back
        assertEquals(Step.STAND, PillarBed.step(false, true, PillarBed.fightHere(covered, false, false), false));
    }

    @Test
    public void coverPicksUpWhereTheStandLeftOff() {
        // shoved down beside the pillar: not the stand, still a place to fight from
        assertFalse(PillarBed.atStand(PillarBed.stand(TOP), -1.5, 0.5, 64));
        assertTrue(PillarBed.covered(true, gapAt(-1.5, 64, 0.5), seenAt(-1.5, 64, 0.5)));
        assertTrue(PillarBed.looksEast(TOP, -1.5, 0.5));
        // out east it's not, that's the walk back
        assertFalse(PillarBed.covered(true, gapAt(3.5, 64, 0.5), seenAt(3.5, 64, 0.5)));
    }

    @Test
    public void aSmallCloudThatWillGrowOverTheStandIsOnItAlready() {
        BlockPos stand = PillarBed.stand(TOP);
        // stand middle is -0.5, 0.5. a fresh radius 3 cloud 7 blocks off doesn't touch us yet but it will by the landing
        assertTrue(PillarBed.cloudOnStand(stand, List.of(new Cloud(-0.5, 7.5, 3))));
        assertTrue(PillarBed.cloudOnStand(stand, List.of(new Cloud(-0.5, 0.5, 3))));
        assertFalse(PillarBed.cloudOnStand(stand, List.of(new Cloud(-0.5, 9.0, 3))));
        assertFalse(PillarBed.cloudOnStand(stand, List.of()));
        // any one of them is enough
        assertTrue(PillarBed.cloudOnStand(stand, List.of(new Cloud(30, 30, 3), new Cloud(3, 0.5, 6))));
    }

    @Test
    public void holdSpotIsTheNearestOneClearOfEveryFullGrownCloud() {
        List<Cloud> clouds = List.of(new Cloud(-0.5, 0.5, 3), new Cloud(-0.5, 6.5, 6), new Cloud(-0.5, 10.5, 6));
        Vec3 spot = PillarBed.holdSpot(TOP, clouds, -0.5, 0.5);
        for (Cloud c : clouds) {
            assertTrue(Math.hypot(spot.x - c.x(), spot.z - c.z()) >= PillarBed.CLOUD_FULL_RADIUS + PillarBed.CLOUD_HOLD_MARGIN);
        }
        // the clouds run off to the south (+z), so the way out is north
        assertTrue(spot.z < 0);
        // and nowhere near further than it has to be: one cloud on the stand is 8.5 out
        assertTrue(Math.hypot(spot.x + 0.5, spot.z - 0.5) < 10);
    }

    @Test
    public void holdSpotStaysOutOfThePodium() {
        // a cloud right east of the pillar: the nearest clear spot by distance alone would be in the pit west of the stand
        List<Cloud> clouds = List.of(new Cloud(8, 0.5, 3));
        Vec3 spot = PillarBed.holdSpot(TOP, clouds, -0.5, 0.5);
        assertTrue(Math.hypot(spot.x - 0.5, spot.z - 0.5) >= PillarBed.PODIUM_CLEAR);
        assertTrue(Math.hypot(spot.x - 8, spot.z - 0.5) >= PillarBed.CLOUD_FULL_RADIUS + PillarBed.CLOUD_HOLD_MARGIN);
    }

    @Test
    public void nowhereClearIsNull() {
        List<Cloud> clouds = new ArrayList<>();
        for (int x = -40; x <= 40; x += 6) {
            for (int z = -40; z <= 40; z += 6) {
                clouds.add(new Cloud(x, z, 7));
            }
        }
        assertNull(PillarBed.holdSpot(TOP, clouds, -0.5, 0.5));
    }

    @Test
    public void stayingPutNeedsToBeClearOfTheFullGrownCloud() {
        List<Cloud> clouds = List.of(new Cloud(-0.5, 0.5, 3));
        // where the breath run lets go of a fresh cloud: past its rim of now, inside where it grows to
        assertFalse(PillarBed.clearOfClouds(-6.5, 0.5, clouds));
        assertTrue(PillarBed.clearOfClouds(-8.5, 0.5, clouds));
        // anywhere on the hold spot's block counts as clear, so arriving there ends the walk
        Vec3 spot = PillarBed.holdSpot(TOP, clouds, -0.5, 0.5);
        for (double ox = -0.5; ox <= 0.5; ox += 0.5) {
            for (double oz = -0.5; oz <= 0.5; oz += 0.5) {
                assertTrue(PillarBed.clearOfClouds(spot.x + ox, spot.z + oz, clouds));
            }
        }
    }

    @Test
    public void holdSpotIsOnOurSideOfTheCloud() {
        // a cloud on the stand and we got out east of it: the spot is east too, not round the rim on the far side
        List<Cloud> clouds = List.of(new Cloud(-0.5, 0.5, 3));
        Vec3 spot = PillarBed.holdSpot(TOP, clouds, 6.0, 0.5);
        assertTrue(spot.x > 7);
        assertTrue(Math.abs(spot.z - 0.5) < 2);
        assertTrue(PillarBed.holds(TOP, spot.x, spot.z, clouds));
        // and a spot under a newer cloud doesn't hold any more
        assertFalse(PillarBed.holds(TOP, spot.x, spot.z, List.of(clouds.get(0), new Cloud(spot.x, spot.z, 3))));
    }
}
