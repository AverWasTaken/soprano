package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RunZonesTest {

    private static final double NO_GOAL = Double.NaN;

    @Test
    public void aCellInsideTheRadiusIsAWallAndOutsideIsNot() {
        RunZones z = new RunZones();
        z.add(100, 100, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(110, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(100, 123, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(100, 124, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(130, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneExpiresAfterThreeMinutes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS - 1, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS, 500, 500, NO_GOAL, NO_GOAL));
        assertEquals(0, z.active(1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aZoneWeStandInIsNoWallSoWeCanWalkOut() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertFalse(z.blocks(10, 10, 1000, 5, 5, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneTheGoalIsInIsNoWallSoThePathExists() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertFalse(z.blocks(10, 10, 1000, 500, 500, 3, 3));
        assertTrue(z.blocks(10, 10, 1000, 500, 500, 90, 90));
    }

    @Test
    public void theClockGoingBackwardsIsAnotherWorld() {
        RunZones z = new RunZones();
        z.add(0, 0, 50_000, RunZones.RADIUS);
        assertFalse(z.blocks(0, 0, 100, 500, 500, NO_GOAL, NO_GOAL));
        z.add(900, 900, 100, RunZones.RADIUS);
        assertEquals(1, z.active(100));
    }

    @Test
    public void twoRunsFromTheSameSpotAreOneZoneAndTheClockRefreshes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.add(1, 1, 5000, RunZones.RADIUS);
        assertEquals(1, z.active(5000));
        assertTrue(z.blocks(0, 0, 5000 + RunZones.EXPIRY_TICKS - 10, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void theListStaysSmall() {
        RunZones z = new RunZones();
        for (int i = 0; i < 40; i++) {
            z.add(i * 100, 0, 1000 + i, RunZones.RADIUS);
        }
        assertEquals(16, z.active(1100));
    }

    @Test
    public void everyZoneKeepsItsOwnRadius() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(200, 0, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(11, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(13, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(223, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(225, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void exemptionsUseTheRadiusOfEachZone() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(100, 0, 1000, RunZones.RADIUS);
        // 20 from the small zone's middle is outside it, so standing there does not open it, but it opens the big one's cell
        assertTrue(z.blocks(5, 0, 1000, 20, 0, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(100, 0, 1000, 20, 0, 85, 0));
        // a goal 15 off the big middle is in the big zone and not the small one
        assertTrue(z.blocks(5, 0, 1000, 500, 500, 115, 0));
        assertFalse(z.blocks(100, 10, 1000, 500, 500, 115, 0));
    }

    @Test
    public void replaceAllSwapsTheWholeList() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.replaceAll(java.util.List.of(new double[]{50, 50}), 2000, 12);
        assertFalse(z.blocks(0, 0, 2000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(55, 50, 2000, 500, 500, NO_GOAL, NO_GOAL));
        assertEquals(1, z.active(2000));
    }

    @Test
    public void aSmallZoneOnTheSameSpotDoesNotShrinkABigOne() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.add(2, 2, 1100, RunZones.PACK_RADIUS);
        // two zones now, a merge used to lose track of where the pack was
        assertEquals(2, z.active(1100));
        assertTrue(z.blocks(20, 0, 1100, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void coversHoldsBlazesWhereverWeStand() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        // no player or goal exemption: standing in the closet is still the closet
        assertTrue(z.covers(5, 5, 1000));
        assertFalse(z.covers(13, 0, 1000));
        // and it goes with the same three minute clock as the rest
        assertFalse(z.covers(5, 5, 1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aPackAndAFatZoneCoverInEitherOrder() {
        RunZones a = new RunZones();
        a.add(0, 0, 1000, RunZones.RADIUS);
        a.add(2, 2, 1100, RunZones.PACK_RADIUS);
        assertTrue(a.covers(2, 2, 1100));
        assertTrue(a.covers(20, 0, 1100));
        RunZones b = new RunZones();
        b.add(0, 0, 1000, RunZones.PACK_RADIUS);
        b.add(2, 2, 1100, RunZones.RADIUS);
        assertTrue(b.covers(0, 0, 1100));
        assertTrue(b.covers(20, 0, 1100));
    }

    @Test
    public void aPackZoneCoversItsOwnRadiusAndThenExpires() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        assertTrue(z.coversPack(11, 0, 1000));
        assertFalse(z.coversPack(13, 0, 1000));
        assertTrue(z.coversPack(0, 0, 1000 + RunZones.EXPIRY_TICKS - 1));
        assertFalse(z.coversPack(0, 0, 1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aRunOffOneSkeletonIsNoPack() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertTrue(z.covers(5, 0, 1000));
        assertFalse(z.coversPack(5, 0, 1000));
        assertEquals(null, z.packZoneAt(5, 0, 1000));
    }

    @Test
    public void aFatRunOnTopOfAPackLeavesThePackWhereItWas() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(4, 0, 1100, RunZones.RADIUS);
        // the pack's own edge, not one dragged over by the run
        assertTrue(z.coversPack(-10.5, 0, 1100));
        assertFalse(z.coversPack(13, 0, 1100));
        // and its own clock
        assertFalse(z.coversPack(0, 0, 1000 + RunZones.EXPIRY_TICKS));
        assertTrue(z.covers(0, 0, 1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aPackOnTopOfAFatRunDoesNotShrinkIt() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.add(2, 2, 1100, RunZones.PACK_RADIUS);
        assertTrue(z.coversPack(2, 2, 1100));
        assertTrue(z.covers(20, 0, 1100));
    }

    @Test
    public void theSamePackAgainMovesAndRefreshes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(3, 0, 1100, RunZones.PACK_RADIUS);
        assertEquals(1, z.active(1100));
        assertTrue(z.coversPack(0, 0, 1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void thePackZoneSaysWhereItsMiddleIs() {
        RunZones z = new RunZones();
        z.add(30, -40, 1000, RunZones.PACK_RADIUS);
        double[] at = z.packZoneAt(35, -40, 1000);
        assertEquals(30, at[0], 0);
        assertEquals(-40, at[1], 0);
    }

    // ---- hoglin packs

    private static double[] hoglin(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    // nothing noted yet
    private static RunZones.Pack pack(List<double[]> hoglins, double x, double y, double z) {
        return new RunZones().newHoglinPack(hoglins, x, y, z, 1000);
    }

    private static List<double[]> threeAt(double x, double y, double z) {
        return List.of(hoglin(x, y, z), hoglin(x + 2, y, z), hoglin(x, y, z + 2));
    }

    @Test
    public void threeTogetherAreAPackAtTheirMiddle() {
        RunZones.Pack pack = pack(threeAt(30, 64, 0), 0, 64, 0);
        assertNotNull(pack);
        assertEquals(3, pack.count());
        assertEquals(30 + 2.0 / 3, pack.x(), 1e-9);
        assertEquals(2.0 / 3, pack.z(), 1e-9);
    }

    @Test
    public void twoAreNoPack() {
        assertNull(pack(List.of(hoglin(30, 64, 0), hoglin(31, 64, 0)), 0, 64, 0));
        assertNull(pack(List.of(), 0, 64, 0));
    }

    @Test
    public void threeScatteredAreNoPack() {
        assertNull(pack(List.of(hoglin(0, 64, 0), hoglin(20, 64, 0), hoglin(0, 64, 20)), 5, 64, 5));
    }

    // no two of them within 8 of each other, all three within 8 of the middle
    @Test
    public void threeThatOnlyFitAroundTheirMiddleAreStillAPack() {
        RunZones.Pack pack = pack(List.of(hoglin(-7, 64, 0), hoglin(7, 64, 0), hoglin(0, 64, 7)), 0, 64, 0);
        assertNotNull(pack);
        assertEquals(3, pack.count());
        assertEquals(0, pack.x(), 1e-9);
        assertEquals(7.0 / 3, pack.z(), 1e-9);
    }

    // the big one has its zone: it must not hide the small one behind it for as long as both are in sight
    @Test
    public void aPackWithAZoneDoesNotHideTheNextOne() {
        List<double[]> two = new java.util.ArrayList<>(threeAt(-30, 64, 0));
        two.addAll(threeAt(30, 64, 0));
        two.add(hoglin(31, 64, 1));
        RunZones z = new RunZones();
        RunZones.Pack big = z.newHoglinPack(two, 0, 64, 0, 1000);
        assertNotNull(big);
        assertTrue(big.x() > 29);
        z.add(big.x(), big.z(), 1000, RunZones.HOGLIN_RADIUS);
        RunZones.Pack small = z.newHoglinPack(two, 0, 64, 0, 1020);
        assertNotNull(small);
        assertTrue(small.x() < -29);
        z.add(small.x(), small.z(), 1020, RunZones.HOGLIN_RADIUS);
        assertNull(z.newHoglinPack(two, 0, 64, 0, 1040));
        assertEquals(2, z.active(1040));
    }

    @Test
    public void onlyThoseInSightCount() {
        assertNotNull(pack(threeAt(37, 64, 0), 0, 64, 0));
        // flat
        assertNull(pack(threeAt(41, 64, 0), 0, 64, 0));
        // and up or down
        assertNotNull(pack(threeAt(10, 88, 0), 0, 64, 0));
        assertNull(pack(threeAt(10, 89, 0), 0, 64, 0));
        assertNull(pack(threeAt(10, 39, 0), 0, 64, 0));
    }

    // the middle of all six would be an empty field between them
    @Test
    public void twoPacksPickTheBiggerNotTheFieldBetween() {
        List<double[]> two = new java.util.ArrayList<>(threeAt(-30, 64, 0));
        two.addAll(threeAt(30, 64, 0));
        two.add(hoglin(31, 64, 1));
        RunZones.Pack pack = pack(two, 0, 64, 0);
        assertNotNull(pack);
        assertEquals(4, pack.count());
        assertTrue(pack.x() > 29);
    }

    @Test
    public void aHoglinZoneIsAWallForWalksAndNothingElse() {
        RunZones z = new RunZones();
        z.add(100, 100, 1000, RunZones.HOGLIN_RADIUS);
        assertTrue(z.blocks(110, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(117, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
        // the blaze picking and the spawner picking don't hear about it
        assertFalse(z.covers(100, 100, 1000));
        assertFalse(z.coversPack(100, 100, 1000));
        assertNull(z.packZoneAt(100, 100, 1000));
        // stood in, or walking to something in it, it's no wall
        assertFalse(z.blocks(110, 100, 1000, 105, 105, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(110, 100, 1000, 500, 500, 95, 95));
    }

    @Test
    public void otherZonesStillCoverNextToAHoglinOne() {
        RunZones z = new RunZones();
        z.add(100, 100, 1000, RunZones.HOGLIN_RADIUS);
        z.add(100, 100, 1000, RunZones.PACK_RADIUS);
        assertTrue(z.covers(100, 100, 1000));
        assertTrue(z.coversPack(100, 100, 1000));
    }

    @Test
    public void aLiveHoglinZoneNearbyMeansNoSecondOne() {
        RunZones z = new RunZones();
        assertFalse(z.hoglinZoneNear(0, 0, 1000));
        z.add(0, 0, 1000, RunZones.HOGLIN_RADIUS);
        assertTrue(z.hoglinZoneNear(16, 0, 1000));
        assertFalse(z.hoglinZoneNear(17, 0, 1000));
        assertFalse(z.hoglinZoneNear(0, 0, 1000 + RunZones.EXPIRY_TICKS));
        // somebody else's zone on the spot doesn't count
        RunZones other = new RunZones();
        other.add(0, 0, 1000, RunZones.PACK_RADIUS);
        assertFalse(other.hoglinZoneNear(0, 0, 1000));
    }
}
