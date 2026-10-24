package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LavaShoreTest {
    // a flat world: floor is solid at y < 0 (feet at y = 0), everything above is open. lava replaces a floor cell, a void
    // column is open all the way down, a ledge column has its floor further down
    private static final class World implements Cells {
        final Set<Long> lava = new HashSet<>();
        final Set<Long> voids = new HashSet<>();
        final Set<Long> water = new HashSet<>();
        final Set<Long> solid = new HashSet<>();
        final Map<Long, Integer> floorAt = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }

        World lava(int x, int y, int z) {
            lava.add(key(x, y, z));
            return this;
        }

        World pit(int x, int z) {
            voids.add(key(x, 0, z));
            return this;
        }

        World water(int x, int y, int z) {
            water.add(key(x, y, z));
            return this;
        }

        World solid(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        World ledge(int x, int z, int floorY) {
            floorAt.put(key(x, 0, z), floorY);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (lava.contains(key(x, y, z))) return Cell.LAVA;
            if (water.contains(key(x, y, z))) return Cell.WATER;
            if (solid.contains(key(x, y, z))) return Cell.SOLID;
            if (voids.contains(key(x, 0, z))) return Cell.OPEN;
            Integer floor = floorAt.get(key(x, 0, z));
            if (floor != null) return y > floor ? Cell.OPEN : Cell.SOLID;
            return y >= 0 ? Cell.OPEN : Cell.SOLID;
        }
    }

    @Test
    public void inlandApproachesAsBefore() {
        assertFalse(LavaShore.approachBlocked(new World(), 0.5, 0, 0.5, 6.5, 0, 0.5));
    }

    @Test
    public void foeOnTheShoreIsLeftToComeToUs() {
        World w = new World().lava(7, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 6.5, 0, 0.5));
    }

    @Test
    public void ourOwnShoreStopsTheWalkToo() {
        World w = new World().lava(-1, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 8.5, 0, 0.5));
    }

    @Test
    public void lavaOnTheStraightLineBlocks() {
        // 3 blocks of lava in the middle, more than two away from either end
        World w = new World().lava(5, -1, 0).lava(6, -1, 0).lava(7, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertTrue(LavaShore.lineHitsLava(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
    }

    @Test
    public void lavaThreeAwayIsNotAShore() {
        World w = new World().lava(3, -1, 6);
        assertFalse(LavaShore.approachBlocked(w, 0.5, 0, 6.5, 0.5, 0, 12.5));
    }

    @Test
    public void lavaAtThreeCellsBehindIsUnsafe() {
        // foe at +x of us, the knock goes toward -x
        World w = new World().lava(-3, -1, 0);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
        // the other way round it is not in the line
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, -1.5, 0.5));
    }

    @Test
    public void lavaFourBehindIsOutOfTheLine() {
        World w = new World().lava(-4, -1, 0);
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void diagonalKnockFindsTheLava() {
        // foe at +x +z, knock toward -x -z, lava two cells down that diagonal
        World w = new World().lava(-2, -1, -2);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 1.5));
    }

    @Test
    public void fortressBridgeSideIsADropBehindUs() {
        // a bridge one wide along x, void on both sides
        World w = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (z != 0) w.pit(x, z);
            }
        }
        // foe in front of us along the bridge: the knock goes back along the bridge, the floor is there
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 2.5, 0.5));
        // foe off the side of the bridge: the knock goes the other way, into the void
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 0.5, 1.5));
    }

    @Test
    public void ledgeWithAFloorFourDownIsSafe() {
        World w = new World();
        for (int x = -4; x <= -1; x++) {
            w.ledge(x, 0, -4);
        }
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void ledgeWithNoFloorWithinEightIsUnsafe() {
        World w = new World().pit(-2, 0);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void sweptBoxClipsTheCorner() {
        // lava just off the line but inside the body's width
        World w = new World().lava(-2, -1, 1);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.75, 1.5, 0.75));
    }

    @Test
    public void keysFollowTheYaw() {
        // yaw 0 faces +z. foe straight ahead
        assertEquals(new LavaShore.Keys(true, false, false, false), LavaShore.keysToward(0, 0, 3));
        // foe behind
        assertEquals(new LavaShore.Keys(false, true, false, false), LavaShore.keysToward(0, 0, -3));
        // facing +z, a foe at -x is on our right (west is right of south)
        assertEquals(new LavaShore.Keys(false, false, false, true), LavaShore.keysToward(0, -3, 0));
        assertEquals(new LavaShore.Keys(false, false, true, false), LavaShore.keysToward(0, 3, 0));
        // yaw 90 faces -x: a foe at -x is straight ahead
        assertEquals(new LavaShore.Keys(true, false, false, false), LavaShore.keysToward(90, -3, 0));
        // diagonal front left at yaw 0 (foe at +x +z)
        assertEquals(new LavaShore.Keys(true, false, true, false), LavaShore.keysToward(0, 3, 3));
        assertFalse(LavaShore.keysToward(0, 0, 0).any());
    }

    @Test
    public void holdOnlyWhenTheBackIsBadAndTheFrontIsNot() {
        // standing, back unsafe
        assertTrue(LavaShore.hold(true, true, false, false, true, false));
        // standing, back fine
        assertFalse(LavaShore.hold(true, true, false, false, false, false));
        // the cell ahead is bad too
        assertFalse(LavaShore.hold(true, true, false, false, true, true));
        // overworld
        assertFalse(LavaShore.hold(false, true, false, false, true, false));
        // a live path on the ground is the path's business
        assertFalse(LavaShore.hold(true, true, false, true, true, false));
        // airborne after a hit with the back bad
        assertTrue(LavaShore.hold(true, false, true, false, true, false));
        assertFalse(LavaShore.hold(true, false, true, false, false, false));
        // a path wipes the keys each tick, no hold
        assertFalse(LavaShore.hold(true, false, true, true, true, false));
        // airborne with no fresh hit (a jump)
        assertFalse(LavaShore.hold(true, false, false, true, true, false));
    }

    @Test
    public void landingCancelNeedsEverything() {
        assertTrue(LavaShore.cancelOnLanding(true, true, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(false, true, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, false, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, false, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, true, true, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, true, false, false));
    }

    @Test
    public void landingLavaRadiusIsThree() {
        World w = new World().lava(3, -1, 0);
        assertTrue(LavaShore.lavaClose(w, 0.5, 0, 0.5));
        assertFalse(LavaShore.lavaClose(new World().lava(4, -1, 0), 0.5, 0, 0.5));
    }

    @Test
    public void passiveOnlyCaresAboutLavaByTheAnimal() {
        // lava by us and on the line, the cow is on dry stone: baritone paths around it, nothing to wait for
        World w = new World().lava(-1, -1, 0).lava(5, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertFalse(LavaShore.blockedFor(true, w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertTrue(LavaShore.blockedFor(false, w, 0.5, 0, 0.5, 12.5, 0, 0.5));
    }

    @Test
    public void passiveByThePoolIsBlockedAndLetGoOfAtOnce() {
        World w = new World().lava(7, -1, 0);
        assertTrue(LavaShore.blockedFor(true, w, 0.5, 0, 0.5, 6.5, 0, 0.5));
        assertTrue(LavaShore.giveUp(true, 1));
    }

    @Test
    public void hostileWaitEndsAtTheCap() {
        assertFalse(LavaShore.giveUp(false, 1));
        assertFalse(LavaShore.giveUp(false, LavaShore.WAIT_CAP_TICKS - 1));
        assertTrue(LavaShore.giveUp(false, LavaShore.WAIT_CAP_TICKS));
    }

    @Test
    public void rimStepPicksACellWithASafeBack() {
        World w = new World().lava(-3, -1, 0);
        int[] step = LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(step);
        int x = step[0];
        int z = step[1];
        // not the foe's own cell, and the knock from there goes somewhere fine
        assertFalse(x == 1 && z == 0);
        assertFalse(LavaShore.backUnsafe(w, x + 0.5, 0, z + 0.5, 1.5, 0.5));
        assertFalse(LavaShore.lavaNear(w, x + 0.5, 0, z + 0.5, 1));
    }

    @Test
    public void rimStepKeepsAwayFromLavaItCanSee() {
        // lava two cells off the cells on the knock side: those lose to the ones that are not near it
        int[] step = LavaShore.safeStep(new World().lava(-3, -1, 0), 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(step);
        assertTrue(step[0] >= 0);
    }

    @Test
    public void rimStepStaysPutWhenTheSpotIsFine() {
        assertNull(LavaShore.safeStep(new World(), 0.5, 0, 0.5, 1.5, 0.5));
        assertNull(LavaShore.safeStep(new World().lava(-4, -1, 0), 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void rimStepHasNowhereToGoOnABridge() {
        // a one wide bridge: the only free neighbour is the one further down the knock line
        World w = new World().lava(-3, -1, 0);
        for (int x = -1; x <= 1; x++) {
            w.pit(x, 1).pit(x, -1);
        }
        assertNull(LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    // foe at (-1.5, 0.5) so the knock line through cell (0, 0) runs along +x and never touches column (0, 1)
    @Test
    public void aDropBesideACellOffTheKnockLineMakesItNoStep() {
        assertTrue(LavaShore.standable(new World(), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(1, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aPondBesideACellIsFine() {
        World w = new World().pit(0, 1).water(0, -2, 1);
        assertTrue(LavaShore.standable(w, 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aWallBesideACellIsFine() {
        World w = new World().pit(0, 1).solid(0, 0, 1);
        assertTrue(LavaShore.standable(w, 0, 0, 0, -1.5, 0.5));
        // a wall only at head height still stops a knock
        assertTrue(LavaShore.standable(new World().pit(0, 1).solid(0, 1, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aLandingThreeCellsDownCountsAndLavaDoesNot() {
        // first non-open cell at y-3 is solid: fine. lava there is not a landing
        assertTrue(LavaShore.standable(new World().pit(0, 1).solid(0, -3, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1).lava(0, -2, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1).solid(0, -4, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void rimSwingDoesNotWaitForTheGround() {
        assertTrue(LavaShore.swingReady(true, false, false, true));
        assertFalse(LavaShore.swingReady(true, false, false, false));
        assertTrue(LavaShore.swingReady(true, true, false, false));
        assertTrue(LavaShore.swingReady(true, false, true, false));
        // the other gates (eating, the bucket) still hold on a rim
        assertFalse(LavaShore.swingReady(false, true, true, true));
    }

    @Test
    public void shoreWaitEndsBeforeTheFightDoes() {
        // the stall ends the fight and the cube gets picked again with its count running, so the cap has to come first
        assertTrue(LavaShore.WAIT_CAP_TICKS < CombatCommit.STALL_TICKS);
        assertEquals(160, LavaShore.WAIT_CAP_TICKS);
    }
}
