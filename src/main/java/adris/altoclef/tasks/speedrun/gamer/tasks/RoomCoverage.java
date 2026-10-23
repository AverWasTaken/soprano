package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

// when a chunk of the stronghold counts as searched, and which chunk to look at next when we have already seen bricks.
// pure. the portal room is only found by line of sight, so standing in a chunk (a dry tunnel through the rock) is not
// searching it: be near its middle, at the level the stronghold is on
public final class RoomCoverage {
    // within this of the chunk centre horizontally (a chunk is 16 wide, the room is 11 x 16 so this sees most of it)
    static final double HORIZONTAL_BLOCKS = 24;
    // and this close to the working level. a stronghold level is 5-8 tall, stairs link them, so a room on the next
    // level is still seen from a corridor within this
    static final double VERTICAL_BLOCKS = 12;

    public static boolean covers(double px, double py, double pz, int chunkX, int chunkZ, double workY) {
        double cx = chunkX * 16 + 8;
        double cz = chunkZ * 16 + 8;
        return Math.hypot(px - cx, pz - cz) <= HORIZONTAL_BLOCKS && Math.abs(py - workY) <= VERTICAL_BLOCKS;
    }

    // a leg ends when the chunk is covered, the walk finished, the timer ran out or we stopped moving. being inside the
    // chunk at the wrong height is none of those, the room is only seen from its own level
    public static boolean legOver(boolean covered, boolean walkFinished, boolean timerOut, boolean stalled) {
        return covered || walkFinished || timerOut || stalled;
    }

    // stand-still check: re-anchors whenever we get further than STALL_BLOCKS from the anchor, stalled once STALL_SECONDS
    // of task time pass without one. the walk task resets its own progress checker while baritone is pathing, so a bot
    // wedged mid-path would never be noticed by it
    public static final class Stall {
        static final double STALL_BLOCKS = 2;
        static final double STALL_SECONDS = 20;

        private double ax = Double.NaN;
        private double ay;
        private double az;
        private double since;

        public void reset() {
            ax = Double.NaN;
        }

        public boolean stalled(double x, double y, double z, double nowSeconds) {
            if (Double.isNaN(ax) || Math.sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay) + (z - az) * (z - az)) > STALL_BLOCKS) {
                ax = x;
                ay = y;
                az = z;
                since = nowSeconds;
                return false;
            }
            return nowSeconds - since >= STALL_SECONDS;
        }
    }

    public enum DigVerdict {KEEP_DIGGING, BRICKS, FLOOR}

    // the dig down the start column ends on bricks (we are in the stronghold) or on the floor with none (the estimate is
    // wrong). never on a fixed depth: the stronghold sits anywhere from the floor up to y 53
    public static DigVerdict digVerdict(boolean bricksSeen, double y, int floorY) {
        if (bricksSeen) {
            return DigVerdict.BRICKS;
        }
        return y <= floorY + 2 ? DigVerdict.FLOOR : DigVerdict.KEEP_DIGGING;
    }

    // a target chunk we are done with (arrived at the wrong level, or the timer ran out): if nothing saw it, it goes in
    // the skipped set and never in visited. returns true when it was skipped rather than covered
    public static boolean giveUp(Set<String> visited, Set<String> skipped, String key) {
        if (visited.contains(key)) {
            return false;
        }
        skipped.add(key);
        return true;
    }

    // the level to search on is where the bricks are, not where the dig happened to end
    public static double workY(OptionalInt brickY, double playerY) {
        return brickY.isPresent() ? brickY.getAsInt() : playerY;
    }

    // tracked bricks that can be the stronghold's, before any cap. the cap has to come after this: a village or a ruin
    // next to us is nearer than the real thing and would eat every slot
    public static List<BlockPos> brickCandidates(List<BlockPos> tracked, int maxY, boolean hasStart, double startX,
                                                 double startZ, double maxDist, double nearY) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : tracked) {
            if (pos.getY() > maxY) {
                continue;
            }
            // a level of the stronghold, not a ruin at the surface or a corridor seen way down a cave
            if (!Double.isNaN(nearY) && Math.abs(pos.getY() - nearY) > VERTICAL_BLOCKS) {
                continue;
            }
            if (hasStart && Math.hypot(pos.getX() - startX, pos.getZ() - startZ) > maxDist) {
                continue;
            }
            out.add(pos);
        }
        return out;
    }

    // seen bricks mark where corridors are. of the chunks they sit in, the nearest one inside the search square that
    // is not visited yet, so we follow the stronghold before walking the blind spiral. bricks are {x, z}
    public static Optional<int[]> nearestUnvisitedBrickChunk(List<int[]> seenBricks, Set<String> visited, double px, double pz,
                                                            int startCx, int startCz, int radius) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] b : seenBricks) {
            int cx = Math.floorDiv(b[0], 16);
            int cz = Math.floorDiv(b[1], 16);
            if (Math.abs(cx - startCx) > radius || Math.abs(cz - startCz) > radius || visited.contains(cx + "," + cz)) {
                continue;
            }
            double d = Math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz);
            if (d < bestD) {
                bestD = d;
                best = new int[]{cx, cz};
            }
        }
        return Optional.ofNullable(best);
    }

    private RoomCoverage() {
    }
}
