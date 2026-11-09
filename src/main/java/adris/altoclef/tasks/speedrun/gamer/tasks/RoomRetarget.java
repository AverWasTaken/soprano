package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.Optional;

// a loaded frame far away (or far below) is not something to walk at: the room search digs down from dry land near it instead.
// the pure half of that, so the rule and the column pick are testable without a world
final class RoomRetarget {
    // close enough to walk straight at the ring: this near sideways and within a dig-less climb vertically
    // SEARCH_RADIUS plus a dig shift, the frame's offset from the ring middle and a sloppy stand on the column
    static final double NEAR_HORIZONTAL = 109;
    static final double NEAR_VERTICAL = 12;
    // how far from the frame we look for dry land. inside NEAR_HORIZONTAL so the dig column itself counts as near.
    // 100 means a tunnel of a few minutes in the worst case, which still beats floating over a ring you can't dig to
    // with drowned throwing tridents at you
    static final int SEARCH_RADIUS = 100;
    // the lava pool sits right under the portal, keep the dig column well off it
    static final double KEEP_OFF_RING = 6;
    // blocks under the surface that must be free of fluid (a dig column goes through them)
    static final int FLUID_DEPTH = 4;
    static final int MAX_RETARGETS = 2;
    // the approach counts as stuck when it has not come this much closer in this long
    static final double PROGRESS_BLOCKS = 3;
    static final double PROGRESS_SECONDS = 10;
    // the shore around a ring this far off is loaded, so "no dry land" is an answer and not just a chunk we haven't got yet
    static final double SHORE_LOADED_BLOCKS = 96;
    // nobody swims a stronghold's worth of ocean in this long, stop believing in the walk. total stage time on purpose, the
    // no progress clock never gets here while a crawl keeps earning its three blocks. the longest tunnel is well inside it
    static final double FAR_WALK_CAP_SECONDS = 600;

    interface Probe {
        boolean loaded(int x, int z);

        // y of the top block that stops motion or holds fluid (so an ocean reads as its surface water)
        int topY(int x, int z);

        boolean solid(int x, int y, int z);

        boolean fluid(int x, int y, int z);
    }

    private RoomRetarget() {
    }

    // walk straight at it only from here, anything else goes down a dry column first
    static boolean shoreLoaded(double px, double pz, int fx, int fz) {
        return Math.hypot(fx - px, fz - pz) <= SHORE_LOADED_BLOCKS;
    }

    // the approach clock is for a ring we got to and can't see the rest of. a walk that started two thousand blocks out runs it down in
    // the first minute and hands OPEN a frame 70 blocks under the sea with no dig. it counts time with no progress: a tunnel to the
    // ring is slow and getting there, and calling it off halfway starts the fill interacts through solid rock
    static boolean approachOver(double stageSeconds, double stuckSeconds, double approachSeconds, double px, double pz, int fx, int fz) {
        return stageSeconds >= FAR_WALK_CAP_SECONDS || (stuckSeconds >= approachSeconds && shoreLoaded(px, pz, fx, fz));
    }

    static boolean near(double px, double py, double pz, int fx, int fy, int fz) {
        return Math.hypot(fx - px, fz - pz) <= NEAR_HORIZONTAL && Math.abs(fy - py) <= NEAR_VERTICAL;
    }

    static boolean progressed(double bestDist, double dist) {
        return dist <= bestDist - PROGRESS_BLOCKS;
    }

    // solid on top, no water or lava on it or in the few blocks a dig goes through (ice over an ocean fails on what is under it).
    // the sides count too: avoidBreaking won't touch a block next to a source, so a column at the waterline can't be dug at all
    // and every cell of it burns a minute finding that out
    static boolean dry(Probe probe, int x, int z) {
        if (!probe.loaded(x, z)) {
            return false;
        }
        int top = probe.topY(x, z);
        return probe.solid(x, top, z) && noFluid(probe, x, z, top)
                && sideDry(probe, x + 1, z, top) && sideDry(probe, x - 1, z, top)
                && sideDry(probe, x, z + 1, top) && sideDry(probe, x, z - 1, top);
    }

    private static boolean noFluid(Probe probe, int x, int z, int top) {
        for (int y = top + 1; y >= top - FLUID_DEPTH; y--) {
            if (probe.fluid(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    // an unloaded chunk reads as no fluid at all, which is not the same as dry. the side's own surface is for a cliff over the sea:
    // the water is way under the depth we check here, and exactly where the dig runs into it
    private static boolean sideDry(Probe probe, int x, int z, int top) {
        return probe.loaded(x, z) && noFluid(probe, x, z, top) && !probe.fluid(x, probe.topY(x, z), z);
    }

    // loaded and fluid on top. unloaded is not water, it's nothing yet
    static boolean underWater(Probe probe, int x, int z) {
        return probe.loaded(x, z) && probe.fluid(x, probe.topY(x, z), z);
    }

    // feet above the sea floor: the way in is the shore, a shaft and a tunnel
    static boolean shoreWalk(Probe probe, int x, int z, double playerY) {
        return underWater(probe, x, z) && playerY > seaFloorY(probe, x, z);
    }

    // already under it (a relog mid tunnel, a stall retry, a hint walk that gave up): the start is a tunnel away. the shore walk would
    // redo the whole thing, and a plain walk to the start's x/z ends on the water and digs from the surface
    static boolean underSeaFloor(Probe probe, int x, int z, double playerY) {
        return underWater(probe, x, z) && playerY <= seaFloorY(probe, x, z);
    }

    // a start estimate out on the water: the dry column to dig down from instead of the surface over it. empty = it isn't
    // water we can see, or there's no shore inside SEARCH_RADIUS
    static Optional<int[]> shoreFor(Probe probe, int x, int z) {
        return underWater(probe, x, z) ? pickColumn(probe, x, z, x, z) : Optional.empty();
    }

    // the sea floor under a water start, minus this much rock so the tunnel over to it doesn't open the sea into itself
    static final int TUNNEL_UNDER_SEABED = 6;
    static final int WORLD_BOTTOM = -64;

    static int tunnelY(Probe probe, int x, int z) {
        return seaFloorY(probe, x, z) - TUNNEL_UNDER_SEABED;
    }

    // the first block under the water
    static int seaFloorY(Probe probe, int x, int z) {
        int y = probe.topY(x, z);
        while (y > WORLD_BOTTOM && probe.fluid(x, y, z)) {
            y--;
        }
        return y;
    }

    // the nearest dry column to the frame's x/z (square rings out, nearest of the first ring that has one), at least
    // KEEP_OFF_RING from the ring centre. empty = nothing in range, the caller keeps the old straight walk
    static Optional<int[]> pickColumn(Probe probe, int fx, int fz, int cx, int cz) {
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = fx + dx;
                    int z = fz + dz;
                    double d = Math.hypot(dx, dz);
                    // the square's corners are further than the radius, and the near rule is radial
                    if (d > SEARCH_RADIUS || d >= bestD || Math.hypot(x - cx, z - cz) < KEEP_OFF_RING || !dry(probe, x, z)) {
                        continue;
                    }
                    bestD = d;
                    best = new int[]{x, z};
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
