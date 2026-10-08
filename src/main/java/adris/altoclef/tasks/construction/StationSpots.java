package adris.altoclef.tasks.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// where a table or furnace goes, picked the way a player does it: look at the floor next to you and click it. the old
// way asked baritone's builder to place it and the builder walked, scaffolded and wandered for 8 seconds a table (and
// sat on a fence forever). pure, the world comes in through Cells, so the scoring tests without a game
final class StationSpots {
    // how far the click can be from the eye. baritone reaches 4.5, the margin is for the bot drifting while it aims
    static final double REACH = 4.0;
    // horizontal search radius around the feet
    static final int RADIUS = 4;
    // a cell this many blocks under (or over) our feet is a climb for the eye. heavier than distance on purpose: a spot
    // on our level 2 blocks further off beats one a step down right next to us
    static final double LEVEL = 6;
    // standing point search for a relocation
    static final int MIN_MOVE = 2;
    static final int MAX_MOVE = 6;
    // a full ring of columns is ~160 and each costs a few hundred cell checks, once per relocation (twice a task at most).
    // this used to be 30, and the 30 nearest all look at the same floor we are already failing on
    static final int MAX_STANDPOINTS_TRIED = 200;
    // a failed spot takes its column with it, this far up and down. a click that missed once misses again from here
    static final int BAN_Y = 2;

    private StationSpots() {
    }

    // the questions we ask the world. every coordinate is a block cell
    interface Cells {
        // air or something a block replaces (grass), nothing in the way, not water
        boolean placeable(int x, int y, int z);

        // the face of the block at x,y,z that points (fx,fy,fz) is a full solid face you can build on. fence tops and
        // bottom slabs are not, which is the point of asking instead of "is it solid"
        boolean faceSturdy(int x, int y, int z, int fx, int fy, int fz);

        // we can stand in it (no collision, no liquid)
        boolean passable(int x, int y, int z);

        // standing on the block here is fine: a full block under it, feet and head cells open
        boolean standable(int x, int y, int z);

        // a ray from the eye to the middle of that face of the support hits the face, and nothing else gets in the way
        boolean visible(double eyeX, double eyeY, double eyeZ, int sx, int sy, int sz, int fx, int fy, int fz, int cx, int cy, int cz);
    }

    // where we are standing and where the eye is (sneaking eye if we will sneak, the caller knows)
    record Stance(int feetX, int feetY, int feetZ, double eyeX, double eyeY, double eyeZ) {
        boolean isOwnCell(int x, int y, int z) {
            return x == feetX && z == feetZ && (y == feetY || y == feetY + 1);
        }
    }

    // `placed` is the cell the block goes in, `support` is the block we click, and the face is the way from one to the other
    record Spot(int x, int y, int z, int sx, int sy, int sz) {
        int faceX() {
            return x - sx;
        }

        int faceY() {
            return y - sy;
        }

        int faceZ() {
            return z - sz;
        }

        boolean isTopFace() {
            return faceY() == 1;
        }
    }

    record Stand(int x, int y, int z) {
    }

    // spots that let us down. banning a cell takes its column, BAN_Y either way
    static final class Bans {
        private final List<int[]> banned = new ArrayList<>();

        void ban(int x, int y, int z) {
            banned.add(new int[]{x, y, z});
        }

        boolean isBanned(int x, int y, int z) {
            for (int[] b : banned) {
                if (b[0] == x && b[2] == z && Math.abs(b[1] - y) <= BAN_Y) {
                    return true;
                }
            }
            return false;
        }

        void clear() {
            banned.clear();
        }
    }

    private static final int[][] HORIZONTALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // lower is better. distance from our feet, plus LEVEL per block of height away from them (feet level wins)
    static double score(double horizontalDistSq, int levelsAway) {
        return horizontalDistSq + LEVEL * Math.abs(levelsAway);
    }

    // the best cell to put the block in from where we stand, clicking the floor next to us. if no floor works, the side
    // of a wall. empty when neither does
    static Optional<Spot> best(Cells w, Stance me, Bans bans) {
        Spot top = scan(w, me, bans, false);
        return top != null ? Optional.of(top) : Optional.ofNullable(scan(w, me, bans, true));
    }

