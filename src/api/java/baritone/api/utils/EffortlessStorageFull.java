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
package baritone.api.utils;

/**
 * What an Effortless Soprano break job does when it goes to empty its inventory and the output storage is full.
 */
public enum EffortlessStorageFull {
    /**
     * Keep the inventory, go back to work, and try the storage again later, in case something empties it meanwhile.
     */
    KEEP_WORKING,
    /**
     * Stay at the storage and stop the job and the queue.
     */
    STOP
}
