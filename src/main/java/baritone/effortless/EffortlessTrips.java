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

package baritone.effortless;

import baritone.api.utils.BlockRange;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.List;
import baritone.api.utils.Dimension;
import java.util.function.Consumer;

// the errands a job sends the bot on between builds: empty the bag into the output storage, fetch the bill from the
// input storage, or go and gather it. AltoClef runs them (AltoClefBridge hands one in), so without AltoClef there's
// none and jobs stay with what's in the inventory. done gets null for "went fine", or why it didn't
public interface EffortlessTrips {

    // the reason a deposit came back with: every container was full
    String FULL = "full";

    boolean available();

    void deposit(List<StorageArea> areas, Dimension dimension, Consumer<String> done);

    void fetch(List<StorageArea> areas, Dimension dimension, Map<Item, Integer> bill, Consumer<String> done);

    void gather(Map<Item, Integer> bill, Consumer<String> done);

    // the errand that's out, if any, is called off. its done is told why
    void cancel();
}
