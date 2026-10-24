package adris.altoclef.tasks.speedrun.gamer.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// pure: the lava bucket portal, built in the pool the way the speedrun does it. the frame stands in the shore row of a
// lava pool: four lava sources in a row, shore right behind them, a one deep pool. no world access in here, everything
// reads a Terrain, so the whole build runs in a test
//
// coordinates are (u, y, d): u runs along the row, y is up (0 = the lava surface layer, the same as the shore's top
// layer), d is toward the shore (so the pool is at d < 0). d=0 is the frame plane. Layout turns those into world cells.
//
// base: a throwaway block goes into the lava at (1,0,0), water goes in beside it at (2,0,0). the water cements
// the corner (3,0,0) and the cell in front of it, then the block comes out, the water runs into its cell and cements the
// other corner (0,0,0) and the cell in front of that. the water goes back in the bucket. what is left is two obsidian
// corners, an empty pit between them over the pool floor, and two obsidian cells in the pool in front of the pit to stand
// on (the platform)
//
// walls: seven cobble blocks and nothing else. on the shore behind the frame (d=1) a 2 tall pillar behind the left corner
// with a step beside it, a 3 tall pillar behind the right corner, and an arm on its top in the frame plane. the lava goes
// in by clicking their pool facing sides from the platform, and the water spills down onto them and into the pit
//
// the portal is the frame one layer lower than the corners: its bottom is the pool floor under the pit (broken out and
// filled with lava while the water pours in), the corners are the lowest cells of the side columns
//
// water: one source behind the top right of the interior. it falls behind the frame and spills into it at the top, and over
// the pit, and a spill over the top left falls onto the step and comes into the frame over the left half of the pit. lava goes
// into a cell only while there is water next to it, and the casts that need water to keep running go first
public final class LPortalPlan {
    public enum Mat {
        AIR, SOLID, OBSIDIAN, LAVA, LAVA_FLOW, WATER, WATER_FLOW, PORTAL;

        public boolean solid() {
            return this == SOLID || this == OBSIDIAN;
        }

        public boolean wet() {
            return this == WATER || this == WATER_FLOW;
        }

        public boolean lava() {
            return this == LAVA || this == LAVA_FLOW;
        }
    }

    public interface Terrain {
        Mat at(int x, int y, int z);
    }

    public record P(int x, int y, int z) {
    }

    // what is in the bag. hasSpot = there is a lava source to scoop, from where we stand or from a stand we can walk to
    public record Inv(int lava, boolean water, int empty, int blocks, boolean hasSpot) {
    }

    public enum Kind {
        SCOOP, PLACE_BLOCK, PLACE_STACK, BREAK, WATER_PLACE, LAVA_PLACE, LAVA_UNDO, WATER_SCOOP, LIGHT, WAIT, STUCK, DONE
    }

    // cell = what the step is about, support + face = the block we click and which side of it (world offset), stand =
    // where we want our feet (null = the task picks)
    public record Step(Kind kind, P cell, P support, int fx, int fy, int fz, P stand, String why) {
        public String toString() {
            return kind + (cell == null ? "" : " " + cell) + (why == null ? "" : " (" + why + ")");
        }
    }

    // one cast: lava goes into t, clicking the face of support that looks at it, from stand
    public record Cast(String name, int[] t, int[] support, int[] stand, String[] after) {
        public Cast(String name, int[] t, int[] support, int[] stand) {
            this(name, t, support, stand, new String[0]);
        }
    }

    public record Scoop(P source, P stand) {
    }

    // where we stand on the platform, one cell in front of the frame
    private static final int[] LEFT_STAND = {1, 1, -1};
    private static final int[] RIGHT_STAND = {2, 1, -1};
    // on the shore edge, right behind the row: from further back the shore edge is in the way of the floor and the lava cells
    private static final int[] SHORE_LEFT = {1, 1, 1};
    private static final int[] SHORE_RIGHT = {3, 1, 1};

    // the bot stands higher on the left stairs (step, then the pillar top) to take the source back, it is out of sight from the platform
    private static final int[] HIGH_STAND = {0, 3, 1};

