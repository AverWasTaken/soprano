package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

// the shape of the mine, and that it always ends
public class BranchPlanTest {
    private static final BlockPos ORIGIN = new BlockPos(0, -59, 0);

    @Test
    public void trunkSegmentsAreFourCellsAndLandOnTheCheckpoints() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        BranchPlan.Segment trunk = plan.current();
        assertEquals(BranchPlan.Kind.TRUNK, trunk.kind());
        assertEquals(4, trunk.cells().size());
        assertEquals(ORIGIN, trunk.anchor());
        assertEquals(new BlockPos(0, -59, -1), trunk.cells().get(0));
        assertEquals(new BlockPos(0, -59, -4), trunk.end());
    }

    @Test
    public void bothSidesGetABranchAtEveryCheckpoint() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        plan.segmentDone();
        BlockPos checkpoint = new BlockPos(0, -59, -4);
        // left of north is west, four stretches of 8
        for (int stretch = 0; stretch < 4; stretch++) {
            BranchPlan.Segment s = plan.current();
            assertEquals(BranchPlan.Kind.BRANCH, s.kind());
            assertEquals(Direction.WEST, s.dir());
            assertEquals(checkpoint.west(stretch * 8), s.anchor());
            assertEquals(checkpoint.west(stretch * 8 + 1), s.cells().get(0));
            assertEquals(checkpoint.west(stretch * 8 + 8), s.end());
            plan.segmentDone();
        }
        for (int stretch = 0; stretch < 4; stretch++) {
            BranchPlan.Segment s = plan.current();
            assertEquals(Direction.EAST, s.dir());
            assertEquals(checkpoint.east(stretch * 8 + 8), s.end());
            plan.segmentDone();
        }
        BranchPlan.Segment next = plan.current();
        assertEquals(BranchPlan.Kind.TRUNK, next.kind());
        assertEquals(checkpoint, next.anchor());
        assertEquals(checkpoint.north(4), next.end());
    }

    @Test
    public void branchesAreFourApart() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.EAST);
        List<Integer> branchXs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            plan.segmentDone();
            for (int s = 0; s < 8; s++) {
                branchXs.add(plan.current().end().getX());
                plan.segmentDone();
            }
        }
        assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 8, 8, 8, 8, 12, 12, 12, 12, 12, 12, 12, 12), branchXs);
    }

    @Test
    public void aBlockedBranchEndsThatBranchOnly() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        plan.segmentDone();
        plan.lineBlocked();
        assertEquals(Direction.EAST, plan.current().dir());
        assertEquals(new BlockPos(0, -59, -4), plan.current().anchor());
        plan.lineBlocked();
        assertEquals(BranchPlan.Kind.TRUNK, plan.current().kind());
        assertEquals(Direction.NORTH, plan.current().dir());
    }

    @Test
    public void aBlockedTrunkTurnsAtTheLastCheckpoint() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        plan.segmentDone();
        for (int s = 0; s < 8; s++) {
            plan.segmentDone();
        }
        plan.lineBlocked();
        BranchPlan.Segment turned = plan.current();
        assertEquals(BranchPlan.Kind.TRUNK, turned.kind());
        assertEquals(Direction.WEST, turned.dir());
        assertEquals(new BlockPos(0, -59, -4), turned.anchor());
    }

    @Test
    public void aTrunkBlockedAllAroundEndsWithoutGoingBack() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        Set<Direction> tried = new HashSet<>();
        for (int i = 0; i < 10 && plan.current() != null; i++) {
            tried.add(plan.current().dir());
            plan.lineBlocked();
        }
        assertNull(plan.current());
        assertEquals(BranchPlan.End.NOWHERE_TO_TURN, plan.end());
        assertEquals(Set.of(Direction.NORTH, Direction.WEST, Direction.EAST), tried);
    }

    @Test
    public void onlyThreeTurnsEver() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        int turns = 0;
        // every trunk gets one stretch in, then hits a wall and has no branches to speak of
        for (int i = 0; i < 100 && plan.current() != null; i++) {
            plan.segmentDone();
            plan.lineBlocked();
            plan.lineBlocked();
            plan.lineBlocked();
            if (plan.current() != null) {
                turns++;
            }
        }
        assertNull(plan.current());
        assertEquals(BranchPlan.MAX_TURNS, turns);
    }

    @Test
    public void theTrunkStopsAtTheCap() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.SOUTH);
        int steps = 0;
        // no fluff on the way: the two branches at each checkpoint get blocked right off
        while (plan.current() != null) {
            plan.segmentDone();
            plan.lineBlocked();
            plan.lineBlocked();
            steps++;
        }
        assertEquals(BranchPlan.TRUNK_CAP / BranchPlan.SPACING, steps);
        assertEquals(BranchPlan.TRUNK_CAP, plan.trunkCells());
        assertEquals(BranchPlan.End.TRUNK_CAP, plan.end());
    }

    @Test
    public void currentDoesNotMoveByItself() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        assertEquals(plan.current(), plan.current());
    }

    @Test
    public void theCorridorIsTheLineAndEverythingAboveIt() {
        BranchPlan plan = new BranchPlan(ORIGIN, Direction.NORTH);
        BranchPlan.Segment s = plan.current();
        assertTrue(s.inCorridor(0, -59, -1));
        assertTrue(s.inCorridor(0, -58, -4));
        // a gravel stack on top of a cell has to stay breakable or the whole cell is off limits
        assertTrue(s.inCorridor(0, -40, -2));
        assertTrue("the cell we start from, gravel can land there too", s.inCorridor(0, -59, 0));
        assertFalse("floor", s.inCorridor(0, -60, -1));
        assertFalse("wall", s.inCorridor(1, -59, -1));
        assertFalse("wall", s.inCorridor(-1, -58, -1));
        assertFalse("past the end", s.inCorridor(0, -59, -5));
        assertFalse("behind us", s.inCorridor(0, -59, 1));
    }

    @Test
    public void aCutSegmentKeepsItsFront() {
        BranchPlan.Segment s = new BranchPlan(ORIGIN, Direction.NORTH).current().firstCells(2);
        assertEquals(new BlockPos(0, -59, -2), s.end());
        assertFalse(s.inCorridor(0, -59, -3));
    }

    @Test
    public void veinFloodReachesTheBlockOnlyTheOtherOreTouches() {
        BlockPos wall = new BlockPos(1, -59, -1);
        BlockPos second = wall.east();
        BlockPos third = second.east();
        Set<BlockPos> ore = Set.of(wall, second, third);
        List<BlockPos> found = BranchPlan.veinsAlong(List.of(new BlockPos(0, -59, -1)), ore::contains, 16);
        assertEquals(List.of(wall, second, third), found);
    }

    @Test
    public void oreThatTouchesNothingStaysUnseen() {
        // two blocks into the wall. that's x-ray and we don't do that
        BlockPos buried = new BlockPos(2, -59, -1);
        assertTrue(BranchPlan.veinsAlong(List.of(new BlockPos(0, -59, -1)), Set.of(buried)::contains, 16).isEmpty());
    }

    @Test
    public void veinsAreSeenFromTheHeadToo() {
        BlockPos ceiling = new BlockPos(0, -57, -1);
        assertEquals(List.of(ceiling), BranchPlan.veinsAlong(List.of(new BlockPos(0, -59, -1)), Set.of(ceiling)::contains, 16));
    }

    @Test
    public void veinFloodHasNoDuplicatesFromTwoCells() {
        BlockPos shared = new BlockPos(1, -59, -1);
        List<BlockPos> found = BranchPlan.veinsAlong(List.of(new BlockPos(0, -59, -1), new BlockPos(0, -60, -1)), Set.of(shared)::contains, 16);
        assertEquals(List.of(shared), found);
    }

    @Test
    public void veinFloodIsBounded() {
        // every block in the world is ore. the flood has to come back anyway
        List<BlockPos> found = BranchPlan.veinsAlong(List.of(ORIGIN), pos -> true, 16);
        assertEquals(16, found.size());
        assertEquals(16, new HashSet<>(found).size());
    }
}
