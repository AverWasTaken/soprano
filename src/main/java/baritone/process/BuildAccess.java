/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import net.minecraft.core.BlockPos;
import java.util.*;

// a placement gets judged against the walking routes it leaves behind, not only the route to its click
final class BuildAccess {
    interface Terrain {
        boolean open(BlockPos p);
        boolean support(BlockPos p);
        boolean futureSolid(BlockPos p);
    }
    private static final int[][] SIDES = {{1,0},{-1,0},{0,1},{0,-1}};
    private final Map<BlockPos, List<BlockPos>> graph = new HashMap<>();
    private final Map<BlockPos, Set<BlockPos>> after = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Set<BlockPos>> entry) { return size() > 32; }
    };
    private final Set<BlockPos> reachable;
    private final BlockPos exit;

    BuildAccess(BlockPos from, BlockPos entry, int radius, Terrain terrain) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            for (int y = -6; y <= 6; y++) {
                BlockPos p = from.offset(x, y, z);
                if (terrain.open(p) && terrain.open(p.above()) && terrain.support(p.below())) graph.put(p, new ArrayList<>());
            }
        }
        // Step-up clearance includes the departing head; step-down includes the arriving head at the old height.
        for (BlockPos p : graph.keySet()) for (int[] d : SIDES) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos q = p.offset(d[0], dy, d[1]);
                if (graph.containsKey(q) && (dy != 1 || terrain.open(p.above(2)))
                        && (dy != -1 || terrain.open(q.above(2)))) graph.get(p).add(q);
            }
        }
        reachable = flood(from, null);
        // Prefer the entry side. Otherwise use a reachable edge of the local graph which the build won't close.
        if (entry != null && reachable.contains(entry) && stable(entry, terrain)) exit = entry;
        else exit = reachable.stream().filter(p -> stable(p, terrain))
                .filter(p -> Math.abs(p.getX() - from.getX()) == radius || Math.abs(p.getZ() - from.getZ()) == radius)
                .min(Comparator.<BlockPos>comparingDouble(p -> entry == null ? p.distSqr(from) : p.distSqr(entry))
                        .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    private static boolean stable(BlockPos p, Terrain terrain) {
        return !terrain.futureSolid(p) && !terrain.futureSolid(p.above());
    }

    private Set<BlockPos> flood(BlockPos start, BlockPos filled) {
        return flood(start, filled, false);
    }

    private Set<BlockPos> flood(BlockPos start, BlockPos changed, boolean removing) {
        Set<BlockPos> seen = new HashSet<>();
        if (start == null || !graph.containsKey(start) || blocked(start, changed, removing)) return seen;
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        seen.add(start); todo.add(start);
        while (!todo.isEmpty()) {
            BlockPos current = todo.remove();
            for (BlockPos q : graph.get(current)) {
                boolean blockedStep = !removing && changed != null && ((q.getY() > current.getY() && changed.equals(current.above(2)))
                        || (q.getY() < current.getY() && changed.equals(q.above(2))));
                if (!blocked(q, changed, removing) && !blockedStep && seen.add(q)) todo.add(q);
            }
        }
        return seen;
    }

    private static boolean blocked(BlockPos feet, BlockPos filled) {
        return blocked(feet, filled, false);
    }

    private static boolean blocked(BlockPos feet, BlockPos changed, boolean removing) {
        return changed != null && (removing ? feet.below().equals(changed) : feet.equals(changed) || feet.above().equals(changed));
    }

    boolean removable(BlockPos feet, BlockPos target, Collection<? extends BlockPos> pending, double reach) {
        if (!known()) return true; // ladders/falls remain the movement planner's responsibility
        if (!graph.containsKey(target.above())) return true;
        Set<BlockPos> remaining = flood(exit, target, true);
        if (!remaining.contains(feet)) return false;
        for (BlockPos cell : pending) {
            if (!cell.equals(target) && hasStance(reachable, cell, reach) && !hasStance(remaining, cell, reach)) return false;
        }
        return true;
    }

    List<BlockPos> removalStances(BlockPos target, Collection<? extends BlockPos> pending, double reach) {
        if (!known()) return List.of();
        Set<BlockPos> remaining = flood(exit, target, true);
        for (BlockPos cell : pending) {
            if (!cell.equals(target) && hasStance(reachable, cell, reach) && !hasStance(remaining, cell, reach)) return List.of();
        }
        return reachable.stream().filter(p -> remaining.contains(p) && p.distSqr(target) <= reach * reach)
                .sorted(Comparator.comparingDouble(p -> -p.distSqr(target))).toList();
    }

    boolean known() { return exit != null; }

    synchronized boolean safe(BlockPos feet, BlockPos filled) {
        if (!known() || !reachable.contains(feet)) return false;
        if (!graph.containsKey(filled) && !graph.containsKey(filled.below()) && !graph.containsKey(filled.below(2))) return true;
        return after.computeIfAbsent(filled.immutable(), p -> flood(exit, p)).contains(feet);
    }

    synchronized boolean preservesWork(BlockPos filled, Collection<? extends BlockPos> pending, double reach) {
        if (!known()) return false;
        if (!graph.containsKey(filled) && !graph.containsKey(filled.below()) && !graph.containsKey(filled.below(2))) return true;
        Set<BlockPos> remaining = after.computeIfAbsent(filled.immutable(), p -> flood(exit, p));
        long removed = reachable.stream().filter(p -> !blocked(p, filled)).count();
        if (remaining.size() >= removed) return true;
        for (BlockPos target : pending) {
            if (target.equals(filled)) continue;
            // Out-of-window work is unknown, not assumed reachable. It is reconsidered as we travel.
            if (hasStance(reachable, target, reach) && !hasStance(remaining, target, reach)) return false;
        }
        return true;
    }

    private static boolean hasStance(Set<BlockPos> feet, BlockPos target, double reach) {
        int r = (int)Math.ceil(reach);
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            if (x*x + (y+1.12)*(y+1.12) + z*z <= reach*reach && feet.contains(target.offset(x,y,z))) return true;
        }
        return false;
    }

    List<BlockPos> stances(BlockPos target, double reach) {
        if (!known()) return List.of();
        double squared = reach * reach;
        return reachable.stream().filter(p -> {
            double dx = p.getX() - target.getX(), dz = p.getZ() - target.getZ();
            double dy = p.getY() + 1.12 - target.getY();
            return dx * dx + dy * dy + dz * dz <= squared && safe(p, target);
        }).sorted(Comparator.<BlockPos>comparingDouble(p -> -p.distSqr(target))
                .thenComparingInt(BlockPos::getY)).toList();
    }
}