    // one water source does it all. it is poured behind the frame, at the top right of the interior's shore row, against the
    // side of the right pillar's top block. it falls down behind the right interior column and spreads into the frame plane
    // at the top and at the bottom of that fall (over the pit), which wets the right column and the top. and its spread over the
    // top reaches the cell behind the top left, which falls onto the step: the step turns that water into the frame plane over
    // the left half of the pit, which wets the left column. the floor under the pit is broken out before it goes in, so the
    // bottom cells are cast under the water. the right column goes before the top right, which cuts its water off
    public record Round(String name, int[] source, int[] sourceSupport, int[] stand, int[] scoopStand, List<Cast> casts) {
    }

    public static final List<Round> ROUNDS = List.of(
            new Round("a", new int[]{2, 3, 1}, new int[]{3, 3, 1}, RIGHT_STAND, HIGH_STAND, List.of(
                    new Cast("r1", new int[]{3, 1, 0}, new int[]{3, 1, 1}, RIGHT_STAND),
                    new Cast("r2", new int[]{3, 2, 0}, new int[]{3, 2, 1}, RIGHT_STAND),
                    new Cast("l1", new int[]{0, 1, 0}, new int[]{0, 1, 1}, LEFT_STAND),
                    new Cast("l2", new int[]{0, 2, 0}, new int[]{0, 2, 1}, LEFT_STAND),
                    new Cast("b2", new int[]{2, -1, 0}, new int[]{2, -1, 1}, LEFT_STAND),
                    new Cast("b1", new int[]{1, -1, 0}, new int[]{1, -1, 1}, LEFT_STAND),
                    // against the arm, then t1 against t2
                    new Cast("t2", new int[]{2, 3, 0}, new int[]{3, 3, 0}, RIGHT_STAND, new String[]{"r1", "r2"}),
                    new Cast("t1", new int[]{1, 3, 0}, new int[]{2, 3, 0}, LEFT_STAND))));

    // the cobble on the shore behind the frame, seven blocks with the arm: a 2 tall pillar behind the left corner and a step
    // beside it toward the frame, a 3 tall pillar behind the right corner, and the arm on its top, in the frame plane over
    // the right corner. the builder places the shore ones, the arm goes in from the platform against the top of the pillar
    public static final int[][] WALLS = {
            {0, 1, 1}, {0, 2, 1}, {1, 1, 1},
            {3, 1, 1}, {3, 2, 1}, {3, 3, 1}};
    public static final int[] ARM = {3, 3, 0};
    public static final int[] ARM_SUPPORT = {3, 3, 1};
    public static final int[][] INTERIOR = {{1, 0, 0}, {2, 0, 0}, {1, 1, 0}, {2, 1, 0}, {1, 2, 0}, {2, 2, 0}};
    // the pool floor under the pit, which becomes the bottom of the frame
    public static final int[][] BOTTOM = {{1, -1, 0}, {2, -1, 0}};

    // the base: where the water goes in and the block it is poured against, the corners it makes
    public static final int[] PLACEHOLDER = {1, 0, 0};
    public static final int[] BASE_WATER = {2, 0, 0};
    public static final int[] CORNER_RIGHT = {3, 0, 0};
    public static final int[] CORNER_LEFT = {0, 0, 0};
    public static final int[] PLATFORM_LEFT = {1, 0, -1};
    public static final int[] PLATFORM_RIGHT = {2, 0, -1};

    // eight casts, and a source taken is gone, so some spare for a miss or an undo
    public static final int SUPPLY_NEEDED = 10;
    // how far around the row the build looks for lava, also what the task scans
    public static final int SCAN = 22;

    private LPortalPlan() {
    }

    // turns (u, y, d) into world cells. (ax, az) is the unit step along u, (nx, nz) the unit step toward the shore
    public record Layout(int ox, int oy, int oz, int ax, int az, int nx, int nz) {
        public P cell(int u, int y, int d) {
            return new P(ox + u * ax + d * nx, oy + y, oz + u * az + d * nz);
        }

        public P cell(int[] c) {
            return cell(c[0], c[1], c[2]);
        }

        // a local offset as a world offset
        public int[] dir(int du, int dy, int dd) {
            return new int[]{du * ax + dd * nx, dy, du * az + dd * nz};
        }
    }

    private static Mat m(Layout l, Terrain t, int[] c) {
        P p = l.cell(c);
        return t.at(p.x(), p.y(), p.z());
    }

