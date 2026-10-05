package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.RunStateStore;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Kind;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.world.NetherComplexGrid;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetherSweepPlannerTest {
    // cells in a row along x, each with three waypoints 100 blocks apart starting at the cell's x * 1000
    private static final NetherSweepPlanner.Grid ROW = new NetherSweepPlanner.Grid() {
        @Override
        public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
            int base = cell.cx() * 1000;
            return List.of(new Point(base, 0), new Point(base + 100, 0), new Point(base + 200, 0));
        }

        @Override
        public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
            for (int cx = 0; cx < 100; cx++) {
                if (!visited.contains(new Cell(cx, 0).key())) {
                    return Optional.of(new Cell(cx, 0));
                }
            }
            return Optional.empty();
        }
    };

    private RunState state;
    private NetherConfig cfg;
    private NetherSweepPlanner planner;

    @Before
    public void setUp() {
        state = new RunState();
        cfg = new NetherConfig();
        planner = new NetherSweepPlanner(state, cfg, ROW, 12);
    }

    private static RunState.Pos pos(int x, int z) {
        return new RunState.Pos(x, 64, z);
    }

    // a fake "seen" oracle: only what is in the map has been seen
    private static NetherSweepPlanner.SightSource seeing(Map<Sight, RunState.Pos> seen) {
        return sight -> Optional.ofNullable(seen.get(sight));
    }

    @Test
    public void walksTheWaypointsOfTheNearestCellInOrder() {
        Action a = planner.step(Goal.FORTRESS, -300, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(0, a.x());
        // not there yet: same waypoint
        assertEquals(0, planner.step(Goal.FORTRESS, 80, 0, 1).x());
        // inside the arrive radius of the first one: on to the second
        Action b = planner.step(Goal.FORTRESS, 10, 0, 2);
        assertEquals(100, b.x());
        Action c = planner.step(Goal.FORTRESS, 100, 0, 3);
        assertEquals(200, c.x());
    }

    @Test
    public void finishedCellIsMarkedVisitedAndTheNextOneStarts() {
        planner.step(Goal.FORTRESS, 0, 0, 0);
        planner.step(Goal.FORTRESS, 0, 0, 1);
        planner.step(Goal.FORTRESS, 100, 0, 2);
        // standing on the last waypoint: cell 0 is done, cell 1 starts
        Action next = planner.step(Goal.FORTRESS, 200, 0, 3);
        assertEquals(Kind.GOTO, next.kind());
        assertEquals(1000, next.x());
        assertTrue(state.visitedCells.contains("0,0"));
        assertEquals(2, planner.cellsStarted());
    }

    @Test
    public void neverRevisitsAVisitedCell() {
        state.visitedCells.add("0,0");
        state.visitedCells.add("1,0");
        Action a = planner.step(Goal.FORTRESS, 0, 0, 0);
        assertEquals(2000, a.x());
    }

    @Test
    public void fortressSightingIsRecordedAndFinishes() {
        Map<Sight, RunState.Pos> seen = new EnumMap<>(Sight.class);
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertFalse(planner.scan(seeing(seen)));
        seen.put(Sight.FORTRESS, pos(150, 40));
        assertTrue(planner.scan(seeing(seen)));
        assertEquals(1, state.fortress.size());
        String key = NetherComplexGrid.cellOf(150, 40).key();
        assertTrue(state.fortressCells.contains(key));
        assertTrue(state.visitedCells.contains(key));
        assertEquals(Kind.FOUND, planner.step(Goal.FORTRESS, 0, 0, 1).kind());
        assertTrue(planner.goalMet(Goal.FORTRESS));
        // the same sighting again is not news
        assertFalse(planner.scan(seeing(seen)));
        assertEquals(1, state.fortress.size());
    }

    @Test
    public void unseenBlocksAreNeverRecorded() {
        planner.step(Goal.FORTRESS, 0, 0, 0);
        planner.scan(seeing(new EnumMap<>(Sight.class)));
        assertTrue(state.fortress.isEmpty());
        assertTrue(state.bastion.isEmpty());
        assertNull(state.warpedForest);
    }

    @Test
    public void bastionMarksItsCellAndIsAvoided() {
        // cell 0 is x in 0..431: a bastion at x=250 is in cell 0, our current cell
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertTrue(planner.report(Sight.BASTION, pos(250, 0)));
        String key = NetherComplexGrid.cellOf(250, 0).key();
        assertTrue(state.bastionCells.contains(key));
        assertTrue(state.visitedCells.contains(key));
        // the current cell was abandoned, the next one starts
        Action a = planner.step(Goal.FORTRESS, 0, 0, 1);
        assertEquals(1000, a.x());
        assertFalse(planner.goalMet(Goal.FORTRESS));
        // 48 blocks around it are off limits
        assertTrue(planner.nearBastion(250, 40));
        assertFalse(planner.nearBastion(250, 60));
    }

    @Test
    public void waypointsInsideABastionZoneAreSkipped() {
        // bastion right on the second waypoint of cell 1
        state.visitedCells.add("0,0");
        planner.report(Sight.BASTION, pos(1100, 10));
        // real cell of that bastion is 2, so the fake cell 1 (waypoints 1000, 1100, 1200) still gets swept
        // we stand on the first waypoint, the second one is 10 blocks from the bastion: skipped, third is 100 away
        Action a = planner.step(Goal.FORTRESS, 1000, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(1200, a.x());
    }

    @Test
    public void pathPredicateLetsUsOutWhenWeStandInsideAZone() {
        planner.report(Sight.BASTION, pos(5000, 0));
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertTrue(planner.blocksPath(5010, 0));
        // now we are in the zone ourselves: no walls
        planner.step(Goal.FORTRESS, 5010, 0, 1);
        assertFalse(planner.blocksPath(5010, 0));
        assertFalse(planner.blocksPath(5020, 0));
    }

    @Test
    public void bastionBlocksInAFortressCellAreIgnored() {
        planner.report(Sight.FORTRESS, pos(100, 100));
        assertFalse(planner.report(Sight.BASTION, pos(120, 120)));
        assertTrue(state.bastion.isEmpty());
        assertFalse(planner.nearBastion(120, 120));
    }

    @Test
    public void warpedForestIsRecordedOnceAndDoesNotStopTheSweep() {
        assertTrue(planner.report(Sight.WARPED, pos(30, 30)));
        assertFalse(planner.report(Sight.WARPED, pos(900, 900)));
        assertEquals(pos(30, 30), state.warpedForest);
        // looking for a fortress: still sweeping
        assertEquals(Kind.GOTO, planner.step(Goal.FORTRESS, 0, 0, 0).kind());
        // looking for a forest: done
        assertEquals(Kind.FOUND, planner.step(Goal.WARPED, 0, 0, 1).kind());
    }

    @Test
    public void slowWaypointIsSkippedAfterTheTimeout() {
        Action a = planner.step(Goal.FORTRESS, 500, 500, 0);
        assertEquals(0, a.x());
        // 89 s later: still on it
        assertEquals(0, planner.step(Goal.FORTRESS, 500, 500, 89).x());
        // 90 s: skipped, the timer restarts on the next one
        assertEquals(100, planner.step(Goal.FORTRESS, 500, 500, 90).x());
        assertEquals(100, planner.step(Goal.FORTRESS, 500, 500, 179).x());
        assertEquals(200, planner.step(Goal.FORTRESS, 500, 500, 180).x());
    }

    @Test
    public void capOfCellsFails() {
        NetherSweepPlanner small = new NetherSweepPlanner(state, cfg, ROW, 3);
        double now = 0;
        Action a = null;
        // everything times out, so one cell takes 3 * 90 s
        for (int i = 0; i < 100; i++) {
            a = small.step(Goal.FORTRESS, 500, 500, now);
            if (a.kind() != Kind.GOTO) {
                break;
            }
            now += 91;
        }
        assertNotNull(a);
        assertEquals(Kind.FAIL, a.kind());
        assertEquals(3, small.cellsStarted());
        assertEquals(3, state.visitedCells.size());
    }

    @Test
    public void runningOutOfCellsFails() {
        NetherSweepPlanner none = new NetherSweepPlanner(state, cfg, new NetherSweepPlanner.Grid() {
            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return List.of();
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                return Optional.empty();
            }
        }, 12);
        assertEquals(Kind.FAIL, none.step(Goal.FORTRESS, 0, 0, 0).kind());
    }

    @Test
    public void emptyWaypointListsStillTerminate() {
        NetherSweepPlanner blank = new NetherSweepPlanner(state, cfg, new NetherSweepPlanner.Grid() {
            private int n;

            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return List.of();
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                return Optional.of(new Cell(n++, 0));
            }
        }, 12);
        assertEquals(Kind.FAIL, blank.step(Goal.FORTRESS, 0, 0, 0).kind());
        assertEquals(12, blank.cellsStarted());
    }

    @Test
    public void givenUpFortressIsNotPickedUpAgain() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.giveUpFortress();
        assertTrue(state.fortress.isEmpty());
        assertNull(state.spawner);
        assertEquals(1, state.fortressExhausted.size());
        // its bricks are still in view, that must not count again, not even from the next block over the cell border
        assertFalse(planner.report(Sight.FORTRESS, pos(150, 40)));
        assertFalse(planner.report(Sight.FORTRESS, pos(300, 40)));
        assertTrue(state.fortress.isEmpty());
        // a fortress far away is fine
        assertTrue(planner.report(Sight.FORTRESS, pos(2000, 40)));
        assertEquals(1, state.fortress.size());
    }

    @Test
    public void whatItRecordedSurvivesAJsonRoundTrip() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.report(Sight.BASTION, pos(-700, 10));
        planner.report(Sight.WARPED, pos(5, 6));
        planner.giveUpFortress();
        state.netherRodsGaveUp = true;
        RunState back = new Gson().fromJson(RunStateStore.toJson(state), RunState.class);
        assertEquals(state.visitedCells, back.visitedCells);
        assertEquals(state.fortressCells, back.fortressCells);
        assertEquals(state.bastionCells, back.bastionCells);
        assertEquals(state.bastion, back.bastion);
        assertEquals(state.fortressExhausted, back.fortressExhausted);
        assertEquals(state.warpedForest, back.warpedForest);
        assertTrue(back.netherRodsGaveUp);
        // a planner built on the loaded state still avoids that bastion and skips the visited cells (0,0 and -2,0)
        NetherSweepPlanner again = new NetherSweepPlanner(back, cfg, ROW, 12);
        assertTrue(again.nearBastion(-700, 10));
        assertEquals(1000, again.step(Goal.FORTRESS, 0, 0, 0).x());
    }
}
