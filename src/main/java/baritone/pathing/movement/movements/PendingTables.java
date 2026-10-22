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

package baritone.pathing.movement.movements;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

// the neo, momentum and climb tables answer A* with "don't know yet" the first time a shape comes up and work it out in the
// background (a stall in the middle of a search would eat the whole search). a search that got told that and then found
// nothing may well find something a second later: this is how whoever asked for the path knows to wait and try again
// instead of giving up
public final class PendingTables {

    private static final Map<Object, BooleanSupplier> AWAITED = new ConcurrentHashMap<>();
    private static volatile long misses;

    private PendingTables() {}

    // a lookup came back "don't know yet" for id, and resolved says when it does know
    static void missed(Object id, BooleanSupplier resolved) {
        AWAITED.putIfAbsent(id, resolved);
        misses++;
    }

    // goes up every miss: compare before and after a search to see whether it was told "don't know yet"
    public static long misses() {
        return misses;
    }

    // anything a search was told "don't know yet" about still being worked out
    public static boolean waiting() {
        AWAITED.values().removeIf(BooleanSupplier::getAsBoolean);
        return !AWAITED.isEmpty();
    }
}
