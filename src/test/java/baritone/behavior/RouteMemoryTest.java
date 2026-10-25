/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.api.utils.BetterBlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class RouteMemoryTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot();
    }

    private static void stand(Object level, Path dir, int x, int y, int z) {
        RouteMemory.tick(level, dir, x + 0.5, y, z + 0.5, new BetterBlockPos(x, y, z), true);
    }

    @Test
    public void aWindingCourseIsFollowedTheWayItWent() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        // a course that goes east from the start, round, and back west past the start to the finish
        stand(level, dir, 0, 64, 0);
        for (int x = 1; x <= 10; x++) {
            stand(level, dir, x, 64, 0);
        }
        for (int z = 1; z <= 6; z++) {
            stand(level, dir, 10, 64, z);
        }
        for (int x = 9; x >= -10; x--) {
            stand(level, dir, x, 64, 6);
        }
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(-10, 64, 6));
        assertNotNull(g);
        assertTrue(g.reachesGoal());
        // east first: further along than the start, though further from the finish
        assertTrue(g.along(5, 64, 0) > g.along(0, 64, 0));
        assertTrue(g.along(-10, 64, 6) > g.along(10, 64, 3));
        // and it lasts: a new session in the same world reads it back
        RouteMemory.save();
        Object again = new Object();
        RouteMemory.tick(again, dir, 100.5, 64, 100.5, new BetterBlockPos(100, 64, 100), true);
        RouteMemory.Guide g2 = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(-10, 64, 6));
        assertNotNull(g2);
        assertEquals(g.length(), g2.length());
    }

    @Test
    public void aFallPutBackDropsWhatCameAfterTheCheckpoint() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        for (int i = 0; i < 70; i++) {
            stand(level, dir, 0, 64, 0); // (settle in: a level's start teleport doesn't count)
        }
        for (int x = 1; x <= 8; x++) {
            stand(level, dir, x, 64, 0);
        }
        // off the end, onto the floor, and put back on the start
        stand(level, dir, 9, 40, 0);
        stand(level, dir, 10, 40, 0);
        stand(level, dir, 0, 64, 0);
        for (int x = 1; x <= 12; x++) {
            stand(level, dir, x, 64, 1);
        }
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(12, 64, 1));
        assertNotNull(g);
        assertEquals(-1, g.along(10, 40, 0)); // the floor isn't the way
        assertTrue(g.along(12, 64, 1) > 0);
    }

    @Test
    public void launchedBackUpAfterAFallKeepsTheClimb() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        for (int i = 0; i < 70; i++) {
            stand(level, dir, 0, 64, 0);
        }
        for (int x = 1; x <= 20; x++) {
            stand(level, dir, x, 64 + x / 2, 0); // a climb
        }
        // off the side, put back on the start, and flown up (no teleport) to where we were
        RouteMemory.tick(level, dir, 20.5, 50, 4.5, new BetterBlockPos(20, 50, 4), false);
        RouteMemory.tick(level, dir, 0.5, 64, 0.5, new BetterBlockPos(0, 64, 0), false);
        for (int k = 1; k <= 10; k++) {
            RouteMemory.tick(level, dir, 2 * k + 0.5, 64 + 0.98 * k, 0.5, new BetterBlockPos(2 * k, 64, 0), false);
        }
        stand(level, dir, 20, 74, 0);
        for (int x = 21; x <= 25; x++) {
            stand(level, dir, x, 74, 0);
        }
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(25, 74, 0));
        assertNotNull(g);
        assertTrue(g.reachesGoal());
        assertTrue(g.along(10, 69, 0) > 0); // the climb's still on it
    }

    @Test
    public void flyingBackAfterAMissLeavesNothingBehind() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        for (int i = 0; i < 70; i++) {
            stand(level, dir, 0, 64, 0);
        }
        for (int x = 1; x <= 5; x++) {
            stand(level, dir, x, 64, 0);
        }
        // a miss: down onto the floor, a walk about, and flown back up to the block it went from (one over)
        for (int y = 63; y > 55; y--) {
            RouteMemory.tick(level, dir, 6.5, y, 0.5, new BetterBlockPos(6, y, 0), false);
        }
        stand(level, dir, 6, 55, 0);
        stand(level, dir, 6, 55, 1);
        stand(level, dir, 6, 55, 2);
        for (int y = 56; y <= 66; y++) {
            RouteMemory.tick(level, dir, 5.5, y, 0.5, new BetterBlockPos(5, y, 0), false);
        }
        stand(level, dir, 5, 64, 1);
        for (int x = 6; x <= 12; x++) {
            stand(level, dir, x, 64, 1);
        }
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(12, 64, 1));
        assertNotNull(g);
        assertTrue(g.reachesGoal());
        assertEquals(-1, g.along(6, 55, 1)); // the floor isn't the way
    }

    @Test
    public void restartingTheLevelKeepsTheWay() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        for (int i = 0; i < 70; i++) {
            stand(level, dir, 0, 64, 0);
        }
        for (int x = 1; x <= 12; x++) {
            stand(level, dir, x, 64, 0);
        }
        for (int i = 0; i < 40; i++) {
            stand(level, dir, 12, 64, 0); // (at the finish a while)
        }
        stand(level, dir, 0, 64, 0); // and the level started over: not a fall
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(0, 64, 0), new BetterBlockPos(12, 64, 0));
        assertNotNull(g);
        assertTrue(g.reachesGoal());
    }

    @Test
    public void aRecordingTeachesTheWayMoved() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        stand(new Object(), dir, 500, 64, 500);
        // a recorded run (a few ticks a cell) somewhere else, rebuilt 100 east of where it was played
        String trace = "tick,world,x,y,z,onGround\n" + java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> i + ",0," + (i / 3 + 0.5) + ",64.0,0.5,1").collect(java.util.stream.Collectors.joining("\n"));
        java.util.List<double[]> ticks = baritone.command.defaults.RoutesCommand.parse(trace);
        assertEquals(60, ticks.size());
        assertTrue(RouteMemory.learn(ticks, 100, 0, 0) >= 15);
        RouteMemory.Guide g = RouteMemory.guide(new BetterBlockPos(100, 64, 0), new BetterBlockPos(119, 64, 0));
        assertNotNull(g);
        assertTrue(g.reachesGoal());
    }

    @Test
    public void theTrailBeingLaidDownIsntFollowed() throws Exception {
        Path dir = Files.createTempDirectory("routes");
        Object level = new Object();
        // first time on a level: the only trail is the one we're making, and it ends where we are
        for (int x = 0; x <= 6; x++) {
            stand(level, dir, x, 64, 0);
        }
        stand(level, dir, 5, 64, 0);
        assertNull(RouteMemory.guide(new BetterBlockPos(5, 64, 0), new BetterBlockPos(40, 64, 20)));
    }

    @Test
    public void jumpsThatKeepMissingCostMore() {
        BetterBlockPos a = new BetterBlockPos(1000, 70, 1000), b = new BetterBlockPos(1003, 70, 1000);
        assertEquals(0, RouteMemory.penalty(a.x, a.y, a.z, b.x, b.y, b.z), 0);
        RouteMemory.jumped(a, b, false);
        RouteMemory.jumped(a, b, false);
        RouteMemory.jumped(a, b, false);
        assertTrue(RouteMemory.penalty(a.x, a.y, a.z, b.x, b.y, b.z) > 0);
    }
}
