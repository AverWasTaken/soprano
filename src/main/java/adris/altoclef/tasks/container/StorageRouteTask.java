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

package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.commands.DepositCommand;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import baritone.api.utils.BlockRange;
import baritone.api.utils.Dimension;
import baritone.effortless.StorageArea;
import baritone.effortless.EffortlessTrips;
import net.minecraft.world.item.Item;
import java.util.*;

// one user task owns the whole trip, so cancellation cannot accidentally launch the next storage visit
public final class StorageRouteTask extends Task {
    private record Stop(StorageArea area, BlockRange range) {}
    private final List<Stop> stops;
    private final Map<Item,Integer> bill;
    private final boolean depositing;
    private int next;
    private Task child;
    private long childAt;
    private boolean done;
    private String failure;
    private boolean timedOut;
    private Map<Item,Integer> targets;

    public StorageRouteTask(List<StorageArea> areas, Dimension dimension, Map<Item,Integer> bill, boolean depositing) {
        this.bill = Map.copyOf(bill); this.depositing = depositing;
        stops = areas.stream().filter(a -> depositing ? a.role().output() : a.role().input())
                .flatMap(a -> a.ranges(dimension).stream().map(range -> new Stop(a,range))).toList();
    }

    public String failure() { return failure; }
    public List<BlockRange> ranges() { return stops.stream().map(Stop::range).toList(); }

    @Override protected void onStart(AltoClef mod) {
        if (targets == null) {
            targets = new LinkedHashMap<>();
            bill.forEach((item,n) -> targets.put(item, n + mod.getItemStorage().getItemCountInventoryOnly(item)));
        }
    }

    private ItemTarget[] wanted(AltoClef mod, StorageArea area) {
        if (depositing) {
            return filtered(area,DepositCommand.getAllNonEquippedOrToolItemsAsTarget(mod));
        }
        return targets.entrySet().stream().filter(e -> area.accepts(e.getKey()))
                .filter(e -> mod.getItemStorage().getItemCountInventoryOnly(e.getKey()) < e.getValue())
                .map(e -> new ItemTarget(e.getKey(),e.getValue())).toArray(ItemTarget[]::new);
    }

    static ItemTarget[] filtered(StorageArea area, ItemTarget[] targets) {
        return Arrays.stream(targets)
                .map(t -> new ItemTarget(Arrays.stream(t.getMatches()).filter(area::accepts).toArray(Item[]::new),t.getTargetCount()))
                .filter(t -> t.getMatches().length > 0).toArray(ItemTarget[]::new);
    }

    @Override protected Task onTick(AltoClef mod) {
        if (child != null) {
            if (!child.isFinished(mod)) {
                if (System.nanoTime() - childAt < 45_000_000_000L) return child;
                timedOut = true;
            }
            child = null;
            // let Task stop the old child and close its container before choosing targets for the next visit
            return null;
        }
        while (next < stops.size()) {
            Stop stop = stops.get(next++);
            ItemTarget[] items = wanted(mod,stop.area());
            if (items.length == 0) continue;
            child = depositing ? new StoreInStashTask(false,stop.range(),items) : new FetchFromStashTask(stop.range(),items);
            childAt = System.nanoTime();
            return child;
        }
        if (depositing) {
            // Items with no matching storage stay carried; a full result means eligible items remain after all visits.
            failure = stops.stream().anyMatch(s -> wanted(mod,s.area()).length > 0) ? (timedOut ? "some marked storages could not be reached" : EffortlessTrips.FULL) : null;
        } else {
            failure = targets.entrySet().stream().anyMatch(e -> mod.getItemStorage().getItemCountInventoryOnly(e.getKey()) < e.getValue())
                    ? "marked input storages are short of the requested items (or their filters exclude them)" : null;
        }
        done = true;
        return null;
    }

    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {}
    @Override protected boolean isEqual(Task other) { return this == other; }
    @Override protected String toDebugString() { return (depositing ? "Depositing" : "Fetching") + " storage " + next + "/" + stops.size(); }
}
