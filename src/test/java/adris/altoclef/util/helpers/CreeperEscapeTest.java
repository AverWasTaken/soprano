package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CreeperEscape.Cells;
import adris.altoclef.util.helpers.CreeperEscape.Heading;
import adris.altoclef.util.helpers.CreeperEscape.Kind;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// the heading for the first ticks of a creeper step: straight away if the next couple of blocks are fine to run over,
// either side of that if not, nothing if nothing is
public class CreeperEscapeTest {

    // flat solid ground with the feet at y 64, everything above it open
    private static class FakeWorld implements Cells {
        final Map<String, Kind> over = new HashMap<>();

        FakeWorld set(int x, int y, int z, Kind k) {
            over.put(x + "," + y + "," + z, k);
            return this;
        }

        // a whole column from y0 up to y1
        FakeWorld column(int x, int z, int y0, int y1, Kind k) {
            for (int y = y0; y <= y1; y++) set(x, y, z, k);
            return this;
        }

        // the whole strip x >= from, wide enough to cover any sideways heading
        FakeWorld wallEast(int from, Kind k) {
            for (int x = from; x <= from + 1; x++) {
                for (int z = -5; z <= 5; z++) column(x, z, 64, 66, k);
            }
            return this;
        }

        @Override
        public Kind kind(int x, int y, int z) {
            Kind k = over.get(x + "," + y + "," + z);
            if (k != null) return k;
            return y <= 63 ? Kind.SOLID : Kind.OPEN;
        }
    }

    // player in the middle of cell (0, 64, 0), creeper three blocks to the west, so away is +x
    private static Heading run(Cells cells) {
        return CreeperEscape.choose(0.5, 64, 0.5, -2.5, 0.5, cells);
    }

    @Test
    public void openGroundIsStraightAway() {
        Heading h = run(new FakeWorld());
        assertNotNull(h);
        assertEquals(0, h.degrees());
        assertEquals(1, h.dx(), 1e-9);
        assertEquals(0, h.dz(), 1e-9);
        assertFalse(h.jump());
    }

    @Test
    public void yawFollowsMinecraftsConvention() {
        // 0 faces +z, 90 faces -x, so +x is -90 and -z is 180
        assertEquals(-90, new Heading(0, 1, 0, false).yaw(), 1e-4);
        assertEquals(0, new Heading(0, 0, 1, false).yaw(), 1e-4);
        assertEquals(90, new Heading(0, -1, 0, false).yaw(), 1e-4);
        assertEquals(180, Math.abs(new Heading(0, 0, -1, false).yaw()), 1e-4);
    }

    @Test
    public void lavaStraightAwayFansToASide() {
        Heading h = run(new FakeWorld().set(1, 63, 0, Kind.DANGER));
        assertNotNull(h);
        assertEquals(45, h.degrees());
    }

    @Test
    public void aDeepDropStraightAwayFansToASide() {
        FakeWorld world = new FakeWorld();
        // a hole four deep (the floor is five down): too far to fall
        world.column(1, 0, 59, 63, Kind.OPEN);
        world.column(2, 0, 59, 63, Kind.OPEN);
        Heading h = run(world);
        assertNotNull(h);
        assertEquals(45, h.degrees());
    }

    @Test
    public void aSmallDropIsFine() {
        // three blocks down is no damage
        FakeWorld world = new FakeWorld();
        world.column(1, 0, 61, 63, Kind.OPEN);
        world.column(2, 0, 61, 63, Kind.OPEN);
        assertEquals(0, run(world).degrees());
    }

    @Test
    public void everyHazardKindIsRefusedAsFloorAndAsInTheWay() {
        // danger in the cell one block ahead. it is a corner of both diagonals too, so only sideways is left
        assertEquals(90, run(new FakeWorld().set(1, 64, 0, Kind.DANGER)).degrees());
        // danger at head height
        assertEquals(90, run(new FakeWorld().set(1, 65, 0, Kind.DANGER)).degrees());
    }

    @Test
    public void waterIsFineToRunIntoAndToLandIn() {
        FakeWorld world = new FakeWorld().set(1, 63, 0, Kind.WATER).set(2, 63, 0, Kind.WATER);
        assertEquals(0, run(world).degrees());
        // swimming through it
        world = new FakeWorld().set(1, 64, 0, Kind.WATER).set(2, 64, 0, Kind.WATER);
        assertEquals(0, run(world).degrees());
    }

    @Test
    public void aWallTwoHighIsNotRunInto() {
        FakeWorld world = new FakeWorld();
        world.column(1, 0, 64, 65, Kind.SOLID);
        Heading h = run(world);
        assertNotNull(h);
        // the wall is a corner of both diagonals, so it goes along it
        assertEquals(90, h.degrees());
    }

    @Test
    public void aOneBlockStepIsAJump() {
        Heading h = run(new FakeWorld().set(1, 64, 0, Kind.SOLID));
        assertNotNull(h);
        assertEquals(0, h.degrees());
        assertTrue(h.jump());
    }

    @Test
    public void aStepFurtherOnIsNotAJumpYet() {
        Heading h = run(new FakeWorld().set(2, 64, 0, Kind.SOLID));
        assertEquals(0, h.degrees());
        assertFalse(h.jump());
    }

