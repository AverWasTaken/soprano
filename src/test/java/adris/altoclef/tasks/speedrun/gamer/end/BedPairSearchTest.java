package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.BedPairSearch.Cell;
import adris.altoclef.tasks.speedrun.gamer.end.BedPairSearch.Dragon;
import adris.altoclef.tasks.speedrun.gamer.end.BedPairSearch.Pair;
import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch.Box;
import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch.Parts;
import java.util.List;
import org.junit.Test;

public class BedPairSearchTest {
    private static final double P = 64;
    private static final int FLOOR = 61;

    // end stone up to y 60 everywhere, the bedrock pillar in the middle up to the perch
    private static final BedPairSearch.Terrain FLAT = new BedPairSearch.Terrain() {
        private boolean solid(int x, int y, int z) {
            return y < FLOOR || (x == 0 && z == 0 && y < P);
        }

        @Override
        public boolean bedCell(int x, int y, int z) {
            return !solid(x, y, z) && solid(x, y - 1, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return !solid(x, y, z) && !solid(x, y + 1, z) && solid(x, y - 1, z);
        }
    };

    private static final BedPairSearch.Terrain VOID = new BedPairSearch.Terrain() {
        @Override
        public boolean bedCell(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return false;
        }
    };

    private static Dragon dragon(double yaw, boolean cloudUp) {
        Parts p = DragonPerch.sitting(0.5, P, 0.5, yaw);
        double[] c = DragonPerch.cloudCentre(0.5, 0.5, p.head().centreX(), p.head().centreZ());
        return new Dragon(p.eaters(), p.pushes(), p.biters(), p.head().centreX(), p.head().minY(), p.head().centreZ(),
                p.neck().centreX(), p.neck().minY(), p.neck().centreZ(), c[0], c[1], cloudUp);
    }

    // where the bot stands when the roar starts: on the ring, in front of the head (the scan turned it to face us)
    private static double[] botInFront(double yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{0.5 + Math.sin(r) * DragonWaitRules.BED_AIM, 0.5 - Math.cos(r) * DragonWaitRules.BED_AIM};
    }

    private static void assertGoodPair(Dragon d, Pair p, double botX, double botZ) {
        assertNotNull(p);
        assertTrue(p.dragonDamage() >= BedPairSearch.MIN_DRAGON_DAMAGE);
        assertEquals(p.foot().x() + p.dirX(), p.head().x());
        assertEquals(p.foot().z() + p.dirZ(), p.head().z());
        assertEquals(p.foot().y(), p.head().y());
        Cell s = p.stand();
        assertTrue(DragonPerch.standsClear(DragonPerch.player(s.x() + 0.5, s.y(), s.z() + 0.5), d.pushes(), d.biters(),
                BedPairSearch.STAND_MARGIN));
        assertTrue(Math.hypot(s.x() + 0.5 - d.cloudX(), s.z() + 0.5 - d.cloudZ()) >= BedPairSearch.CLOUD_KEEP_OUT);
        assertTrue(BedPairSearch.headDistance(s, p.head()) >= BedPairSearch.HEAD_KEEP_OUT);
        assertTrue(BedPairSearch.inReach(s, p.foot()));
        assertTrue(BedPairSearch.facesAlong(s, p.foot(), new int[]{p.dirX(), p.dirZ()}));
        assertTrue(BedPairSearch.cellClear(d.eaters(), p.head()));
        assertTrue(BedPairSearch.cellClear(d.eaters(), p.foot()));
        assertTrue(Math.hypot(s.x() + 0.5 - botX, s.z() + 0.5 - botZ) <= BedPairSearch.SEARCH_RADIUS);
    }

    @Test
    public void findsAPairOnAFlatFloorAtEveryTestedYaw() {
        for (double yaw : new double[]{0, 5, 10, 25, 35, 45, 90, 180, 270}) {
            Dragon d = dragon(yaw, false);
            double[] bot = botInFront(yaw);
            Pair p = BedPairSearch.search(d, FLAT, bot[0], bot[1]);
            assertGoodPair(d, p, bot[0], bot[1]);
            // the head half sits on the floor under the head, not up on the pillar
            assertEquals(FLOOR, p.head().y());
        }
    }

    @Test
    public void nothingToStandOnIsNoPair() {
        double[] bot = botInFront(0);
        assertNull(BedPairSearch.search(dragon(0, false), VOID, bot[0], bot[1]));
    }

    @Test
    public void tooFarFromEverythingIsNoPair() {
        assertNull(BedPairSearch.search(dragon(0, false), FLAT, 60, 60));
    }

    // the found pair, then the same stand with one rule broken at a time
    @Test
    public void rejectsAStandInAWingABiteOrTheCloud() {
        Dragon d = dragon(0, false);
        double[] bot = botInFront(0);
        Pair p = BedPairSearch.search(d, FLAT, bot[0], bot[1]);
        assertNotNull(p);
        int[] dir = {p.dirX(), p.dirZ()};
        Cell s = p.stand();
        assertTrue(BedPairSearch.standOk(d, FLAT, p.head(), p.foot(), dir, s, bot[0], bot[1]));

        List<Box> wingOnUs = List.of(Box.cell(s.x(), s.y(), s.z()));
        Dragon inWing = new Dragon(d.eaters(), wingOnUs, d.biters(), d.headX(), d.headY(), d.headZ(),
                d.neckX(), d.neckY(), d.neckZ(), d.cloudX(), d.cloudZ(), false);
        assertFalse(BedPairSearch.standOk(inWing, FLAT, p.head(), p.foot(), dir, s, bot[0], bot[1]));

        Dragon inBite = new Dragon(d.eaters(), d.pushes(), wingOnUs, d.headX(), d.headY(), d.headZ(),
                d.neckX(), d.neckY(), d.neckZ(), d.cloudX(), d.cloudZ(), false);
        assertFalse(BedPairSearch.standOk(inBite, FLAT, p.head(), p.foot(), dir, s, bot[0], bot[1]));

        Dragon inCloud = new Dragon(d.eaters(), d.pushes(), d.biters(), d.headX(), d.headY(), d.headZ(),
                d.neckX(), d.neckY(), d.neckZ(), s.x() + 3.5, s.z() + 0.5, false);
        assertFalse(BedPairSearch.standOk(inCloud, FLAT, p.head(), p.foot(), dir, s, bot[0], bot[1]));
    }

    // once the cloud is up the walk to the stand may not go through it, before that it may
    @Test
    public void aWalkThroughTheCloudOnlyCountsOnceItIsUp() {
        Dragon d = dragon(0, false);
        double[] bot = botInFront(0);
        Pair p = BedPairSearch.search(d, FLAT, bot[0], bot[1]);
        assertNotNull(p);
        int[] dir = {p.dirX(), p.dirZ()};
        Cell s = p.stand();
        // a bot on the far side of the cloud from the stand
        double bx = d.cloudX() - (s.x() + 0.5 - d.cloudX());
        double bz = d.cloudZ() - (s.z() + 0.5 - d.cloudZ());
        Dragon up = dragon(0, true);
        assertTrue(BedPairSearch.standOk(d, FLAT, p.head(), p.foot(), dir, s, bx, bz));
        assertFalse(BedPairSearch.standOk(up, FLAT, p.head(), p.foot(), dir, s, bx, bz));
    }

    @Test
    public void theHeadOrNeckHasToTakeAtLeastFortyFive() {
        Dragon d = dragon(0, false);
        // straight under the head box: 1.5 down from the head's position
        assertTrue(BedPairSearch.dragonDamage(d, new Cell(0, FLOOR, -7)) >= BedPairSearch.MIN_DRAGON_DAMAGE);
        // five blocks out is a scratch
        assertTrue(BedPairSearch.dragonDamage(d, new Cell(0, FLOOR, -12)) < BedPairSearch.MIN_DRAGON_DAMAGE);
    }

    @Test
    public void theFacingNeedsAClearAxis() {
        Cell f = new Cell(0, FLOOR, 0);
        assertTrue(BedPairSearch.facesAlong(new Cell(-4, FLOOR, 0), f, new int[]{1, 0}));
        assertFalse(BedPairSearch.facesAlong(new Cell(-4, FLOOR, 0), f, new int[]{-1, 0}));
        // a diagonal rounds either way, no good
        assertFalse(BedPairSearch.facesAlong(new Cell(-3, FLOOR, -3), f, new int[]{1, 0}));
    }

    @Test
    public void segmentDistanceClampsToTheEnds() {
        assertEquals(1, BedPairSearch.segmentDistance(0, 0, 10, 0, 5, 1), 1e-9);
        assertEquals(5, BedPairSearch.segmentDistance(0, 0, 10, 0, -3, 4), 1e-9);
    }

    // the floor bed's best case, what the phase uses to say beds are off in this armor
    @Test
    public void noPairBeatsTheBestSelfDamage() {
        assertEquals(23.4, BedPairSearch.BEST_SELF_DAMAGE, 0.1);
        for (double yaw : new double[]{0, 25, 45, 90}) {
            double[] bot = botInFront(yaw);
            Pair p = BedPairSearch.search(dragon(yaw, false), FLAT, bot[0], bot[1]);
            assertTrue(p.selfDamage() >= BedPairSearch.BEST_SELF_DAMAGE - 1e-9);
        }
        // full iron at full health is out, diamond is in
        assertFalse(BedSafety.survives(20, 0, BedPairSearch.BEST_SELF_DAMAGE, 2, 15, 0));
        assertTrue(BedSafety.survives(20, 0, BedPairSearch.BEST_SELF_DAMAGE, 2, 20, 8));
    }
}
