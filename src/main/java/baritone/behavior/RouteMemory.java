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

    private record Stored(List<int[]> trails, Map<String, int[]> jumps) {}

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
    private static Trail current;
    private static Object world;
    private static Path file;
    private static boolean dirty;
    private static long lastCell = Long.MIN_VALUE;
    private static double lastX, lastY, lastZ = Double.NaN;
    private static int ticks, sinceTeleport, cellTicks;
    private static boolean lastGrounded;
    // a teleport within this many ticks of stepping onto a block is that block putting us back (FallFloor.JUST_STEPPED)
    static final int PUT_BACK_TICKS = 20;

    private RouteMemory() {}

    // every tick, with the player, where the cells are (playerFeet: a slab or a head counts as the cell over it), whether
    // they're on the ground, and the world's Baritone folder (null: none, nothing kept)
    public static synchronized void tick(Object level, Path worldDir, double x, double y, double z, BetterBlockPos feet, boolean grounded) {
        if (level != world) {
            save();
            world = level;
            load(worldDir == null ? null : worldDir.resolve("routes.json"));
            current = null;
            lastCell = Long.MIN_VALUE;
            lastZ = Double.NaN;
            sinceTeleport = 0;
            ASKED_FROM.clear();
        }
        sinceTeleport++;
        if (!Double.isNaN(lastZ)) {
            double dx = x - lastX, dz = z - lastZ;
            if (dx * dx + dz * dz > 16 || y - lastY > 2.5) {
                // put back on a checkpoint after a fall (somewhere on the trail: what came after it was the way that
                // didn't work), or somewhere new (a level's start, a /tp): a new trail
                // (a put back comes after falling, off the ground or down below, or the moment we stepped onto something
                // that does it; standing somewhere a while and then a teleport is a restart, or a /tp: the trail so far stays
                // as it is, it's still the way)
                boolean settling = sinceTeleport < SETTLE_TICKS;
                boolean putBack = !lastGrounded || lastY < y - 1 || cellTicks <= PUT_BACK_TICKS;
                sinceTeleport = 0;
                int at = current == null || settling || !putBack ? -1 : indexNear(current.cells, feet, 1);
                if (at >= 0) {
                    current.cells.size(at + 1);
                } else {
                    newTrail();
                }
                lastCell = Long.MIN_VALUE;
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastGrounded = grounded;
        cellTicks++;
        if (grounded) {
            long cell = net.minecraft.core.BlockPos.asLong(feet.x, feet.y, feet.z);
            if (cell != lastCell) {
                lastCell = cell;
                cellTicks = 0;
                if (current == null) {
                    newTrail();
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
        if (++ticks % SAVE_TICKS == 0) {
            save();
        }
    }

    private static void newTrail() {
        if (current != null && current.cells.size() < MIN_CELLS) {
            TRAILS.remove(current);
        }
        current = new Trail();
        TRAILS.add(current);
        while (TRAILS.size() > MAX_TRAILS) {
            TRAILS.remove(0);
        }
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
        double missRate = v[1] / (double) (v[0] + v[1]);
        return missRate * MISS_PENALTY * Math.min(v[1], 5);
    }

    static final double MISS_PENALTY = 20;

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
                if (t == current) {
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
        for (int i = bestS; i <= bestEnd; i++) {
            index.putIfAbsent(bestCells.getLong(i), i - bestS);
        }
        return new Guide(index, bestEnd - bestS, bestReaches);
    }

    private static long dist2(long c, BetterBlockPos p) {
        long dx = net.minecraft.core.BlockPos.getX(c) - p.x, dy = net.minecraft.core.BlockPos.getY(c) - p.y, dz = net.minecraft.core.BlockPos.getZ(c) - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // a trail has to go on at least this far from the start to be any guide
    static final int MIN_GUIDE = 3;

    // how far along the way a cell is (-1: not on it), how long the way is, whether it goes all the way to the goal
    public record Guide(Long2IntOpenHashMap index, int length, boolean reachesGoal) {
        public int along(int x, int y, int z) {
            return index.get(net.minecraft.core.BlockPos.asLong(x, y, z));
        }
    }

    public static synchronized void forget() {
        TRAILS.clear();
        JUMPS.clear();
        JUMP_NAMES.clear();
        current = null;
        dirty = true;
    }

    public static synchronized int trailCount() {
        return TRAILS.size();
    }

    private static void load(Path f) {
        TRAILS.clear();
        JUMPS.clear();
        JUMP_NAMES.clear();
        file = f;
        dirty = false;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            Stored s = GSON.fromJson(Files.readString(f), Stored.class);
            if (s == null) {
                return;
            }
            if (s.trails() != null) {
                for (int[] flat : s.trails()) {
                    Trail t = new Trail();
                    for (int i = 0; i + 2 < flat.length; i += 3) {
                        t.cells.add(net.minecraft.core.BlockPos.asLong(flat[i], flat[i + 1], flat[i + 2]));
                    }
                    TRAILS.add(t);
                }
            }
            if (s.jumps() != null) {
                for (Map.Entry<String, int[]> e : s.jumps().entrySet()) {
                    String[] q = e.getKey().split("[ >]");
                    long key = jumpKey(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]),
                            Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5]));
                    JUMPS.put(key, e.getValue());
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
            Files.writeString(tmp, GSON.toJson(new Stored(trails, jumps)));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Helper.HELPER.logDebug("Couldn't save the routes to " + file + ": " + e);
        }
    }
}