    private static Mat m(Layout l, Terrain t, int u, int y, int d) {
        P p = l.cell(u, y, d);
        return t.at(p.x(), p.y(), p.z());
    }

    // ---- is this a place for it ----

    // the cheap part: four lava sources in a row with shore right behind them, lava in front of the middle two, and the
    // pool floor under the row
    static boolean quick(Layout l, Terrain t) {
        for (int u = 0; u <= 3; u++) {
            if (m(l, t, u, 0, 0) != Mat.LAVA || m(l, t, u, 0, 1) != Mat.SOLID || m(l, t, u, -1, 0) != Mat.SOLID) {
                return false;
            }
        }
        for (int u = 1; u <= 2; u++) {
            if (m(l, t, u, 0, -1) != Mat.LAVA || m(l, t, u, -1, -1) != Mat.SOLID) {
                return false;
            }
        }
        return true;
    }

    // the row, room above it for the frame and the walls and the stands, no water about, and enough lava around to scoop
    public static boolean fits(Layout l, Terrain t) {
        if (!quick(l, t)) {
            return false;
        }
        for (int u = 0; u <= 3; u++) {
            if (m(l, t, u, 0, 2) != Mat.SOLID || m(l, t, u, -1, 1) != Mat.SOLID) {
                return false;
            }
            for (int y = 1; y <= 4; y++) {
                if (m(l, t, u, y, 0) != Mat.AIR) {
                    return false;
                }
            }
            for (int y = 1; y <= 4; y++) {
                if (m(l, t, u, y, 1) != Mat.AIR || (y <= 3 && m(l, t, u, y, 2) != Mat.AIR)) {
                    return false;
                }
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int y = 1; y <= 4; y++) {
                if (m(l, t, u, y, -1) != Mat.AIR) {
                    return false;
                }
            }
        }
        for (int u = -2; u <= 5; u++) {
            for (int d = -3; d <= 3; d++) {
                for (int y = -2; y <= 6; y++) {
                    Mat now = m(l, t, u, y, d);
                    if (now.wet() || now == Mat.PORTAL) {
                        return false;
                    }
                }
            }
        }
        return supply(l, t) >= SUPPLY_NEEDED;
    }

    // the lava sources we could scoop (the same ones scoopSpot takes) in the layer of the row around it, which is the stretch the
    // build looks at, counted from the world so a far lake or a short tracker list changes nothing
    static int supply(Layout l, Terrain t) {
        P mid = l.cell(1, 0, 0);
        int n = 0;
        for (int x = mid.x() - SCAN; x <= mid.x() + SCAN; x++) {
            for (int z = mid.z() - SCAN; z <= mid.z() + SCAN; z++) {
                if (eligible(t, l, new P(x, mid.y(), z))) {
                    n++;
                }
            }
        }
        return n;
    }

