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

        // a block we could mine out to make room: solid, breakable with what we hold, and not bedrock, a container, an ore
        // or anything wet. mining it must not be a favour to somebody (a chest) or to the bot (the iron we came for)
        boolean carvable(int x, int y, int z);

        // opening the neighbour lets this in: a liquid that flows, or sand and gravel that fall
        boolean floods(int x, int y, int z);

        // a station of ours or any workbench (StationHook.keepStanding): never carved, whatever carvable says. the furnace once
        // went in the hole where the table it was crafted at had stood
        default boolean keep(int x, int y, int z) {
            return false;
        }
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

    // a block cell: a carve candidate, or the one we already carved
    record Cell(int x, int y, int z) {
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
        return best(w, me, bans, null);
    }

    // `carved` is a cell we mined out ourselves to have somewhere to put the block. it doesn't have to leave a way out: in
    // a 1x1 shaft the other exits are straight up and down, and the hole we dug is the only horizontal neighbour there is
    static Optional<Spot> best(Cells w, Stance me, Bans bans, Cell carved) {
        Spot top = scan(w, me, bans, carved, false);
        return top != null ? Optional.of(top) : Optional.ofNullable(scan(w, me, bans, carved, true));
    }

    private static Spot scan(Cells w, Stance me, Bans bans, Cell carved, boolean sides) {
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
                    if (!w.placeable(x, y, z)) {
                        continue;
                    }
                    boolean dugByUs = carved != null && carved.x() == x && carved.y() == y && carved.z() == z;
                    if (!dugByUs && !leavesAWayOut(w, me, x, y, z)) {
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

    // blocks to mine out when nothing around us takes a station, best first: beside our feet (what a player does in a tunnel:
    // knock out the wall and put the furnace in the hole), then beside our head, then two out along a line that is already
    // open. each needs a full block under it for the floor and nothing next to it that would pour in once it is open
    static List<Cell> carveCandidates(Cells w, Stance me, Bans banned) {
        List<Cell> out = new ArrayList<>();
        for (int[] h : HORIZONTALS) {
            addCarve(w, me, banned, out, me.feetX() + h[0], me.feetY(), me.feetZ() + h[1]);
        }
        for (int[] h : HORIZONTALS) {
            addCarve(w, me, banned, out, me.feetX() + h[0], me.feetY() + 1, me.feetZ() + h[1]);
        }
        for (int[] h : HORIZONTALS) {
            // the look down at the floor two out goes through the cell between, so that one has to be open. it never dips
            // under feet level on the way, so the floor of the cell between doesn't matter
            if (w.passable(me.feetX() + h[0], me.feetY(), me.feetZ() + h[1])) {
                addCarve(w, me, banned, out, me.feetX() + 2 * h[0], me.feetY(), me.feetZ() + 2 * h[1]);
            }
        }
        return out;
    }

    private static void addCarve(Cells w, Stance me, Bans banned, List<Cell> out, int x, int y, int z) {
        if (banned.isBanned(x, y, z) || w.keep(x, y, z) || !w.carvable(x, y, z)) {
            return;
        }
        // a floor to stand the block on, in reach. the look from our eye down to that floor stays inside our own cell and the
        // one we open, so unlike best() there is no raycast to ask (the cell is still solid right now, a raycast would lie)
        Spot onFloor = new Spot(x, y, z, x, y - 1, z);
        if (!w.faceSturdy(x, y - 1, z, 0, 1, 0) || faceDistSq(me, onFloor) > REACH * REACH) {
            return;
        }
        // above and the four sides. the floor is covered by being a sturdy, dry face
        if (w.floods(x, y + 1, z) || w.floods(x + 1, y, z) || w.floods(x - 1, y, z) || w.floods(x, y, z + 1) || w.floods(x, y, z - 1)) {
            return;
        }
        out.add(new Cell(x, y, z));
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
