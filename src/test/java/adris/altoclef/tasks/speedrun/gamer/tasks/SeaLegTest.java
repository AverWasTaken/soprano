package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SeaLegTest {
    private static final int RING_Y = 38;

    // sea surface at 63, water down to floor(x) + 1, rock under that. the floor can change with x
    private static final class Sea implements RoomRetarget.Probe {
        IntUnaryOperator floor = x -> 45;

        @Override
        public boolean loaded(int x, int z) {
            return true;
        }

        @Override
        public int topY(int x, int z) {
            return 63;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= floor.applyAsInt(x);
        }

        @Override
        public boolean fluid(int x, int y, int z) {
            return y > floor.applyAsInt(x) && y <= 63;
        }
    }

    private final Sea sea = new Sea();

    @Test
    public void aWaypointIsAStepTowardTheTargetFromWhereWeStand() {
        int[] w = SeaLeg.waypoint(sea, 0.5, 0.5, 160, RING_Y, 0, 0);
        assertEquals(SeaLeg.STEP, w[0]);
        assertEquals(0, w[2]);
        // floor 45, six under it is 39, the cap over the ring is 40
        assertEquals(39, w[1]);
        // from somewhere else entirely it's a step from there, not the next one of some old list
        int[] w2 = SeaLeg.waypoint(sea, 100.5, 0.5, 160, RING_Y, 0, 0);
        assertEquals(100 + SeaLeg.STEP, w2[0]);
        // diagonal: still STEP long
        int[] d = SeaLeg.waypoint(sea, 0.5, 0.5, 100, RING_Y, 100, 0);
        assertEquals(SeaLeg.STEP, Math.hypot(d[0] - 0.5, d[2] - 0.5), 1.0);
    }

    @Test
    public void theDepthIsPerWaypointColumn() {
        // shallow by the shore, deep further out
        sea.floor = x -> x < 50 ? 55 : 42;
        assertEquals(RING_Y + RoomRetarget.UNDER_SEA_TURN_ABOVE, SeaLeg.waypoint(sea, 0.5, 0.5, 160, RING_Y, 0, 0)[1]);
        assertEquals(42 - RoomRetarget.TUNNEL_UNDER_SEABED, SeaLeg.waypoint(sea, 50.5, 0.5, 160, RING_Y, 0, 0)[1]);
    }

    @Test
    public void theDepthStaysNearTheRingUnlessTheRockSaysOtherwise() {
        // floor at 30: six under is 24, more than NEAR_VERTICAL under the ring, so it's held at ring - 12
        assertEquals(RING_Y - (int) RoomRetarget.NEAR_VERTICAL, SeaLeg.legY(colAt(30), 0, 0, RING_Y, 0));
        // a trench at 10: ring - 12 would be open water, two blocks of floor over our heads wins
        assertEquals(10 - 2, SeaLeg.legY(colAt(10), 0, 0, RING_Y, 0));
        // a deeper retry goes down three, still not past the clamp
        assertEquals(39 - 3, SeaLeg.legY(colAt(45), 0, 0, RING_Y, -3));
        assertEquals(RING_Y - (int) RoomRetarget.NEAR_VERTICAL, SeaLeg.legY(colAt(30), 0, 0, RING_Y, -3));
        // and never into the lava down at the bottom
        assertEquals(SeaLeg.LEG_FLOOR_Y, SeaLeg.legY(colAt(-60), 0, 0, -45, 0));
    }

    private static Sea colAt(int floor) {
        Sea s = new Sea();
        s.floor = x -> floor;
        return s;
    }

    @Test
    public void withinARoomItHandsOver() {
        SeaLeg leg = new SeaLeg(100, RING_Y, 0);
        assertEquals(SeaLeg.Kind.WALK, leg.tick(sea, 0.5, 40, 0.5, 0, false).kind());
        assertEquals(SeaLeg.Kind.ARRIVED, leg.tick(sea, 100.5 - SeaLeg.HAND_OFF, 40, 0.5, 1, false).kind());
        assertFalse(leg.arrived(100.5 - SeaLeg.HAND_OFF - 1, 0.5));
    }

    @Test
    public void reachingAWaypointIssuesTheNextOneAndCountsAsProgress() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        SeaLeg.Step first = leg.tick(sea, 0.5, 39, 0.5, 0, false);
        assertTrue(first.fresh());
        assertTrue(first.progressed());
        // same spot a second later: same waypoint, nothing new
        SeaLeg.Step same = leg.tick(sea, 0.5, 39, 0.5, 1, false);
        assertFalse(same.fresh());
        assertEquals(first.waypoint(), same.waypoint());
        // two blocks along: not three, no progress yet
        assertFalse(leg.tick(sea, 2.5, 39, 0.5, 2, false).progressed());
        assertTrue(leg.tick(sea, 3.5, 39, 0.5, 3, false).progressed());
        // standing on it
        SeaLeg.Step next = leg.tick(sea, 13.5, 39, 0.5, 4, false);
        assertTrue(next.fresh());
        assertEquals(13 + SeaLeg.STEP, next.waypoint()[0]);
    }

    @Test
    public void aWaypointFromBeforeAnInterruptIsDropped() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        leg.tick(sea, 0.5, 39, 0.5, 0, false);
        // a fight dragged us 30 blocks off to the side
        SeaLeg.Step after = leg.tick(sea, 0.5, 39, 30.5, 1, false);
        assertTrue(after.fresh());
        assertEquals(30, after.waypoint()[2], 3);
    }

    @Test
    public void stuckGoesDeeperThenSidewaysThenGivesUp() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        int[] w0 = leg.tick(sea, 0.5, 39, 0.5, 0, false).waypoint();
        SeaLeg.Step s = null;
        int retries = 0;
        int[] lastWp = w0;
        for (int t = 1; t <= 200 && (s == null || s.kind() == SeaLeg.Kind.WALK); t++) {
            s = leg.tick(sea, 0.5, 39, 0.5, t, false);
            if (s.kind() == SeaLeg.Kind.WALK && s.fresh()) {
                retries++;
                assertEquals(retries, s.retry());
                assertEquals(SeaLeg.STUCK_SECONDS * retries, t, 0.01);
                assertNotEquals(lastWp[0] * 1000 + lastWp[1] * 100 + lastWp[2], s.waypoint()[0] * 1000 + s.waypoint()[1] * 100 + s.waypoint()[2]);
                if (retries == 1) {
                    // deeper
                    assertEquals(w0[1] - 3, s.waypoint()[1]);
                    assertEquals(w0[2], s.waypoint()[2]);
                } else {
                    // off to one side, then the other
                    assertEquals(retries == 2 ? 5 : -5, s.waypoint()[2], 1);
                }
                lastWp = s.waypoint();
            }
        }
        assertEquals(SeaLeg.MAX_RETRIES, retries);
        assertEquals(SeaLeg.Kind.FAILED, s.kind());
        assertTrue(leg.failed());
        assertEquals(SeaLeg.Kind.FAILED, leg.tick(sea, 50, 39, 0, 500, false).kind());
    }

    @Test
    public void noPathRetriesAtOnce() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        leg.tick(sea, 0.5, 39, 0.5, 0, false);
        SeaLeg.Step s = leg.tick(sea, 0.5, 39, 0.5, 1, true);
        assertTrue(s.fresh());
        assertEquals(1, s.retry());
    }

    // time someone else had the wheel isn't time stuck
    @Test
    public void aLongInterruptDoesNotCountAsStuck() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        leg.tick(sea, 0.5, 39, 0.5, 0, false);
        SeaLeg.Step s = leg.tick(sea, 0.5, 39, 0.5, 100, false);
        assertEquals(0, s.retry());
        assertFalse(s.fresh());
    }

    @Test
    public void getsPastTheBlockAndTheRetriesStartOver() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        leg.tick(sea, 0.5, 39, 0.5, 0, false);
        int[] deeper = leg.tick(sea, 0.5, 39, 0.5, 1, true).waypoint();
        SeaLeg.Step s = leg.tick(sea, deeper[0] + 0.5, deeper[1], deeper[2] + 0.5, 2, false);
        assertTrue(s.fresh());
        assertEquals(0, s.retry());
    }

    // the ring turned up mid leg: next waypoint heads for it, from where we are
    @Test
    public void aNewTargetMidLegIsTheNextWaypoint() {
        SeaLeg leg = new SeaLeg(160, RING_Y, 0);
        leg.tick(sea, 0.5, 39, 0.5, 0, false);
        leg.target(0, RING_Y, 160);
        SeaLeg.Step s = leg.tick(sea, 0.5, 39, 0.5, 1, false);
        assertTrue(s.fresh());
        assertEquals(0, s.waypoint()[0], 1);
        assertEquals(SeaLeg.STEP, s.waypoint()[2], 1);
        assertEquals(160, leg.tz());
    }

    @Test
    public void theGoalTestIsGoalNears() {
        int[] w = {10, 40, 0};
        assertTrue(SeaLeg.reached(w, 10.5, 40, 0.5));
        assertTrue(SeaLeg.reached(w, 13.5, 40, 0.5));
        assertFalse(SeaLeg.reached(w, 14.5, 40, 0.5));
        assertFalse(SeaLeg.reached(w, 12.5, 43, 2.5));
    }
}
