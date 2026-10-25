/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the way through a course, remembered. every cell the player stands on goes onto a trail (the bot's or yours: playing a
// level by hand teaches it the way too), with the loops taken out and the bits after a fall dropped when the server puts
// you back. the trails, and how each Tungsten jump really went, are kept in the world's Baritone folder, so they're still
// there next session. a search towards a goal some trail goes past follows that trail (Settings.useRouteMemory): its cells
// cost less, and the part path it takes when it can't see all the way is the furthest it got along the trail, not the
// nearest it got to the goal as the crow flies (on a course that winds away from its finish first, that one's a dead end)
public final class RouteMemory {

    // how many trails a world keeps (the oldest goes), and how long one gets
    static final int MAX_TRAILS = 64, MAX_CELLS = 20_000;
    // a trail shorter than this isn't worth keeping
    static final int MIN_CELLS = 8;
    // how often the current trail is written out, in ticks (and whenever a goal is reached, and leaving the world)
    static final int SAVE_TICKS = 600;
    // a teleport this soon after the last one is a level's start settling in, see FallFloor
    static final int SETTLE_TICKS = 60;

    private static final Gson GSON = new GsonBuilder().create();

    private static final class Trail {
        final LongArrayList cells = new LongArrayList();
    }

    private static final List<Trail> TRAILS = new ArrayList<>();
    private static final Map<Long, int[]> JUMPS = new HashMap<>();
    // how far from where we are the end of a trail that stops short has to be, for it to be followed
    static final int FAR_ENOUGH = 5;
    // how many times a guide is given from the same spot, for one goal
    static final int GUIDE_TRIES = 3;
    private static final Long2IntOpenHashMap ASKED_FROM = new Long2IntOpenHashMap();

    // somewhere new to go: every spot gets its tries again
    public static synchronized void newGoal() {
        ASKED_FROM.clear();
    }
    private static Object world;
    private static Path file;
    private static boolean dirty;
    private static int ticks;
    // a teleport within this many ticks of stepping onto a block is that block putting us back (FallFloor.JUST_STEPPED)
    static final int PUT_BACK_TICKS = 20;
    // higher than this over where a flight left the ground is more than any jump: flown (or launched)
    static final double FLY_RISE = 1.8;
    // and lower than this under it, a fall (not a jump down a block or two onto the next one)
    static final double FALL_DROP = 3;
    // the player, laying down trails as they go
    private static Layer live = new Layer();

    private RouteMemory() {}

    // lays trails down from one run, tick by tick: the player's (live) or a recording's (learn)
    private static final class Layer {
        Trail current;
        long lastCell = Long.MIN_VALUE;
        double lastX, lastY, lastZ = Double.NaN;
        int sinceTeleport, cellTicks;
        // put back onto this cell of the trail: what's after it goes, once we see where we end up
        int pendingCut = -1;
        boolean lastGrounded;

        // airborne since leaving the trail, and how far up it got: a rise more than a jump's (flying, a fly back up after a
        // fall) that comes down on or by somewhere already on the trail is a way back there, like walking back
        boolean airborne;
        double takeoffY, peakY, standY;

        // grounded: standing on something; floating: in water or on a ladder (swimming up a hanging water block, climbing):
        // both are cells of the way
        void step(double x, double y, double z, int fx, int fy, int fz, boolean grounded, boolean floating) {
            sinceTeleport++;
            if (!Double.isNaN(lastZ)) {
                double dx = x - lastX, dz = z - lastZ;
                if (dx * dx + dz * dz > 16 || y - lastY > 2.5) {
                    // put back on a checkpoint after a fall (somewhere on the trail: what came after it was the way that
                    // didn't work), or somewhere new (a level's start, a /tp): a new trail
                    // (a put back comes after falling, off the ground or down below, or the moment we stepped onto
                    // something that does it; standing somewhere a while and then a teleport is a restart, or a /tp: the
                    // trail so far stays as it is, it's still the way)
                    boolean settling = sinceTeleport < SETTLE_TICKS;
                    boolean putBack = !lastGrounded || lastY < y - 1 || cellTicks <= PUT_BACK_TICKS;
                    sinceTeleport = 0;
                    int at = current == null || settling || !putBack ? -1 : indexNear(current.cells, new BetterBlockPos(fx, fy, fz), 1);
                    if (at >= 0) {
                        // (not cut yet: some servers put you back and then send you flying back up to where you got to,
                        // see the first cell stood on after)
                        pendingCut = at;
                    } else {
                        pendingCut = -1;
                        newTrail();
                    }
                    lastCell = Long.MIN_VALUE;
                }
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            lastGrounded = grounded || floating;
            cellTicks++;
            boolean flew = false, fell = false;
            if (!grounded && !floating) {
                if (!airborne) {
                    airborne = true;
                    takeoffY = peakY = standY;
                }
                peakY = Math.max(peakY, y);
            } else if (airborne) {
                airborne = false;
                flew = peakY - takeoffY > FLY_RISE;
                fell = y < takeoffY - FALL_DROP;
            }
            if (grounded || floating) {
                standY = y;
            }
            if (grounded || floating) {
                long cell = net.minecraft.core.BlockPos.asLong(fx, fy, fz);
                if (cell != lastCell) {
                    lastCell = cell;
                    cellTicks = 0;
                    if (current == null) {
                        newTrail();
                    }
                    if (pendingCut >= 0 && near(current.cells.getLong(pendingCut), fx, fy, fz, 1)) {
                        // still on the checkpoint we were put back on (a level's start pad, before it launches you): where
                        // we go from here says what to keep
                        return;
                    }
                    if (pendingCut >= 0) {
                        // the first cell after a put back: further on along the trail (launched back up to the last
                        // checkpoint, the way CubeCraft's Endurance does it) keeps the trail up to there; anywhere else, what
                        // came after the checkpoint we were put back on goes
                        int on = -1;
                        long back = current.cells.getLong(pendingCut);
                        boolean away = Math.abs(net.minecraft.core.BlockPos.getX(back) - fx) > 3 || Math.abs(net.minecraft.core.BlockPos.getY(back) - fy) > 3
                                || Math.abs(net.minecraft.core.BlockPos.getZ(back) - fz) > 3;
                        for (int i = current.cells.size() - 1; away && i > pendingCut; i--) {
                            long c = current.cells.getLong(i);
                            if (Math.abs(net.minecraft.core.BlockPos.getX(c) - fx) <= 1 && Math.abs(net.minecraft.core.BlockPos.getY(c) - fy) <= 1
                                    && Math.abs(net.minecraft.core.BlockPos.getZ(c) - fz) <= 1) {
                                on = i;
                                break;
                            }
                        }
                        current.cells.size((on >= 0 ? on : pendingCut) + 1);
                        pendingCut = -1;
                        dirty = true;
                    }
                    if ((flew || fell) && pendingCut < 0) {
                        // came down by somewhere earlier on the trail after a flight or a fall (flown back up after a miss,
                        // fallen down to a lower part of the course): like a put back, where we go from here says what to
                        // keep (flown back up to where we fell from keeps the way between; on from here, it goes)
                        for (int i = current.cells.size() - 1; i >= 0; i--) {
                            if (near(current.cells.getLong(i), fx, fy, fz, 1)) {
                                if (i < current.cells.size() - 2) {
                                    pendingCut = i;
                                    return;
                                }
                                break;
                            }
                        }
                    }
                    int seen = current.cells.indexOf(cell);
                    if (seen >= 0) {
                        current.cells.size(seen + 1); // back where we were: the loop since then goes
                    } else if (current.cells.size() < MAX_CELLS) {
                        current.cells.add(cell);
                        dirty = true;
                    }
                }
            }
        }

        void newTrail() {
            if (current != null && current.cells.size() < MIN_CELLS) {
                TRAILS.remove(current);
            }
            current = new Trail();
            TRAILS.add(current);
            while (TRAILS.size() > MAX_TRAILS) {
                TRAILS.remove(0);
            }
        }

        void done() {
            if (current != null && current.cells.size() < MIN_CELLS) {
                TRAILS.remove(current);
            }
            current = null;
        }
    }

    // every tick, with the player, where the cells are (playerFeet: a slab or a head counts as the cell over it), whether
    // they're on the ground, and the world's Baritone folder (null: none, nothing kept)
    public static synchronized void tick(Object level, Path worldDir, double x, double y, double z, BetterBlockPos feet, boolean grounded) {
        tick(level, worldDir, x, y, z, feet, grounded, false);
    }

    public static synchronized void tick(Object level, Path worldDir, double x, double y, double z, BetterBlockPos feet, boolean grounded, boolean floating) {
        if (level != world) {
            save();
            world = level;
            load(worldDir == null ? null : worldDir.resolve("routes.json"));
            live = new Layer();
            ASKED_FROM.clear();
        }
        live.step(x, y, z, feet.x, feet.y, feet.z, grounded, floating);
        if (++ticks % SAVE_TICKS == 0) {
            save();
        }
    }

    // a run played before (a #record recording's trace: x y z, and 1 on the ground, 2 in water or on a ladder, 0 in the air, a tick at a time), laid down as trails in
    // this world, moved by dx dy dz (a map rebuilt somewhere else). how many cells it came to
    public static synchronized int learn(List<double[]> ticks, int dx, int dy, int dz) {
        Layer l = new Layer();
        int before = cellCount();
        for (double[] t : ticks) {
            double x = t[0] + dx, y = t[1] + dy, z = t[2] + dz;
            // (the cell the feet are in, the way playerFeet has it: a slab's top or a head is the cell over it)
            l.step(x, y, z, (int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z), t[3] == 1, t[3] == 2);
        }
        l.done();
        dirty = true;
        trailVersion++;
        return cellCount() - before;
    }

    // a trail as it is, cell by cell (tests and tools: a routes.json's trail checked offline)
    public static synchronized void remember(List<BetterBlockPos> cells) {
        Trail t = new Trail();
        for (BetterBlockPos p : cells) {
            t.cells.add(net.minecraft.core.BlockPos.asLong(p.x, p.y, p.z));
        }
        TRAILS.add(t);
        trailVersion++;
    }

    private static int cellCount() {
        int n = 0;
        for (Trail t : TRAILS) {
            n += t.cells.size();
        }
        return n;
    }

    private static boolean near(long c, int x, int y, int z, int reach) {
        return Math.abs(net.minecraft.core.BlockPos.getX(c) - x) <= reach && Math.abs(net.minecraft.core.BlockPos.getY(c) - y) <= 1
                && Math.abs(net.minecraft.core.BlockPos.getZ(c) - z) <= reach;
    }

    private static int indexNear(LongArrayList cells, BetterBlockPos p, int reach) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            long c = cells.getLong(i);
            if (Math.abs(net.minecraft.core.BlockPos.getX(c) - p.x) <= reach && Math.abs(net.minecraft.core.BlockPos.getY(c) - p.y) <= 1
                    && Math.abs(net.minecraft.core.BlockPos.getZ(c) - p.z) <= reach) {
                return i;
            }
        }
        return -1;
    }

    // how a Tungsten jump from src to dest went in the game
    public static synchronized void jumped(BetterBlockPos src, BetterBlockPos dest, boolean landed) {
        long key = jumpKey(src.x, src.y, src.z, dest.x, dest.y, dest.z);
        JUMP_NAMES.putIfAbsent(key, src.x + " " + src.y + " " + src.z + ">" + dest.x + " " + dest.y + " " + dest.z);
        int[] v = JUMPS.computeIfAbsent(key, k -> new int[2]);
        v[landed ? 0 : 1]++;
        dirty = true;
    }

    // what that's worth in extra cost, in ticks: a jump that's never once landed in the game, and missed a few times, is one
    // to go round if there's another way (the sim said yes, the game keeps saying no)
    public static double penalty(int x, int y, int z, int tx, int ty, int tz) {
        if (JUMPS.isEmpty() || !Baritone.settings().useRouteMemory.value) {
            return 0;
        }
        int[] v;
        synchronized (RouteMemory.class) {
            v = JUMPS.get(jumpKey(x, y, z, tx, ty, tz));
        }
        if (v == null || v[1] < 2) {
            return 0;
        }
        if (v[0] == 0 && v[1] >= NEVER_LANDS) {
            return NEVER_PENALTY; // (missed every time, never once landed: whatever the sim says, it's not a way, go round)
        }
        double missRate = v[1] / (double) (v[0] + v[1]);
        return missRate * MISS_PENALTY * Math.min(v[1], 5);
    }

    static final double MISS_PENALTY = 20;
    // missed this many times in the game and never landed: as good as no jump at all
    static final int NEVER_LANDS = 4;
    static final double NEVER_PENALTY = 100_000;

    private static long jumpKey(int x, int y, int z, int tx, int ty, int tz) {
        return net.minecraft.core.BlockPos.asLong(x, y, z) * 31 + net.minecraft.core.BlockPos.asLong(tx, ty, tz);
    }

    // the way from start towards goal along a trail: each of its cells from the one by the start on, with how far along it
    // is (1 for the next cell, and up). to the goal if a trail goes past it after going past the start (the shortest such
    // way); otherwise to the far end of the trail that got furthest on from here (as far as we got last time: on a course
    // that winds, how near the goal the trail came says nothing, the end of it is where we were stuck, and on from there is
    // where to look). null when no trail goes past the start
    public static synchronized Guide guide(BetterBlockPos start, BetterBlockPos goal) {
        if (!Baritone.settings().useRouteMemory.value) {
            return null;
        }
        // asked from the same spot over and over (for the same goal): the way it went doesn't work from here (the trail's
        // next jump keeps failing, or it went somewhere that's a dead end), so the search is on its own
        int asked = ASKED_FROM.addTo(net.minecraft.core.BlockPos.asLong(start.x, start.y, start.z), 1);
        if (asked >= GUIDE_TRIES) {
            if (asked == GUIDE_TRIES) {
                baritone.api.utils.Helper.HELPER.logDebug("The way we went before isn't working from here, finding another");
            }
            return null;
        }
        LongArrayList bestCells = null;
        int bestS = -1, bestEnd = -1;
        boolean bestReaches = false;
        for (Trail t : TRAILS) {
            LongArrayList cells = t.cells;
            int s = indexNear(cells, start, 1);
            if (s < 0) {
                continue;
            }
            int end = -1;
            for (int i = s + 1; i < cells.size(); i++) {
                long c = cells.getLong(i);
                if (Math.abs(net.minecraft.core.BlockPos.getX(c) - goal.x) <= 2 && Math.abs(net.minecraft.core.BlockPos.getY(c) - goal.y) <= 2
                        && Math.abs(net.minecraft.core.BlockPos.getZ(c) - goal.z) <= 2) {
                    end = i;
                    break;
                }
            }
            // (and on as long as it gets nearer still: the goal itself, not the first cell within reach of it)
            while (end >= 0 && end + 1 < cells.size() && dist2(cells.getLong(end + 1), goal) < dist2(cells.getLong(end), goal)) {
                end++;
            }
            boolean reaches = end >= 0;
            if (!reaches) {
                // one that stops short is only any use if it went further than here: not the one being laid down now (it
                // ends where we are), and not one that ends round the corner
                if (t == live.current) {
                    continue;
                }
                end = cells.size() - 1;
                if (dist2(cells.getLong(end), start) < FAR_ENOUGH * FAR_ENOUGH) {
                    continue;
                }
            }
            if (end - s < MIN_GUIDE) {
                continue;
            }
            boolean better = bestCells == null || reaches && !bestReaches
                    || reaches == bestReaches && (reaches ? end - s < bestEnd - bestS : end - s > bestEnd - bestS);
            if (better) {
                bestCells = cells;
                bestS = s;
                bestEnd = end;
                bestReaches = reaches;
            }
        }
        if (bestCells == null) {
            return null;
        }
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        long[] order = new long[bestEnd - bestS + 1];
        for (int i = bestS; i <= bestEnd; i++) {
            index.putIfAbsent(bestCells.getLong(i), i - bestS);
            order[i - bestS] = bestCells.getLong(i);
        }
        return new Guide(index, bestEnd - bestS, bestReaches, order);
    }

    private static long dist2(long c, BetterBlockPos p) {
        long dx = net.minecraft.core.BlockPos.getX(c) - p.x, dy = net.minecraft.core.BlockPos.getY(c) - p.y, dz = net.minecraft.core.BlockPos.getZ(c) - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // a trail has to go on at least this far from the start to be any guide
    static final int MIN_GUIDE = 3;

    // the jumps along the trails (a cell to one of the next few on, either end give or take a block across: a trail cell
    // can be the one over an edge you jumped from): the planner tries harder at these, somebody's made them
    private static volatile it.unimi.dsi.fastutil.longs.LongOpenHashSet PAIRS = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static int pairsFor = -1;

    public static boolean trailJump(int x, int y, int z, int tx, int ty, int tz) {
        if (!Baritone.settings().useRouteMemory.value) {
            return false;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet p = PAIRS;
        int now = trailVersion;
        if (pairsFor != now) {
            synchronized (RouteMemory.class) {
                if (pairsFor != trailVersion) {
                    PAIRS = p = pairs();
                    pairsFor = trailVersion;
                } else {
                    p = PAIRS;
                }
            }
        }
        return p.contains(jumpKey(x, y, z, tx, ty, tz));
    }

    private static volatile int trailVersion;

    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet pairs() {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int[][] near = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Trail t : TRAILS) {
            LongArrayList c = t.cells;
            for (int i = 0; i < c.size(); i++) {
                long a = c.getLong(i);
                int ax = net.minecraft.core.BlockPos.getX(a), ay = net.minecraft.core.BlockPos.getY(a), az = net.minecraft.core.BlockPos.getZ(a);
                for (int j = i + 1; j < Math.min(c.size(), i + 5); j++) {
                    long b = c.getLong(j);
                    int bx = net.minecraft.core.BlockPos.getX(b), by = net.minecraft.core.BlockPos.getY(b), bz = net.minecraft.core.BlockPos.getZ(b);
                    if (Math.abs(bx - ax) <= 1 && Math.abs(bz - az) <= 1 && by == ay) {
                        continue; // (a step)
                    }
                    for (int[] u : near) {
                        for (int[] v : near) {
                            out.add(jumpKey(ax + u[0], ay, az + u[1], bx + v[0], by, bz + v[1]));
                        }
                    }
                }
            }
        }
        return out;
    }

    // how far along the way a cell is (-1: not on it), how long the way is, whether it goes all the way to the goal
    public record Guide(Long2IntOpenHashMap index, int length, boolean reachesGoal, long[] cells) {
        public int along(int x, int y, int z) {
            return index.get(net.minecraft.core.BlockPos.asLong(x, y, z));
        }

        // the same, or the furthest along of the cells next to it (a block either side of where the player went, on the
        // same level): -1 if none is on the way
        public int alongNear(int x, int y, int z) {
            int best = along(x, y, z);
            if (best >= 0) {
                return best;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        best = Math.max(best, along(x + dx, y, z + dz));
                    }
                }
            }
            return best;
        }

        // the k-th cell of the way (BlockPos.asLong), or Long.MIN_VALUE past the end
        public long cell(int k) {
            return k >= 0 && k < cells.length ? cells[k] : Long.MIN_VALUE;
        }
    }

    public static synchronized void forget() {
        TRAILS.clear();
        JUMPS.clear();
        JUMP_NAMES.clear();
        live = new Layer();
        dirty = true;
        trailVersion++;
    }

    public static synchronized int trailCount() {
        return TRAILS.size();
    }

    private static void load(Path f) {
        trailVersion++;
        TRAILS.clear();
        JUMPS.clear();
        JUMP_NAMES.clear();
        file = f;
        dirty = false;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            // (read by hand, not by Gson reflection: the release jar's obfuscation gives a record's fields the same short name)
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (o.has("trails")) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("trails")) {
                    com.google.gson.JsonArray flat = e.getAsJsonArray();
                    Trail t = new Trail();
                    for (int i = 0; i + 2 < flat.size(); i += 3) {
                        t.cells.add(net.minecraft.core.BlockPos.asLong(flat.get(i).getAsInt(), flat.get(i + 1).getAsInt(), flat.get(i + 2).getAsInt()));
                    }
                    TRAILS.add(t);
                }
            }
            if (o.has("jumps")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("jumps").entrySet()) {
                    String[] q = e.getKey().split("[ >]");
                    long key = jumpKey(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]),
                            Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5]));
                    com.google.gson.JsonArray v = e.getValue().getAsJsonArray();
                    JUMPS.put(key, new int[]{v.get(0).getAsInt(), v.get(1).getAsInt()});
                    JUMP_NAMES.put(key, e.getKey());
                }
            }
        } catch (Exception e) {
            Helper.HELPER.logDebug("Couldn't read the routes in " + f + ": " + e);
        }
    }

    // (and the jump keys back out: kept alongside, the hash doesn't come apart)
    private static final Map<Long, String> JUMP_NAMES = new HashMap<>();

    public static synchronized void save() {
        if (!dirty || file == null) {
            return;
        }
        trailVersion++; // (the live trail's grown: the jumps along it, worked out again when next asked)
        dirty = false;
        List<int[]> trails = new ArrayList<>();
        for (Trail t : TRAILS) {
            if (t.cells.size() < MIN_CELLS) {
                continue;
            }
            int[] flat = new int[t.cells.size() * 3];
            for (int i = 0; i < t.cells.size(); i++) {
                long c = t.cells.getLong(i);
                flat[i * 3] = net.minecraft.core.BlockPos.getX(c);
                flat[i * 3 + 1] = net.minecraft.core.BlockPos.getY(c);
                flat[i * 3 + 2] = net.minecraft.core.BlockPos.getZ(c);
            }
            trails.add(flat);
        }
        Map<String, int[]> jumps = new HashMap<>();
        for (Map.Entry<Long, int[]> e : JUMPS.entrySet()) {
            String name = JUMP_NAMES.get(e.getKey());
            if (name != null) {
                jumps.put(name, e.getValue());
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // (written by hand: see load)
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
            for (int[] flat : trails) {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (int v : flat) {
                    a.add(v);
                }
                ts.add(a);
            }
            o.add("trails", ts);
            com.google.gson.JsonObject js = new com.google.gson.JsonObject();
            for (Map.Entry<String, int[]> e : jumps.entrySet()) {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                a.add(e.getValue()[0]);
                a.add(e.getValue()[1]);
                js.add(e.getKey(), a);
            }
            o.add("jumps", js);
            Files.writeString(tmp, GSON.toJson(o));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { // (never the game's problem: a route not saved is a route learned again)
            Helper.HELPER.logDebug("Couldn't save the routes to " + file + ": " + e);
        }
    }
}
