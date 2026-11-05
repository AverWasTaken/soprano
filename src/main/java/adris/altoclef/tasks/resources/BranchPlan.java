package adris.altoclef.tasks.resources;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// which cells a strip mine digs and in what order, no game in here so it can be tested. the pattern comes from Hackerokuz's
// altoclef fork (LegitMineFeature, https://github.com/Hackerokuz/altoclef/tree/LegitMineFeature, MIT): a trunk with a branch
// off both sides every 4 cells. the task owns the world, this only owns the bookkeeping, so it lives through a fight or a tool trip
public final class BranchPlan {
    // the old code said depth 3 and meant 4 (inclusive corners), and the trunk has to land on the checkpoints or the branches drift
    public static final int SPACING = 4;
    public static final int BRANCH_LENGTH = 32;
    public static final int BRANCH_SEGMENT = 8;
    public static final int TRUNK_CAP = 256;
    public static final int MAX_TURNS = 3;

    public enum Kind {TRUNK, BRANCH}

    public enum End {NOT_YET, TRUNK_CAP, NOWHERE_TO_TURN}

    private enum Phase {TRUNK, LEFT, RIGHT}

    // anchor is the cell it is dug from. cells hold feet positions, the head is one up
    public record Segment(Kind kind, Direction dir, BlockPos anchor, List<BlockPos> cells) {
        public BlockPos end() {
            return cells.get(cells.size() - 1);
        }

        // a same shape segment with the bad tail cut off
        public Segment firstCells(int count) {
            return new Segment(kind, dir, anchor, List.copyOf(cells.subList(0, count)));
        }

        // anything above the floor counts, not just feet and head. a gravel stack over a cell that's off limits makes the whole
        // cell cost infinity and the pather walks around it or gives up
        public boolean inCorridor(int x, int y, int z) {
            if (y < anchor.getY()) {
                return false;
            }
            int run = (x - anchor.getX()) * dir.getStepX() + (z - anchor.getZ()) * dir.getStepZ();
            return run >= 0 && run <= cells.size()
                    && x == anchor.getX() + run * dir.getStepX()
                    && z == anchor.getZ() + run * dir.getStepZ();
        }
    }

    private final Direction _original;
    private BlockPos _checkpoint;
    private Direction _trunkDir;
    private Phase _phase = Phase.TRUNK;
    // cells already dug on the branch we're on
    private int _along = 0;
    private int _trunkCells = 0;
    private int _turns = 0;
    // directions we already bounced off from this checkpoint, so a turn doesn't walk back into the same wall
    private final Set<Direction> _triedHere = EnumSet.noneOf(Direction.class);
    private End _end = End.NOT_YET;

    public BranchPlan(BlockPos origin, Direction facing) {
        _checkpoint = origin.immutable();
        _trunkDir = facing;
        _original = facing;
        _triedHere.add(facing);
    }

    // same answer until segmentDone or lineBlocked moves us on, null once it's over (see end)
    public Segment current() {
        if (_end != End.NOT_YET) {
            return null;
        }
        return switch (_phase) {
            case TRUNK -> trunkSegment();
            case LEFT -> branchSegment(_trunkDir.getCounterClockWise());
            case RIGHT -> branchSegment(_trunkDir.getClockWise());
        };
    }

    private Segment trunkSegment() {
        if (_trunkCells + SPACING > TRUNK_CAP) {
            _end = End.TRUNK_CAP;
            return null;
        }
        return new Segment(Kind.TRUNK, _trunkDir, _checkpoint, line(_checkpoint, _trunkDir, 1, SPACING));
    }

    private Segment branchSegment(Direction side) {
        BlockPos anchor = _checkpoint.relative(side, _along);
        return new Segment(Kind.BRANCH, side, anchor, line(_checkpoint, side, _along + 1, BRANCH_SEGMENT));
    }

    private static List<BlockPos> line(BlockPos from, Direction dir, int first, int count) {
        List<BlockPos> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(from.relative(dir, first + i));
        }
        return List.copyOf(out);
    }

    public void segmentDone() {
        switch (_phase) {
            case TRUNK -> {
                _checkpoint = _checkpoint.relative(_trunkDir, SPACING);
                _trunkCells += SPACING;
                _triedHere.clear();
                _triedHere.add(_trunkDir);
                _phase = Phase.LEFT;
                _along = 0;
            }
            case LEFT -> {
                _along += BRANCH_SEGMENT;
                if (_along >= BRANCH_LENGTH) {
                    _phase = Phase.RIGHT;
                    _along = 0;
                }
            }
            case RIGHT -> {
                _along += BRANCH_SEGMENT;
                if (_along >= BRANCH_LENGTH) {
                    _phase = Phase.TRUNK;
                    _along = 0;
                }
            }
        }
    }

    // this line goes no further. a branch just ends, the rest of it hangs off a cell we can't dig. a trunk has to go somewhere
    // though, so it turns at the last checkpoint and starts over from there
    public void lineBlocked() {
        switch (_phase) {
            case LEFT -> {
                _phase = Phase.RIGHT;
                _along = 0;
            }
            case RIGHT -> {
                _phase = Phase.TRUNK;
                _along = 0;
            }
            case TRUNK -> turn();
        }
    }

    private void turn() {
        Direction next = _turns < MAX_TURNS ? nextTrunkDir() : null;
        if (next == null) {
            _end = End.NOWHERE_TO_TURN;
            return;
        }
        _turns++;
        _trunkDir = next;
        _triedHere.add(next);
    }

    // sideways first. never back the way the whole thing came from, that's the tunnel we already dug
    private Direction nextTrunkDir() {
        Direction[] order = {_trunkDir.getCounterClockWise(), _trunkDir.getClockWise(), _trunkDir.getOpposite()};
        for (Direction d : order) {
            if (!_triedHere.contains(d) && d != _original.getOpposite()) {
                return d;
            }
        }
        return null;
    }

    public End end() {
        return _end;
    }

    public int trunkCells() {
        return _trunkCells;
    }

    // what the walls of these cells show: anything touching feet or head starts a flood through the rest of the vein. no x-ray,
    // a block we can't see from the tunnel only gets in by touching one we can. first found first, so a buried block comes after
    // the one in front of it, and the limit stops a giant vein from eating the whole run
    public static List<BlockPos> veinsAlong(Collection<BlockPos> cells, Predicate<BlockPos> isOre, int limit) {
        Set<BlockPos> found = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos cell : cells) {
            for (BlockPos part : List.of(cell, cell.above())) {
                for (Direction d : Direction.values()) {
                    offer(part.relative(d), isOre, limit, found, queue);
                }
            }
        }
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos at = queue.poll();
            for (Direction d : Direction.values()) {
                offer(at.relative(d), isOre, limit, found, queue);
            }
        }
        return List.copyOf(found);
    }

    private static void offer(BlockPos pos, Predicate<BlockPos> isOre, int limit, Set<BlockPos> found, ArrayDeque<BlockPos> queue) {
        if (found.size() < limit && !found.contains(pos) && isOre.test(pos)) {
            found.add(pos.immutable());
            queue.add(pos.immutable());
        }
    }
}
