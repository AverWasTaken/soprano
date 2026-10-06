package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.List;
import java.util.Optional;
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
