package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    enum ApproachEnd {GO_ON, FINISH, UNDER_SEA}

    // approachOver, except for a bot floating over a ring under the sea: that's not being there, and OPEN can't fill a frame twenty
    // blocks down. a dry land ring (or one we're already under the floor of) ends exactly as it always did
    static ApproachEnd approachEnd(double stageSeconds, double stuckSeconds, double approachSeconds, Probe probe, double px, double py,
                                   double pz, int fx, int fz) {
        if (!approachOver(stageSeconds, stuckSeconds, approachSeconds, px, pz, fx, fz)) {
            return ApproachEnd.GO_ON;
        }
        return shoreWalk(probe, fx, fz, py) ? ApproachEnd.UNDER_SEA : ApproachEnd.FINISH;
    }

    static boolean near(double px, double py, double pz, int fx, int fy, int fz) {
        return near(px, py, pz, fx, fy, fz, NEAR_HORIZONTAL);
    }

    // reach is how far sideways still counts, see reachFor
    static boolean near(double px, double py, double pz, int fx, int fy, int fz, double reach) {
        return Math.hypot(fx - px, fz - pz) <= reach && Math.abs(fy - py) <= NEAR_VERTICAL;
    }

    // the slack NEAR_HORIZONTAL keeps over SEARCH_RADIUS (shift, frame offset, sloppy stand). a far shore needs the same on top of
    // its own distance, or the bottom of its shaft never counts as near and the dig runs on to the floor and calls the estimate wrong
    static final double REACH_SLACK = NEAR_HORIZONTAL - SEARCH_RADIUS;

    static double reachFor(int sx, int sz, int fx, int fz) {
        return Math.max(NEAR_HORIZONTAL, Math.hypot(sx - fx, sz - fz) + REACH_SLACK);
    }

    // a ring under the sea: the shaft goes down to about the ring before the walk takes over. turning at ring + 12 runs the tunnel
    // through the water band, and avoidBreaking won't touch a block next to water, so it just detours up and out
    static final int UNDER_SEA_TURN_ABOVE = 2;
    // inside this of the ring we're in the room or right by it (its stairs are a few blocks up), the depth rule is for the shaft
    static final double ROOM_HALF = 16;

    static boolean deepEnough(boolean ringUnderSea, double px, double py, double pz, int fx, int fy, int fz) {
        return !ringUnderSea || py <= fy + UNDER_SEA_TURN_ABOVE || Math.hypot(fx - px, fz - pz) <= ROOM_HALF;
    }

    // as far as we can see. unseen with a shore past SEARCH_RADIUS: no dry land in a hundred blocks of it is the sea
    static boolean ringUnderSea(Probe probe, int fx, int fz, double reach) {
        return probe.loaded(fx, fz) ? underWater(probe, fx, fz) : reach > NEAR_HORIZONTAL;
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
        return pickColumn(probe, fx, fz, cx, cz, SEARCH_RADIUS);
    }

    static Optional<int[]> pickColumn(Probe probe, int fx, int fz, int cx, int cz, int radius) {
        for (int r = 0; r <= radius; r++) {
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
                    if (d > radius || d >= bestD || Math.hypot(x - cx, z - cz) < KEEP_OFF_RING || !dry(probe, x, z)) {
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

    // past SEARCH_RADIUS the only shore we trust is one we stood on: the route's own pick, or the tracker's last dry land. this far
    // from the ring at most, a couple of minutes more of tunnel
    static final double FAR_SHORE_RADIUS = 200;
    // the remembered spot is wherever our feet last were, usually the beach with the sea right beside it. a real column is a few
    // blocks inland
    static final int CHECK_RADIUS = 12;

    // y is where we stand on it. checked = dry() passed on a loaded chunk, unchecked = a remembered spot we can't see from here yet
    record Shore(int x, int y, int z, boolean checked) {
    }

    // the one shore pick (the room's retarget, a water start, the portal dig after an end death): a dry column within SEARCH_RADIUS,
    // else the first remembered spot (x, y, z) within FAR_SHORE_RADIUS of the target that holds up. null entries are skipped
    static Optional<Shore> pickShore(Probe probe, int fx, int fz, int cx, int cz, List<int[]> remembered) {
        Optional<int[]> column = pickColumn(probe, fx, fz, cx, cz);
        if (column.isPresent()) {
            int[] c = column.get();
            return Optional.of(new Shore(c[0], probe.topY(c[0], c[1]) + 1, c[1], true));
        }
        Shore unseen = null;
        for (int[] r : remembered) {
            if (r == null || Math.hypot(r[0] - fx, r[2] - fz) > FAR_SHORE_RADIUS) {
                continue;
            }
            if (!probe.loaded(r[0], r[2])) {
                // we stood there, so it was land. whether it digs is asked once we're close enough to see it (checkShore).
                // one we can check right now still wins over it
                if (unseen == null) {
                    unseen = new Shore(r[0], r[1], r[2], false);
                }
                continue;
            }
            Optional<Shore> checked = checkShore(probe, r[0], r[2], cx, cz);
            if (checked.isPresent()) {
                return checked;
            }
            if (unseen == null && !checkLoaded(probe, r[0], r[2])) {
                // the beach is in but the column inland isn't yet, that's still a maybe and not a no
                unseen = new Shore(r[0], r[1], r[2], false);
            }
        }
        return Optional.ofNullable(unseen);
    }

    // the remembered spots in the order they're trusted: the route's own pick, then the tracker. bad = "x,z" of ones that didn't dig
    static List<int[]> remembered(int[] saved, int[] tracker, Set<String> bad) {
        List<int[]> out = new ArrayList<>();
        for (int[] r : new int[][]{saved, tracker}) {
            if (r != null && !bad.contains(key(r[0], r[2]))) {
                out.add(r);
            }
        }
        return out;
    }

    static String key(int x, int z) {
        return x + "," + z;
    }

    // everything checkShore looks at is loaded (dry() asks the neighbours too). points 13 apart never skip a 16 wide chunk
    static boolean checkLoaded(Probe probe, int x, int z) {
        int r = CHECK_RADIUS + 1;
        for (int dx = -r; dx <= r; dx += r) {
            for (int dz = -r; dz <= r; dz += r) {
                if (!probe.loaded(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // a remembered spot with its chunks in (checkLoaded): a dry column on it or next to it, else it's no shore
    static Optional<Shore> checkShore(Probe probe, int x, int z, int cx, int cz) {
        return pickColumn(probe, x, z, cx, cz, CHECK_RADIUS).map(c -> new Shore(c[0], probe.topY(c[0], c[1]) + 1, c[1], true));
    }
}
