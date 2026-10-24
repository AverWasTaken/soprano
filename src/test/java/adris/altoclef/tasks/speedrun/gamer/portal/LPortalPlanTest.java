package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Cast;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Inv;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Kind;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Round;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Scoop;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class LPortalPlanTest {
    // a flat plain: stone up to y=63 (the top stone layer is layer 0), air above, with overrides
    static final class World implements LPortalPlan.Terrain {
        final Map<P, Mat> over = new HashMap<>();

        @Override
        public Mat at(int x, int y, int z) {
            Mat m = over.get(new P(x, y, z));
            if (m != null) {
                return m;
            }
            return y <= 63 ? Mat.SOLID : Mat.AIR;
        }

        void set(Layout l, int[] c, Mat m) {
            over.put(l.cell(c), m);
        }

        void set(Layout l, int u, int y, int d, Mat m) {
            over.put(l.cell(u, y, d), m);
        }

        List<P> lava() {
            List<P> out = new ArrayList<>();
            for (Map.Entry<P, Mat> e : over.entrySet()) {
                if (e.getValue().lava()) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
    }

    // the row runs along +x at z=10, the shore is z>=11, the pool is a 30 x 11 block behind it (x 10..39, z 0..10), one
    // layer of lava (y 63) on a stone floor
    static final Layout L = new Layout(10, 63, 10, 1, 0, 0, 1);
    static final Layout L_FLIPPED = new Layout(10, 63, 10, 0, 1, -1, 0);

    static World pool() {
        World w = new World();
        for (int x = 10; x < 40; x++) {
            for (int z = 0; z <= 10; z++) {
                w.over.put(new P(x, 63, z), Mat.LAVA);
            }
        }
        return w;
    }

    static Inv bag(int lava, boolean water, int empty, int blocks) {
        return new Inv(lava, water, empty, blocks, true);
    }

    // the base as it stands once it is done: corners and the platform obsidian, the pit empty
    static void baseDone(World w) {
        w.set(L, LPortalPlan.CORNER_LEFT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.CORNER_RIGHT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_LEFT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_RIGHT, Mat.OBSIDIAN);
        w.set(L, 1, 0, 0, Mat.AIR);
        w.set(L, 2, 0, 0, Mat.AIR);
    }

    static void walls(World w) {
        for (int[] c : LPortalPlan.WALLS) {
            w.set(L, c, Mat.SOLID);
        }
        w.set(L, LPortalPlan.ARM, Mat.SOLID);
    }

    static void floorOut(World w) {
        for (int[] b : LPortalPlan.BOTTOM) {
            w.set(L, b, Mat.AIR);
        }
    }

    static World built() {
        World w = pool();
        baseDone(w);
        walls(w);
        floorOut(w);
        return w;
    }

    @Test
    public void layoutMapsLocalToWorld() {
        assertEquals(new P(12, 66, 11), L.cell(2, 3, 1));
        assertEquals(new P(9, 66, 12), L_FLIPPED.cell(2, 3, 1));
        int[] d = L_FLIPPED.dir(0, 0, 1);
        assertEquals(-1, d[0]);
        assertEquals(0, d[2]);
    }

    @Test
    public void castsAndCornersAreTheTenFrameCells() {
        List<int[]> have = new ArrayList<>();
        int casts = 0;
        for (Round r : LPortalPlan.ROUNDS) {
            for (Cast c : r.casts()) {
                have.add(c.t());
                casts++;
            }
        }
        have.add(LPortalPlan.CORNER_LEFT);
        have.add(LPortalPlan.CORNER_RIGHT);
        // the frame one layer under the corners: bottom y=-1 under the pit, side columns y=0..2, top y=3
        int[][] frame = {{1, -1, 0}, {2, -1, 0}, {0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {3, 0, 0}, {3, 1, 0}, {3, 2, 0}, {1, 3, 0}, {2, 3, 0}};
        for (int[] want : frame) {
            boolean found = false;
            for (int[] c : have) {
                found |= c[0] == want[0] && c[1] == want[1] && c[2] == want[2];
            }
            assertTrue("frame cell " + want[0] + "," + want[1], found);
        }
        // the corners are the wash's, so eight lava buckets
        assertEquals(8, casts);
    }

    @Test
    public void everyCastClicksABlockNextToItThatWillExist() {
        List<int[]> made = new ArrayList<>();
        for (Round r : LPortalPlan.ROUNDS) {
            for (Cast c : r.casts()) {
                int[] t = c.t();
                int[] s = c.support();
                assertEquals(c.name() + " support is next to it", 1, Math.abs(t[0] - s[0]) + Math.abs(t[1] - s[1]) + Math.abs(t[2] - s[2]));
                boolean ok = s[1] == -1 && s[2] == 1 || same(s, LPortalPlan.ARM);
                for (int[] w : LPortalPlan.WALLS) {
                    ok |= same(s, w);
                }
                for (int[] e : made) {
                    ok |= same(s, e);
                }
                assertTrue(c.name() + " support must be a wall, the arm, the shore floor or a cell made before it", ok);
                assertEquals("we stand on the platform, in front of the frame", 1, c.stand()[1]);
                made.add(t);
            }
        }
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    @Test
    public void theSourceIsPouredBehindTheFrameAgainstTheTopOfTheRightPillar() {
        Round r = LPortalPlan.ROUNDS.get(0);
        int[] s = r.source();
        int[] sup = r.sourceSupport();
        assertEquals(1, Math.abs(s[0] - sup[0]) + Math.abs(s[1] - sup[1]) + Math.abs(s[2] - sup[2]));
        boolean wall = false;
        for (int[] w : LPortalPlan.WALLS) {
            wall |= same(sup, w);
        }
        assertTrue(wall);
        // one block behind the frame plane, at the top of the interior's right column
        assertEquals(1, s[2]);
        assertEquals(2, s[0]);
        assertEquals(3, s[1]);
    }

    @Test
    public void theFirstThingIsTheBlockInTheLavaNotALavaTrip() {
        assertEquals(Kind.PLACE_STACK, LPortalPlan.next(L, pool(), bag(1, true, 2, 64)).kind());
        assertEquals("no lava needed for the base", Kind.PLACE_STACK, LPortalPlan.next(L, pool(), bag(0, true, 2, 64)).kind());
    }

    @Test
    public void lavaIsScoopedOnlyOnceEverythingIsReadyForItAndTheBagHasNone() {
        World w = built();
        assertEquals(Kind.SCOOP, LPortalPlan.next(L, w, bag(0, true, 2, 64)).kind());
        assertEquals(Kind.WATER_PLACE, LPortalPlan.next(L, w, bag(1, true, 2, 64)).kind());
        World early = pool();
        baseDone(early);
        assertEquals("the walls come first", Kind.PLACE_BLOCK, LPortalPlan.next(L, early, bag(0, true, 2, 64)).kind());
    }

    @Test
    public void thePlaceholderGoesInFromTheFloor() {
        World w = pool();
        Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.PLACE_STACK, s.kind());
        assertEquals(L.cell(1, 0, 0), s.cell());
        assertEquals(L.cell(1, -1, 0), s.support());
        assertEquals(1, s.fy());
        assertEquals(L.cell(1, 1, 1), s.stand());
    }

    @Test
    public void theWaterGoesInBesideThePlaceholderAndComesBackOnceBothCornersAreSet() {
        World w = pool();
        w.set(L, 1, 0, 0, Mat.SOLID);
        Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.WATER_PLACE, s.kind());
        assertEquals(L.cell(2, 0, 0), s.cell());
        assertEquals(L.cell(1, 0, 0), s.support());
        // clicking the +u face of the placeholder
        assertEquals(1, s.fx());
        assertEquals(0, s.fy());
        w.set(L, 2, 0, 0, Mat.WATER);
        assertEquals("the right corner is still lava", Kind.WAIT, LPortalPlan.next(L, w, bag(3, false, 1, 64)).kind());
        w.set(L, LPortalPlan.CORNER_RIGHT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_RIGHT, Mat.OBSIDIAN);
        s = LPortalPlan.next(L, w, bag(3, false, 1, 64));
        assertEquals(Kind.BREAK, s.kind());
        assertEquals(L.cell(1, 0, 0), s.cell());
        // the block is out, the water runs in
        w.set(L, 1, 0, 0, Mat.WATER_FLOW);
        assertEquals(Kind.WAIT, LPortalPlan.next(L, w, bag(3, false, 1, 64)).kind());
        w.set(L, LPortalPlan.CORNER_LEFT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_LEFT, Mat.OBSIDIAN);
        s = LPortalPlan.next(L, w, bag(3, false, 1, 64));
        assertEquals(Kind.WATER_SCOOP, s.kind());
        assertEquals(L.cell(2, 0, 0), s.cell());
        // taken back, the base is done
        w.set(L, 2, 0, 0, Mat.AIR);
        w.set(L, 1, 0, 0, Mat.AIR);
        assertEquals(Kind.PLACE_BLOCK, LPortalPlan.next(L, w, bag(3, true, 0, 64)).kind());
    }

    @Test
    public void wallsGoUpBehindTheColumnsBottomUp() {
        World w = pool();
        baseDone(w);
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
            if (s.kind() != Kind.PLACE_BLOCK) {
                break;
            }
            order.append(s.cell().x() - 10).append(',').append(s.cell().y() - 63).append(',').append(s.cell().z() - 10).append(' ');
            w.over.put(s.cell(), Mat.SOLID);
        }
        assertEquals("0,1,1 0,2,1 1,1,1 3,1,1 3,2,1 3,3,1 ", order.toString());
        // then the arm, from the platform, against the top of the right pillar
        Step arm = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.PLACE_STACK, arm.kind());
        assertEquals(L.cell(3, 3, 0), arm.cell());
        assertEquals(L.cell(3, 3, 1), arm.support());
        assertEquals(L.cell(2, 1, -1), arm.stand());
        assertEquals(-1, arm.fz());
    }

    @Test
    public void theFloorUnderThePitComesOutBeforeAnyWater() {
        World w = pool();
        baseDone(w);
        walls(w);
        Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.BREAK, s.kind());
        assertEquals(L.cell(1, -1, 0), s.cell());
        assertEquals(L.cell(1, 1, -1), s.stand());
        w.set(L, 1, -1, 0, Mat.AIR);
        assertEquals(L.cell(2, -1, 0), LPortalPlan.next(L, w, bag(3, true, 0, 64)).cell());
        w.set(L, 2, -1, 0, Mat.AIR);
        assertEquals(Kind.WATER_PLACE, LPortalPlan.next(L, w, bag(3, true, 0, 64)).kind());
    }

    @Test
    public void noLavaToScoopMeansNoTripAndTheBuildGoesOn() {
        assertEquals(Kind.PLACE_STACK, LPortalPlan.next(L, pool(), new Inv(1, true, 2, 64, false)).kind());
        // ready to pour, bag without lava, nothing to scoop: it pours the water rather than walking off
        assertEquals(Kind.WATER_PLACE, LPortalPlan.next(L, built(), new Inv(0, true, 2, 64, false)).kind());
    }

    @Test
    public void lavaThatWentInDryComesBackBeforeAnotherTrip() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        w.set(L, 3, 1, 0, Mat.LAVA);
        Step s = LPortalPlan.next(L, w, new Inv(0, false, 2, 64, true));
        assertEquals(Kind.LAVA_UNDO, s.kind());
        assertEquals(L.cell(3, 1, 0), s.cell());
    }

    @Test
    public void scoopsOneBucketAtATimeAndOnlyWhenTheBagHasNone() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        assertTrue("holding lava: no trip", LPortalPlan.next(L, w, new Inv(1, false, 1, 64, true)).kind() != Kind.SCOOP);
        assertEquals(Kind.SCOOP, LPortalPlan.next(L, w, new Inv(0, false, 2, 64, true)).kind());
        assertTrue("no empty bucket to fill", LPortalPlan.next(L, w, new Inv(0, false, 0, 64, true)).kind() != Kind.SCOOP);
    }

    @Test
    public void twoBucketsAreEnoughThroughTheWholeBuild() {
        // the bag a real game has after the gate: a water bucket and an empty one, no lava
        World w = pool();
        Inv inv = new Inv(0, true, 1, 64, true);
        // base water goes in (the water bucket empties), comes back out of the pit with the other bucket
        w.set(L, 1, 0, 0, Mat.SOLID);
        assertEquals(Kind.WATER_PLACE, LPortalPlan.next(L, w, inv).kind());
        inv = new Inv(0, false, 2, 64, true);
        w.set(L, 2, 0, 0, Mat.WATER);
        w.set(L, 1, 0, 0, Mat.WATER_FLOW);
        w.set(L, LPortalPlan.CORNER_LEFT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.CORNER_RIGHT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_LEFT, Mat.OBSIDIAN);
        w.set(L, LPortalPlan.PLATFORM_RIGHT, Mat.OBSIDIAN);
        assertEquals(Kind.WATER_SCOOP, LPortalPlan.next(L, w, inv).kind());
        // walls, arm, floor done: lava first (one bucket), then the pour
        World b = built();
        assertEquals(Kind.SCOOP, LPortalPlan.next(L, b, new Inv(0, true, 1, 64, true)).kind());
        assertEquals(Kind.WATER_PLACE, LPortalPlan.next(L, b, new Inv(1, true, 0, 64, true)).kind());
        // source out: the water bucket is empty now, so a lava bucket and one empty. each cast empties the lava bucket and the
        // next one is a trip
        b.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        b.set(L, 2, 1, 0, Mat.WATER_FLOW);
        assertEquals(Kind.LAVA_PLACE, LPortalPlan.next(L, b, new Inv(1, false, 1, 64, true)).kind());
        assertEquals(Kind.SCOOP, LPortalPlan.next(L, b, new Inv(0, false, 2, 64, true)).kind());
        // the last pickup needs an empty bucket, and there are two
        World done = built();
        for (LPortalPlan.Round r : LPortalPlan.ROUNDS) {
            for (Cast c : r.casts()) {
                done.set(L, c.t(), Mat.OBSIDIAN);
            }
        }
        done.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        assertEquals(Kind.WATER_SCOOP, LPortalPlan.next(L, done, new Inv(0, false, 2, 64, true)).kind());
    }

    @Test
    public void noBlocksIsStuck() {
        assertEquals(Kind.STUCK, LPortalPlan.next(L, pool(), bag(3, true, 0, 0)).kind());
        World b = pool();
        baseDone(b);
        assertEquals(Kind.STUCK, LPortalPlan.next(L, b, bag(3, true, 0, 0)).kind());
    }

    @Test
    public void theSourceGoesInBehindTheTopRightOfTheInterior() {
        World w = built();
        Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.WATER_PLACE, s.kind());
        assertEquals(L.cell(2, 3, 1), s.cell());
        assertEquals(L.cell(3, 3, 1), s.support());
        // the pillar is to the right of the cell, so the face we click looks back along -u
        assertEquals(-1, s.fx());
        assertEquals(0, s.fz());
        assertEquals(L.cell(2, 1, -1), s.stand());
    }

    @Test
    public void castsOnlyWhileWaterIsNextToTheCell() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        assertEquals(Kind.WAIT, LPortalPlan.next(L, w, bag(3, false, 1, 64)).kind());
        // the curtain reaches the interior cell next to the right column
        w.set(L, 2, 1, 0, Mat.WATER_FLOW);
        Step s = LPortalPlan.next(L, w, bag(3, false, 1, 64));
        assertEquals(Kind.LAVA_PLACE, s.kind());
        assertEquals("the right column goes first, it leaks", L.cell(3, 1, 0), s.cell());
        assertEquals(L.cell(3, 1, 1), s.support());
        assertEquals(L.cell(2, 1, -1), s.stand());
    }

    @Test
    public void theTopRightWaitsForTheRightColumnAndADryCellDoesNotBlockTheWetOnes() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        // only the top right has water next to it: it still waits for r1 and r2, they are dry
        w.set(L, 2, 3, 1, Mat.WATER);
        w.set(L, 2, 3, 0, Mat.WATER_FLOW);
        Step s = LPortalPlan.next(L, w, bag(3, false, 1, 64));
        assertEquals(Kind.WAIT, s.kind());
        assertTrue(s.why().contains("r1"));
        // the left column's water shows up first: it goes ahead of the dry right one
        w.set(L, 1, 1, 0, Mat.WATER_FLOW);
        s = LPortalPlan.next(L, w, bag(3, false, 1, 64));
        assertEquals(Kind.LAVA_PLACE, s.kind());
        assertEquals(L.cell(0, 1, 0), s.cell());
    }

    @Test
    public void waterAboveOrBesideCountsAndWaterBelowDoesNot() {
        World w = pool();
        assertFalse(LPortalPlan.wet(L, w, new int[]{0, 2, 0}));
        w.set(L, 0, 1, 0, Mat.WATER_FLOW);
        assertFalse("below does not count", LPortalPlan.wet(L, w, new int[]{0, 2, 0}));
        w.set(L, 0, 3, 0, Mat.WATER_FLOW);
        assertTrue(LPortalPlan.wet(L, w, new int[]{0, 2, 0}));
        World side = pool();
        side.set(L, 1, 2, 0, Mat.WATER_FLOW);
        assertTrue(LPortalPlan.wet(L, side, new int[]{0, 2, 0}));
        World front = pool();
        front.set(L, 0, 2, -1, Mat.WATER_FLOW);
        assertTrue("the pool side counts too", LPortalPlan.wet(L, front, new int[]{0, 2, 0}));
    }

    @Test
    public void castOrderFollowsTheWaterAndTheSourceComesBackAtTheEnd() {
        World w = built();
        // water next to every cell on the pool side and over the pit, so only the order limits it
        for (int u = 0; u <= 3; u++) {
            for (int y = -1; y <= 4; y++) {
                w.set(L, u, y, -1, y < 0 ? Mat.SOLID : Mat.WATER_FLOW);
            }
        }
        w.set(L, 1, 0, 0, Mat.WATER_FLOW);
        w.set(L, 2, 0, 0, Mat.WATER_FLOW);
        w.set(L, 1, 0, -1, Mat.OBSIDIAN);
        w.set(L, 2, 0, -1, Mat.OBSIDIAN);
        StringBuilder order = new StringBuilder();
        Inv inv = bag(3, true, 0, 64);
        for (int i = 0; i < 40; i++) {
            Step s = LPortalPlan.next(L, w, inv);
            if (s.kind() == Kind.WATER_PLACE) {
                order.append("S").append(roundOf(s.cell())).append(' ');
                w.over.put(s.cell(), Mat.WATER);
                inv = bag(3, false, 1, 64);
            } else if (s.kind() == Kind.WATER_SCOOP) {
                order.append("-S").append(roundOf(s.cell())).append(' ');
                w.over.remove(s.cell());
                inv = bag(3, true, 0, 64);
            } else if (s.kind() == Kind.LAVA_PLACE) {
                for (Round r : LPortalPlan.ROUNDS) {
                    for (Cast c : r.casts()) {
                        if (L.cell(c.t()).equals(s.cell())) {
                            order.append(c.name()).append(' ');
                        }
                    }
                }
                w.over.put(s.cell(), Mat.OBSIDIAN);
            } else {
                break;
            }
        }
        assertEquals("Sa r1 r2 l1 l2 b2 b1 t2 t1 -Sa ", order.toString());
    }

    private static String roundOf(P source) {
        for (Round r : LPortalPlan.ROUNDS) {
            if (L.cell(r.source()).equals(source)) {
                return r.name();
            }
        }
        return "?";
    }

    @Test
    public void afterTheLastCastItWaitsForTheDrainThenLights() {
        World w = built();
        for (Round r : LPortalPlan.ROUNDS) {
            for (Cast c : r.casts()) {
                w.set(L, c.t(), Mat.OBSIDIAN);
            }
        }
        w.set(L, 1, 2, 0, Mat.WATER_FLOW);
        assertEquals(Kind.WAIT, LPortalPlan.next(L, w, bag(0, true, 0, 64)).kind());
        w.over.remove(L.cell(1, 2, 0));
        Step s = LPortalPlan.next(L, w, bag(0, true, 0, 64));
        assertEquals(Kind.LIGHT, s.kind());
        // the fire goes on the top of the bottom row, which is one under the pit
        assertEquals(L.cell(1, -1, 0), s.support());
        assertEquals(L.cell(1, 0, 0), s.cell());
        w.set(L, 1, 0, 0, Mat.PORTAL);
        assertEquals(Kind.DONE, LPortalPlan.next(L, w, bag(0, true, 0, 64)).kind());
    }

    @Test
    public void scaffoldingInTheFrameOrOnThePlatformLaneIsBroken() {
        World w = built();
        w.set(L, 1, 2, 0, Mat.SOLID);
        Step s = LPortalPlan.next(L, w, bag(3, true, 0, 64));
        assertEquals(Kind.BREAK, s.kind());
        assertEquals(L.cell(1, 2, 0), s.cell());
        World lane = built();
        lane.set(L, 2, 1, -1, Mat.SOLID);
        assertEquals(Kind.BREAK, LPortalPlan.next(L, lane, bag(3, true, 0, 64)).kind());
        World pit = built();
        pit.set(L, 2, 0, 0, Mat.SOLID);
        Step p = LPortalPlan.next(L, pit, bag(3, true, 0, 64));
        assertEquals(Kind.BREAK, p.kind());
        assertEquals(L.cell(2, 0, 0), p.cell());
    }

    @Test
    public void aCellThatTookLavaWithoutWaterIsScoopedBack() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        w.set(L, 3, 1, 0, Mat.LAVA);
        Step s = LPortalPlan.next(L, w, bag(2, false, 2, 64));
        assertEquals(Kind.LAVA_UNDO, s.kind());
        assertEquals(L.cell(3, 1, 0), s.cell());
        assertEquals(Kind.STUCK, LPortalPlan.next(L, w, bag(2, false, 0, 64)).kind());
    }

    @Test
    public void aGoneBaseOrPlatformIsStuck() {
        World w = built();
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        w.set(L, LPortalPlan.PLATFORM_LEFT, Mat.LAVA);
        assertEquals(Kind.STUCK, LPortalPlan.next(L, w, bag(3, false, 1, 64)).kind());
    }

    // ---- the site ----

    @Test
    public void theRowFitsWhenThereIsEnoughLavaAroundToScoop() {
        World w = pool();
        assertTrue(LPortalPlan.fits(L, w));
    }

    @Test
    public void aPoolWithNothingLeftToScoopHasNoSupplyAndDoesNotFit() {
        World w = new World();
        for (int x = 10; x < 14; x++) {
            for (int z = 9; z <= 10; z++) {
                w.over.put(new P(x, 63, z), Mat.LAVA);
            }
        }
        assertTrue(LPortalPlan.quick(L, w));
        assertFalse(LPortalPlan.fits(L, w));
    }

    @Test
    public void theSupplyHasToCoverEveryPourWithSomeToSpare() {
        int[][] extra = {{10, 8}, {11, 8}, {12, 8}, {13, 8}, {14, 8}, {14, 9}, {14, 10}, {15, 10}, {15, 9}, {15, 8}};
        World w = new World();
        for (int u = 0; u <= 3; u++) {
            w.over.put(new P(10 + u, 63, 10), Mat.LAVA);
        }
        w.over.put(new P(11, 63, 9), Mat.LAVA);
        w.over.put(new P(12, 63, 9), Mat.LAVA);
        for (int i = 0; i < extra.length; i++) {
            w.over.put(new P(extra[i][0], 63, extra[i][1]), Mat.LAVA);
            assertEquals("with " + (i + 1) + " spare sources", i + 1 >= 10, LPortalPlan.fits(L, w));
        }
    }

    @Test
    public void aFarLakeDoesNotCountAsSupply() {
        World w = new World();
        for (int u = 0; u <= 3; u++) {
            w.over.put(new P(10 + u, 63, 10), Mat.LAVA);
        }
        w.over.put(new P(11, 63, 9), Mat.LAVA);
        w.over.put(new P(12, 63, 9), Mat.LAVA);
        for (int x = 70; x < 90; x++) {
            for (int z = 0; z <= 10; z++) {
                w.over.put(new P(x, 63, z), Mat.LAVA);
            }
        }
        assertTrue(LPortalPlan.quick(L, w));
        assertFalse(LPortalPlan.fits(L, w));
    }

    @Test
    public void aRowNeedsFourSourcesShoreBesideItAndFloorUnderIt() {
        World w = pool();
        w.set(L, 2, 0, 0, Mat.OBSIDIAN);
        assertFalse(LPortalPlan.quick(L, w));
        World noShore = pool();
        noShore.set(L, 1, 0, 1, Mat.AIR);
        assertFalse(LPortalPlan.quick(L, noShore));
        World noPlatform = pool();
        noPlatform.set(L, 1, 0, -1, Mat.SOLID);
        assertFalse(LPortalPlan.quick(L, noPlatform));
        World deep = pool();
        deep.set(L, 1, -1, 0, Mat.LAVA);
        assertFalse("a pool deeper than one is not for this", LPortalPlan.quick(L, deep));
    }

    @Test
    public void aBumpyOrWetSiteDoesNotFit() {
        World w = pool();
        w.set(L, 2, 1, 1, Mat.SOLID);
        assertFalse("a block where a wall goes", LPortalPlan.fits(L, w));
        World wet = pool();
        wet.set(L, 5, 1, 2, Mat.WATER);
        assertFalse(LPortalPlan.fits(L, wet));
        World tree = pool();
        tree.set(L, 3, 4, 0, Mat.SOLID);
        assertFalse("the frame cells must be clear", LPortalPlan.fits(L, tree));
        World hole = pool();
        hole.set(L, 2, 0, 2, Mat.AIR);
        assertFalse("no ground to stand on at the shore", LPortalPlan.fits(L, hole));
    }

    @Test
    public void candidatesAreRowsInThePoolNearestFirst() {
        World w = pool();
        List<Layout> got = LPortalPlan.candidates(w, w.lava(), 12, 20);
        assertFalse(got.isEmpty());
        Layout first = null;
        for (Layout l : got) {
            assertTrue(LPortalPlan.quick(l, w));
            if (first == null && LPortalPlan.fits(l, w)) {
                first = l;
            }
        }
        assertNotNull(first);
    }

    @Test
    public void noLavaNoCandidates() {
        assertTrue(LPortalPlan.candidates(new World(), List.of(), 0, 0).isEmpty());
    }

    // ---- scooping ----

    private static final Set<P> NONE = Set.of();

    // the eyes of someone standing in a cell
    private static double[] eyes(P stand) {
        return new double[]{stand.x() + 0.5, stand.y() + 1.62, stand.z() + 0.5};
    }

    @Test
    public void farFromTheLavaItWalksToTheShoreCellBesideASource() {
        World w = pool();
        double[] e = eyes(new P(25, 64, 20));
        Scoop s = LPortalPlan.scoopSpot(w, w.lava(), null, e[0], e[1], e[2], NONE);
        assertNotNull(s);
        assertNotNull(s.stand());
        assertEquals(64, s.stand().y());
        assertEquals(Mat.SOLID, w.at(s.stand().x(), 63, s.stand().z()));
        assertEquals(1, Math.abs(s.stand().x() - s.source().x()) + Math.abs(s.stand().z() - s.source().z()));
        assertEquals(new P(25, 63, 10), s.source());
    }

    @Test
    public void lavaInReachIsScoopedWhereWeStandWithNoWalk() {
        World w = built();
        // standing on the platform, the lava in front of it and beside it is right there
        double[] e = eyes(L.cell(LPortalPlan.ROUNDS.get(0).stand()));
        Scoop s = LPortalPlan.scoopSpot(w, w.lava(), L, e[0], e[1], e[2], NONE);
        assertNotNull(s);
        assertNull("no stand means no walk", s.stand());
        double d = Math.sqrt(Math.pow(s.source().x() + 0.5 - e[0], 2) + Math.pow(s.source().y() + 0.5 - e[1], 2) + Math.pow(s.source().z() + 0.5 - e[2], 2));
        assertTrue("within reach of the eyes: " + d, d <= 4.0);
    }

    @Test
    public void thePlanScoopsInPlaceFromThePlatformWithoutALongWalk() {
        World w = built();
        double[] e = eyes(L.cell(LPortalPlan.ROUNDS.get(0).stand()));
        Scoop s = LPortalPlan.scoopSpot(w, w.lava(), L, e[0], e[1], e[2], NONE);
        assertNull(s.stand());
        // and the lava we scoop is never a cell of the row or the platform
        for (int u = 0; u <= 3; u++) {
            for (int d = -1; d <= 0; d++) {
                assertFalse(L.cell(u, 0, d).equals(s.source()));
            }
        }
    }

    @Test
    public void aBlockInTheWayMeansNotInPlace() {
        World w = built();
        double[] e = eyes(L.cell(LPortalPlan.ROUNDS.get(0).stand()));
        // wall the stand in with solid blocks all round at eye level and above, so nothing is in sight
        P stand = L.cell(LPortalPlan.ROUNDS.get(0).stand());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    w.over.put(new P(stand.x() + dx, stand.y(), stand.z() + dz), Mat.SOLID);
                    w.over.put(new P(stand.x() + dx, stand.y() + 1, stand.z() + dz), Mat.SOLID);
                }
            }
        }
        Scoop s = LPortalPlan.scoopSpot(w, w.lava(), L, e[0], e[1], e[2], NONE);
        assertTrue(s == null || s.stand() != null);
    }

    @Test
    public void aSourceThatDidNotWorkFromHereIsSkipped() {
        World w = built();
        double[] e = eyes(L.cell(LPortalPlan.ROUNDS.get(0).stand()));
        Scoop first = LPortalPlan.scoopSpot(w, w.lava(), L, e[0], e[1], e[2], NONE);
        Scoop second = LPortalPlan.scoopSpot(w, w.lava(), L, e[0], e[1], e[2], Set.of(first.source()));
        assertNotNull(second);
        assertFalse(second.source().equals(first.source()));
    }

    @Test
    public void nothingToScoopWhenTheLavaIsCoveredOrFlowing() {
        World w = new World();
        P p = new P(0, 63, 0);
        w.over.put(p, Mat.LAVA);
        w.over.put(new P(0, 64, 0), Mat.SOLID);
        assertNull(LPortalPlan.scoopSpot(w, List.of(p), null, 3.5, 65.6, 3.5, NONE));
        World flow = new World();
        flow.over.put(p, Mat.LAVA_FLOW);
        assertNull(LPortalPlan.scoopSpot(flow, List.of(p), null, 3.5, 65.6, 3.5, NONE));
    }

    // ---- a build that gives up ----

    @Test
    public void looseWaterAndLavaComeBackWhenThereIsABucket() {
        World w = built();
        assertNull("nothing loose", LPortalPlan.cleanup(L, w, 2));
        w.set(L, LPortalPlan.ROUNDS.get(0).source(), Mat.WATER);
        Step s = LPortalPlan.cleanup(L, w, 1);
        assertEquals(Kind.WATER_SCOOP, s.kind());
        assertEquals(L.cell(LPortalPlan.ROUNDS.get(0).source()), s.cell());
        assertNull("no empty bucket to take it with", LPortalPlan.cleanup(L, w, 0));
        World pit = pool();
        pit.set(L, 2, 0, 0, Mat.WATER);
        assertEquals(L.cell(2, 0, 0), LPortalPlan.cleanup(L, pit, 1).cell());
        World lava = built();
        lava.set(L, 3, 1, 0, Mat.LAVA);
        Step undo = LPortalPlan.cleanup(L, lava, 1);
        assertEquals(Kind.LAVA_UNDO, undo.kind());
        assertEquals(L.cell(3, 1, 0), undo.cell());
    }
}