    @Test
    public void aStepWithALowCeilingOverItIsAWall() {
        FakeWorld world = new FakeWorld().set(1, 64, 0, Kind.SOLID).set(1, 66, 0, Kind.SOLID);
        assertEquals(90, run(world).degrees());
    }

    @Test
    public void aLowCeilingOverUsMeansNoJump() {
        FakeWorld world = new FakeWorld().set(1, 64, 0, Kind.SOLID).set(0, 66, 0, Kind.SOLID);
        assertEquals(90, run(world).degrees());
    }

    @Test
    public void aGapOnlyOneHighIsNotRunInto() {
        assertEquals(90, run(new FakeWorld().set(1, 65, 0, Kind.SOLID)).degrees());
    }

    @Test
    public void aDiagonalThroughAWallCornerIsRefused() {
        // away is +x +z, and both the +x cell and the +z cell are wall: nothing squeezes between them
        FakeWorld world = new FakeWorld();
        world.column(1, 0, 64, 65, Kind.SOLID);
        world.column(0, 1, 64, 65, Kind.SOLID);
        Heading h = CreeperEscape.choose(0.5, 64, 0.5, -2.5, -2.5, world);
        assertTrue(h == null || h.degrees() != 0);
    }

    @Test
    public void boxedInIsNone() {
        // walls on every side but the creeper's
        FakeWorld world = new FakeWorld().wallEast(1, Kind.SOLID);
        for (int z = -5; z <= 5; z++) world.column(0, z, 64, 66, Kind.SOLID);
        world.set(0, 64, 0, Kind.OPEN).set(0, 65, 0, Kind.OPEN).set(0, 66, 0, Kind.OPEN);
        assertNull(run(world));
    }

    @Test
    public void onlyTheWayBackIsNone() {
        // the whole east half is lava, the west is the creeper: nothing that gains distance is open
        FakeWorld world = new FakeWorld();
        for (int x = 0; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) world.set(x, 63, z, Kind.DANGER);
        }
        world.set(0, 63, 0, Kind.SOLID);
        assertNull(run(world));
    }

    @Test
    public void noHeadingEverLosesGround() {
        // spin the creeper all the way round the player over ground with some junk on it: whatever comes back never ends
        // up closer to the creeper than it started
        FakeWorld world = new FakeWorld();
        world.set(1, 63, 1, Kind.DANGER).set(-1, 64, 1, Kind.SOLID).set(2, 64, -1, Kind.SOLID).set(0, 63, -2, Kind.DANGER);
        for (int deg = 0; deg < 360; deg += 5) {
            double cx = 0.5 + 3 * Math.cos(Math.toRadians(deg));
            double cz = 0.5 + 3 * Math.sin(Math.toRadians(deg));
            Heading h = CreeperEscape.choose(0.5, 64, 0.5, cx, cz, world);
            if (h == null) continue;
            double before = Math.hypot(0.5 - cx, 0.5 - cz);
            double after = Math.hypot(0.5 + h.dx() * 2 - cx, 0.5 + h.dz() * 2 - cz);
            assertTrue("deg " + deg + " went from " + before + " to " + after, after > before);
        }
    }

    // the same flat run, underground: a ground cell with a negative y is still ground
    @Test
    public void belowYZeroIsJustGround() {
        Cells deep = (x, y, z) -> y <= -21 ? Kind.SOLID : Kind.OPEN;
        Heading h = CreeperEscape.choose(0.5, -20, 0.5, -2.5, 0.5, deep);
        assertNotNull(h);
        assertEquals(0, h.degrees());
    }

    @Test
    public void aFenceIsAWallNotAStep() {
        Heading h = run(new FakeWorld().set(1, 64, 0, Kind.WALL));
        assertNotNull(h);
        assertFalse(h.degrees() == 0);
    }

    @Test
    public void standingOnATallFloorIsNotAStep() {
        // farmland and friends put y a hair under the next block up
        Heading h = CreeperEscape.choose(0.5, 63.9375, 0.5, -2.5, 0.5, new FakeWorld());
        assertNotNull(h);
        assertEquals(0, h.degrees());
        assertFalse(h.jump());
    }

    @Test
    public void aCreeperOnTopOfUsHasNoAway() {
        assertNull(CreeperEscape.choose(0.5, 64, 0.5, 0.5, 0.5, new FakeWorld()));
    }

    @Test
    public void straightAwayIsPreferredOverEverythingElse() {
        // anything else being open does not matter while straight is fine
        FakeWorld world = new FakeWorld().set(0, 63, 1, Kind.DANGER).set(0, 63, -1, Kind.DANGER);
        assertEquals(0, run(world).degrees());
    }

    @Test
    public void bothSidesFanTried() {
        // 45 one way is lava, 45 the other way is open: it takes the other one
        FakeWorld world = new FakeWorld().set(1, 63, 0, Kind.DANGER);
        // away +x rotated by +45 goes +x +z, so block that diagonal and expect the mirror
        world.set(1, 63, 1, Kind.DANGER);
        Heading h = run(world);
        assertNotNull(h);
        assertEquals(-45, h.degrees());
    }
}
