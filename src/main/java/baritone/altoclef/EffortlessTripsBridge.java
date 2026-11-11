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

package baritone.altoclef;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commands.DepositCommand;
import adris.altoclef.tasks.container.FetchFromStashTask;
import adris.altoclef.tasks.container.StoreInStashTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockRange;
import baritone.effortless.EffortlessTrips;
import baritone.effortless.StorageArea;
import baritone.api.utils.Dimension;
import adris.altoclef.tasks.container.StorageRouteTask;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Effortless Soprano's errands, run as AltoClef user tasks: put the bag in the output storage, take the bill out of
// the input storage, or go and get it. one at a time, the job waits on it
final class EffortlessTripsBridge implements EffortlessTrips {

    private Consumer<String> pending;

    @Override
    public boolean available() {
        try {
            AltoClefBridge.require();
            return true;
        } catch (CommandException e) {
            return false;
        }
    }

    @Override
    public void deposit(List<StorageArea> areas, Dimension dimension, Consumer<String> done) {
        run(mod -> {
            StorageRouteTask task = new StorageRouteTask(areas,dimension,Map.of(),true);
            return new Run(task, task::failure);
        },done);
    }

    @Override
    public void fetch(List<StorageArea> areas, Dimension dimension, Map<Item, Integer> bill, Consumer<String> done) {
        run(mod -> {
            StorageRouteTask task = new StorageRouteTask(areas,dimension,bill,false);
            return new Run(task, task::failure);
        },done);
    }

    @Override
    public void gather(Map<Item, Integer> bill, Consumer<String> done) {
        List<String> cant = new ArrayList<>();
        for (Item item : bill.keySet()) {
            if (!TaskCatalogue.isObtainable(item)) {
                cant.add(item.getDefaultInstance().getHoverName().getString());
            }
        }
        if (!cant.isEmpty()) {
            // AltoClef has no recipe for these, the job builds with what there is and says what's missing
            done.accept("can't gather " + String.join(", ", cant));
            return;
        }
        run(mod -> new Run(TaskCatalogue.getSquashedItemTask(targets(mod, bill)), () -> null), done);
    }

    // the bill is what's short, the targets are what the bag should hold once it's in
    private static ItemTarget[] targets(AltoClef mod, Map<Item, Integer> bill) {
        List<ItemTarget> out = new ArrayList<>();
        bill.forEach((item, n) -> out.add(new ItemTarget(item, mod.getItemStorage().getItemCountInventoryOnly(item) + n)));
        return out.toArray(new ItemTarget[0]);
    }

    private record Run(Task task, Supplier<String> result) {
    }

    private interface Maker {
        Run make(AltoClef mod);
    }

    private void run(Maker maker, Consumer<String> done) {
        AltoClef mod;
        try {
            mod = AltoClefBridge.require();
        } catch (CommandException e) {
            done.accept(e.getMessage());
            return;
        }
        Run r = maker.make(mod);
        pending = done;
        mod.runUserTask(r.task(), (UserTaskChain.Listener) outcome -> {
            if (pending != done) {
                return;
            }
            pending = null;
            done.accept(outcome == UserTaskChain.Outcome.FINISHED ? r.result().get() : "stopped");
        });
    }

    @Override
    public void cancel() {
        Consumer<String> p = pending;
        AltoClef mod = AltoClefBridge.instanceOrNull();
        if (mod != null && p != null) {
            mod.cancelUserTask();
        }
    }
}