    private static Spot scan(Cells w, Stance me, Bans bans, boolean sides) {
        Spot best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                // the cheap part first, the raycasts are what costs
                double distSq = dx * dx + dz * dz;
                for (int dy = -3; dy <= 1; dy++) {
                    int x = me.feetX() + dx;
                    int y = me.feetY() + dy;
                    int z = me.feetZ() + dz;
                    double score = score(distSq, dy);
                    if (score >= bestScore || me.isOwnCell(x, y, z) || bans.isBanned(x, y, z)) {
                        continue;
                    }
                    if (!w.placeable(x, y, z) || !leavesAWayOut(w, me, x, y, z)) {
                        continue;
                    }
                    Spot spot = sides ? sideSupport(w, me, x, y, z) : topSupport(w, me, x, y, z);
                    if (spot != null) {
                        best = spot;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static Spot topSupport(Cells w, Stance me, int x, int y, int z) {
        return clickable(w, me, new Spot(x, y, z, x, y - 1, z)) ? new Spot(x, y, z, x, y - 1, z) : null;
    }

    // the wall next to the cell, whichever of them the eye sees from nearest
    private static Spot sideSupport(Cells w, Stance me, int x, int y, int z) {
        Spot best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int[] h : HORIZONTALS) {
            Spot spot = new Spot(x, y, z, x - h[0], y, z - h[1]);
            double dist = faceDistSq(me, spot);
            if (dist < bestDist && clickable(w, me, spot)) {
                best = spot;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean clickable(Cells w, Stance me, Spot s) {
        return faceDistSq(me, s) <= REACH * REACH
                && w.faceSturdy(s.sx(), s.sy(), s.sz(), s.faceX(), s.faceY(), s.faceZ())
                && w.visible(me.eyeX(), me.eyeY(), me.eyeZ(), s.sx(), s.sy(), s.sz(), s.faceX(), s.faceY(), s.faceZ(), s.x(), s.y(), s.z());
    }

    // eye to the middle of the face we would click
    static double faceDistSq(Stance me, Spot s) {
        double dx = s.sx() + 0.5 + s.faceX() * 0.5 - me.eyeX();
        double dy = s.sy() + 0.5 + s.faceY() * 0.5 - me.eyeY();
        double dz = s.sz() + 0.5 + s.faceZ() * 0.5 - me.eyeZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // the block can't wall us in: after it goes down some neighbour of our feet cell is still open at both feet and head
    // height. nobody wants a table that is the last door out of a hole
    static boolean leavesAWayOut(Cells w, Stance me, int x, int y, int z) {
        for (int[] h : HORIZONTALS) {
            int nx = me.feetX() + h[0];
            int nz = me.feetZ() + h[1];
            boolean taken = nx == x && nz == z && (y == me.feetY() || y == me.feetY() + 1);
            if (!taken && w.passable(nx, me.feetY(), nz) && w.passable(nx, me.feetY() + 1, nz)) {
                return true;
            }
        }
        return false;
    }

    // somewhere 2-6 blocks away to stand where best() finds a spot, nearest first. empty if the whole neighbourhood is bad.
    // eyeHeight is the eye over the feet (sneaking one, if the placing sneaks)
    static Optional<Stand> standpoint(Cells w, Stance me, Bans bans, double eyeHeight) {
        List<Stand> stands = new ArrayList<>();
        for (int dx = -MAX_MOVE; dx <= MAX_MOVE; dx++) {
            for (int dz = -MAX_MOVE; dz <= MAX_MOVE; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < MIN_MOVE) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    int x = me.feetX() + dx;
                    int y = me.feetY() + dy;
                    int z = me.feetZ() + dz;
                    if (!bans.isBanned(x, y, z) && w.standable(x, y, z)) {
                        stands.add(new Stand(x, y, z));
                    }
                }
            }
        }
        stands.sort(Comparator.comparingDouble((Stand s) -> sq(s.x() - me.feetX()) + sq(s.z() - me.feetZ()) + LEVEL * Math.abs(s.y() - me.feetY())));
        int tried = 0;
        for (Stand s : stands) {
            if (tried++ >= MAX_STANDPOINTS_TRIED) {
                break;
            }
            Stance there = new Stance(s.x(), s.y(), s.z(), s.x() + 0.5, s.y() + eyeHeight, s.z() + 0.5);
            if (best(w, there, bans).isPresent()) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static double sq(double v) {
        return v * v;
    }
}
