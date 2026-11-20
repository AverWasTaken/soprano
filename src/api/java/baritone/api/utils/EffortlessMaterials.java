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
 * Where an Effortless Soprano place job takes its blocks from.
 */
public enum EffortlessMaterials {
    /**
     * Only what the player is carrying. The job pauses and names what's short when it runs out.
     */
    INVENTORY,
    /**
     * The marked input storage: the job's blocks are taken out of its containers before building, and again when it
     * runs short.
     */
    STORAGE,
    /**
     * AltoClef mines, crafts and smelts whatever is missing, then the job carries on.
     */
    GATHER
}