    // layouts worth a full look, nearest first. sources = lava source cells
    public static List<Layout> candidates(Terrain t, List<P> sources, int px, int pz) {
        Set<Layout> seen = new HashSet<>();
        List<Layout> out = new ArrayList<>();
        int[][] axes = {{1, 0}, {0, 1}};
        for (P s : sources) {
            for (int[] a : axes) {
                for (int k = 0; k < 4; k++) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        Layout l = new Layout(s.x() - k * a[0], s.y(), s.z() - k * a[1], a[0], a[1], -a[1] * sign, a[0] * sign);
                        if (seen.add(l) && quick(l, t)) {
                            out.add(l);
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(l -> {
            P c = l.cell(1, 0, 0);
            return sq(c.x() - px) + sq(c.z() - pz);
        }));
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---- scooping ----

    // how far from the eyes we scoop. the bucket reaches 4.5, and the bot is never exactly where it was planned from
    private static final double SCOOP_REACH = 4.0;
    private static final double EYES = 1.62;

    // the shore cell beside a source, or null. the click is then a short straight one and nothing we built is in the way
    static P standFor(Terrain t, P p) {
        if (t.at(p.x(), p.y(), p.z()) != Mat.LAVA || t.at(p.x(), p.y() + 1, p.z()) != Mat.AIR) {
            return null;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            int sx = p.x() + dir[0];
            int sz = p.z() + dir[1];
            if (t.at(sx, p.y(), sz) == Mat.SOLID && t.at(sx, p.y() + 1, sz) == Mat.AIR && t.at(sx, p.y() + 2, sz) == Mat.AIR) {
                return new P(sx, p.y() + 1, sz);
            }
        }
        return null;
    }

    // a lava source we may take: open on top, and not one of the cells the frame is built on (the row and the platform in
    // front of it). once the base is set the lava around them has nothing to do with the build
    private static boolean eligible(Terrain t, Layout l, P p) {
        if (t.at(p.x(), p.y(), p.z()) != Mat.LAVA || t.at(p.x(), p.y() + 1, p.z()) != Mat.AIR) {
            return false;
        }
        if (l != null) {
            for (int u = 0; u <= 3; u++) {
                for (int d = -1; d <= 0; d++) {
                    if (l.cell(u, 0, d).equals(p)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // a straight line from the eyes to the middle of the cell that no solid block is on
    private static boolean seen(Terrain t, double ex, double ey, double ez, P target) {
        double tx = target.x() + 0.5;
        double ty = target.y() + 0.5;
        double tz = target.z() + 0.5;
        double len = Math.sqrt(sq(tx - ex) + sq(ty - ey) + sq(tz - ez));
        int steps = (int) Math.ceil(len / 0.2);
        for (int i = 0; i < steps; i++) {
            double f = (double) i / steps;
            int x = (int) Math.floor(ex + (tx - ex) * f);
            int y = (int) Math.floor(ey + (ty - ey) * f);
            int z = (int) Math.floor(ez + (tz - ez) * f);
            if (x == target.x() && y == target.y() && z == target.z()) {
                return true;
            }
            if (t.at(x, y, z).solid()) {
                return false;
            }
        }
        return true;
    }

    // where to take the next bucket of lava. first from right where we stand, nothing to walk. else from one of the platform
    // stands the casts use (we are going there anyway), else from the shore cell beside a source, whichever is the shortest
    // walk. (ex, ey, ez) = our eyes, skip = sources that did not work from where we stood. stand null = scoop in place
    public static Scoop scoopSpot(Terrain t, Iterable<P> lava, Layout l, double ex, double ey, double ez, Set<P> skip) {
        List<P> sources = new ArrayList<>();
        for (P p : lava) {
            if (eligible(t, l, p) && !skip.contains(p)) {
                sources.add(p);
            }
        }
        P near = null;
        double nearD = SCOOP_REACH * SCOOP_REACH;
        for (P p : sources) {
            double d = sq(p.x() + 0.5 - ex) + sq(p.y() + 0.5 - ey) + sq(p.z() + 0.5 - ez);
            if (d <= nearD && seen(t, ex, ey, ez, p)) {
                nearD = d;
                near = p;
            }
        }
        if (near != null) {
            return new Scoop(near, null);
        }
        List<P> stands = new ArrayList<>();
        if (l != null) {
            for (int[] s : new int[][]{LEFT_STAND, RIGHT_STAND}) {
                P c = l.cell(s);
                if (t.at(c.x(), c.y(), c.z()) == Mat.AIR && t.at(c.x(), c.y() + 1, c.z()) == Mat.AIR && t.at(c.x(), c.y() - 1, c.z()).solid()) {
                    stands.add(c);
                }
            }
        }
        Scoop best = null;
        double bestCost = Double.MAX_VALUE;
        for (P p : sources) {
            for (P s : stands) {
                double sx = s.x() + 0.5;
                double sy = s.y() + EYES;
                double sz = s.z() + 0.5;
                double reach = sq(p.x() + 0.5 - sx) + sq(p.y() + 0.5 - sy) + sq(p.z() + 0.5 - sz);
                double cost = sq(sx - ex) + sq(sz - ez);
                if (reach <= SCOOP_REACH * SCOOP_REACH && cost < bestCost && seen(t, sx, sy, sz, p)) {
                    bestCost = cost;
                    best = new Scoop(p, s);
                }
            }
            P shore = standFor(t, p);
            if (shore != null) {
                // the shore stand only wins when it is clearly the shorter walk
                double cost = sq(shore.x() + 0.5 - ex) + sq(shore.z() + 0.5 - ez) + 1.0;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new Scoop(p, shore);
                }
            }
        }
        return best;
    }

    // ---- what to do next ----

    // water above the cell or beside it (any of the four sides)
    public static boolean wet(Layout l, Terrain t, int[] c) {
        int u = c[0];
        int y = c[1];
        int d = c[2];
        return m(l, t, u, y + 1, d).wet() || m(l, t, u + 1, y, d).wet() || m(l, t, u - 1, y, d).wet()
                || m(l, t, u, y, d + 1).wet() || m(l, t, u, y, d - 1).wet();
    }

    // the casts this one has to come after are all obsidian
    private static boolean madeBefore(Layout l, Terrain t, Round round, Cast c) {
        for (String name : c.after()) {
            for (Cast other : round.casts()) {
                if (other.name().equals(name) && m(l, t, other.t()) != Mat.OBSIDIAN) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean roundDone(Layout l, Terrain t, Round round) {
        for (Cast c : round.casts()) {
            if (m(l, t, c.t()) != Mat.OBSIDIAN) {
                return false;
            }
        }
        return true;
    }

    public static boolean allMade(Layout l, Terrain t) {
        for (Round r : ROUNDS) {
            if (!roundDone(l, t, r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMade(Layout l, Terrain t) {
        for (Round round : ROUNDS) {
            for (Cast c : round.casts()) {
                if (m(l, t, c.t()) == Mat.OBSIDIAN) {
                    return true;
                }
            }
        }
        return false;
    }

    // corners set, the two cells in front of the pit are solid to stand on, the pit has no lava or water source in it
    static boolean baseDone(Layout l, Terrain t) {
        if (m(l, t, CORNER_LEFT) != Mat.OBSIDIAN || m(l, t, CORNER_RIGHT) != Mat.OBSIDIAN) {
            return false;
        }
        if (!m(l, t, PLATFORM_LEFT).solid() || !m(l, t, PLATFORM_RIGHT).solid()) {
            return false;
        }
        for (int u = 1; u <= 2; u++) {
            Mat now = m(l, t, u, 0, 0);
            if (now.lava() || now == Mat.WATER) {
                return false;
            }
        }
        return true;
    }

    private static Step stuck(String why) {
        return new Step(Kind.STUCK, null, null, 0, 0, 0, null, why);
    }

    private static Step wait(String why) {
        return new Step(Kind.WAIT, null, null, 0, 0, 0, null, why);
    }

    private static Step click(Layout l, Kind kind, int[] target, int[] support, int[] stand, String why) {
        int[] f = l.dir(target[0] - support[0], target[1] - support[1], target[2] - support[2]);
        return new Step(kind, l.cell(target), l.cell(support), f[0], f[1], f[2], stand == null ? null : l.cell(stand), why);
    }

    private static Step place(Layout l, int[] cell, String why) {
        return new Step(Kind.PLACE_BLOCK, l.cell(cell), null, 0, 0, 0, null, why);
    }

    // the cells that have to stay empty: the frame plane above the pit and the platform lane in front of it
    public static List<int[]> keepClear() {
        List<int[]> cells = new ArrayList<>();
        for (int u = 0; u <= 3; u++) {
            for (int y = 1; y <= 4; y++) {
                boolean arm = u == ARM[0] && y == ARM[1];
                boolean wall = false;
                for (int[] w : WALLS) {
                    wall |= w[0] == u && w[1] == y && w[2] == 1;
                }
                if (!arm) {
                    cells.add(new int[]{u, y, 0});
                }
                // behind the frame, where the stairs and the walls are, is ours too
                if (!wall) {
                    cells.add(new int[]{u, y, 1});
                }
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int y = 1; y <= 4; y++) {
                cells.add(new int[]{u, y, -1});
            }
        }
        return cells;
    }

    // anything solid and not ours in those, and in the pit
    private static int[] junk(Layout l, Terrain t) {
        for (int[] c : keepClear()) {
            if (m(l, t, c) == Mat.SOLID) {
                return c;
            }
        }
        for (int u = 1; u <= 2; u++) {
            if (m(l, t, u, 0, 0) == Mat.SOLID) {
                return new int[]{u, 0, 0};
            }
        }
        return null;
    }

    // everything before the first pour is done: base, walls, floor
    private static boolean readyToPour(Layout l, Terrain t) {
        if (!baseDone(l, t)) {
            return false;
        }
        for (int[] w : WALLS) {
            if (!m(l, t, w).solid()) {
                return false;
            }
        }
        if (!m(l, t, ARM).solid()) {
            return false;
        }
        for (int[] b : BOTTOM) {
            if (m(l, t, b) == Mat.SOLID) {
                return false;
            }
        }
        return true;
    }

    // lava only when the next thing needs it and the bag has none. one bucket at a time: the other stays free for the water.
    // the base needs no lava, so the first action is the placeholder
    private static boolean scoopWanted(Inv inv, boolean needsLava) {
        return needsLava && inv.hasSpot() && inv.empty() >= 1 && inv.lava() == 0;
    }

    // the base, one step of it: the throwaway block into the lava, the water beside it, the block out, the water back
    private static Step base(Layout l, Terrain t, Inv inv) {
        Mat water = m(l, t, BASE_WATER);
        boolean block = m(l, t, PLACEHOLDER) == Mat.SOLID;
        if (water == Mat.WATER) {
            if (block) {
                if (m(l, t, CORNER_RIGHT) == Mat.OBSIDIAN && !m(l, t, PLATFORM_RIGHT).lava()) {
                    return new Step(Kind.BREAK, l.cell(PLACEHOLDER), null, 0, 0, 0, l.cell(SHORE_LEFT), "placeholder");
                }
                return wait("the right corner is cooking");
            }
            if (m(l, t, CORNER_LEFT) == Mat.OBSIDIAN && !m(l, t, PLATFORM_LEFT).lava() && !m(l, t, PLACEHOLDER).lava()) {
                return click(l, Kind.WATER_SCOOP, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "base water");
            }
            return wait("the left corner is cooking");
        }
        if (water == Mat.LAVA) {
            if (block) {
                if (!inv.water()) {
                    return stuck("no water bucket");
                }
                return click(l, Kind.WATER_PLACE, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "base water");
            }
            if (inv.blocks() < 1) {
                return stuck("no block for the placeholder");
            }
            return click(l, Kind.PLACE_STACK, PLACEHOLDER, new int[]{1, -1, 0}, SHORE_LEFT, "placeholder");
        }
        return stuck("the pit holds " + water + " before the base");
    }

    // what a build that gave up leaves behind that has to come back: water sources and lava that never cemented. null when
    // there is nothing, or no empty bucket to take it with
    public static Step cleanup(Layout l, Terrain t, int empty) {
        if (empty < 1) {
            return null;
        }
        // lava first, it is the one that runs and the one the cast fallback can't live next to
        for (Round r : ROUNDS) {
            for (Cast c : r.casts()) {
                if (m(l, t, c.t()) == Mat.LAVA) {
                    return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), "loose lava");
                }
            }
        }
        if (m(l, t, BASE_WATER) == Mat.WATER) {
            return click(l, Kind.WATER_SCOOP, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "loose water");
        }
        for (Round r : ROUNDS) {
            if (m(l, t, r.source()) == Mat.WATER) {
                return click(l, Kind.WATER_SCOOP, r.source(), r.sourceSupport(), r.scoopStand(), "loose water");
            }
        }
        return null;
    }

    public static Step next(Layout l, Terrain t, Inv inv) {
        for (int[] c : INTERIOR) {
            if (m(l, t, c) == Mat.PORTAL) {
                return new Step(Kind.DONE, null, null, 0, 0, 0, null, null);
            }
        }
        boolean allMade = allMade(l, t);
        boolean sourceUp = false;
        for (Round r : ROUNDS) {
            sourceUp |= m(l, t, r.source()) == Mat.WATER;
        }
        boolean casting = sourceUp || anyMade(l, t);
        // lava that went in with no water next to it runs, so it comes back before anything else, the bot is standing next to it
        if (!allMade && inv.empty() >= 1) {
            for (Round r : ROUNDS) {
                for (Cast c : r.casts()) {
                    if (m(l, t, c.t()) == Mat.LAVA) {
                        return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), c.name());
                    }
                }
            }
        }
        if (!allMade && scoopWanted(inv, casting || readyToPour(l, t))) {
            return new Step(Kind.SCOOP, null, null, 0, 0, 0, null, "lava");
        }
        if (!allMade && !baseDone(l, t) && !casting) {
            return base(l, t, inv);
        }
        if (!allMade) {
            for (int[] w : WALLS) {
                if (!m(l, t, w).solid()) {
                    if (inv.blocks() < 1) {
                        return stuck("no blocks for the walls");
                    }
                    return place(l, w, "wall");
                }
            }
            if (!m(l, t, ARM).solid()) {
                if (inv.blocks() < 1) {
                    return stuck("no block for the arm");
                }
                return click(l, Kind.PLACE_STACK, ARM, ARM_SUPPORT, RIGHT_STAND, "arm");
            }
            // the builder scaffolds in the frame while it climbs for the walls, so the clearing waits for them to be done
            int[] bad = junk(l, t);
            if (bad != null) {
                return new Step(Kind.BREAK, l.cell(bad), null, 0, 0, 0, null, "in the way");
            }
            if (!m(l, t, PLATFORM_LEFT).solid() || !m(l, t, PLATFORM_RIGHT).solid()) {
                return stuck("the platform is gone");
            }
            // the floor under the pit comes out before any water pours in, so it can be filled with lava under the water
            if (!casting) {
                for (int[] b : BOTTOM) {
                    if (m(l, t, b) == Mat.SOLID) {
                        return new Step(Kind.BREAK, l.cell(b), null, 0, 0, 0, l.cell(LEFT_STAND), "floor");
                    }
                }
            }
            Round round = null;
            for (Round r : ROUNDS) {
                if (round == null && !roundDone(l, t, r)) {
                    round = r;
                }
            }
            if (m(l, t, round.source()) != Mat.WATER) {
                if (!inv.water()) {
                    return stuck("no water bucket");
                }
                if (!m(l, t, round.sourceSupport()).solid()) {
                    return stuck("nothing to pour round " + round.name() + " against");
                }
                return click(l, Kind.WATER_PLACE, round.source(), round.sourceSupport(), round.stand(), "source " + round.name());
            }
            // the first cast in the list that can go now: its click block is there and water is next to it. the ones whose water
            // has not shown up yet wait, and the order only holds where it matters (see the round)
            String waiting = null;
            for (Cast c : round.casts()) {
                Mat now = m(l, t, c.t());
                if (now == Mat.OBSIDIAN) {
                    continue;
                }
                if (now == Mat.LAVA) {
                    // the lava went in with no water next to it. take it back before it runs
                    if (inv.empty() < 1) {
                        return stuck(c.name() + " holds lava and there is no bucket for it");
                    }
                    return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), c.name());
                }
                if (now != Mat.AIR && now != Mat.WATER_FLOW) {
                    return stuck(c.name() + " holds " + now);
                }
                if (!madeBefore(l, t, round, c)) {
                    if (waiting == null) {
                        waiting = c.name() + " goes after " + String.join(", ", c.after());
                    }
                    continue;
                }
                if (!m(l, t, c.support()).solid()) {
                    if (waiting == null) {
                        waiting = c.name() + " has nothing to click yet";
                    }
                    continue;
                }
                if (!wet(l, t, c.t())) {
                    if (waiting == null) {
                        waiting = c.name() + " is dry";
                    }
                    continue;
                }
                if (inv.lava() < 1) {
                    return stuck("out of lava and nowhere to scoop it");
                }
                return click(l, Kind.LAVA_PLACE, c.t(), c.support(), c.stand(), c.name());
            }
            if (waiting != null) {
                return wait(waiting);
            }
        }
        for (Round r : ROUNDS) {
            if (m(l, t, r.source()) == Mat.WATER) {
                if (inv.empty() < 1) {
                    return stuck("no empty bucket for the water");
                }
                return click(l, Kind.WATER_SCOOP, r.source(), r.sourceSupport(), r.scoopStand(), "last source");
            }
        }
        for (int[] c : INTERIOR) {
            if (m(l, t, c).wet()) {
                return wait("water still draining");
            }
        }
        P bottom = l.cell(1, -1, 0);
        return new Step(Kind.LIGHT, l.cell(1, 0, 0), bottom, 0, 1, 0, null, null);
    }
}
