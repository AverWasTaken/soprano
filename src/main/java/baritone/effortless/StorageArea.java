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
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

import java.util.Locale;

// a box of chests, barrels and shulkers marked with an empty hand. output takes what break jobs collect, input is where
// place jobs with the storage source take their blocks from, both is both
public record StorageArea(BlockPos a, BlockPos b, Role role) {

    public enum Role {
        BOTH, OUTPUT, INPUT;

        public boolean output() {
            return this != INPUT;
        }

        public boolean input() {
            return this != OUTPUT;
        }

        public static Role byId(String id) {
            try {
                return valueOf(id.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public StorageArea {
        a = a.immutable();
        b = b.immutable();
    }

    public BlockRange range(Dimension dimension) {
        return new BlockRange(a, b, dimension);
    }

    public BlockPos center() {
        return new BlockPos((a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2, (a.getZ() + b.getZ()) / 2);
    }

    public StorageArea withRole(Role r) {
        return new StorageArea(a, b, r);
    }

    public String describe() {
        return role.name().toLowerCase(Locale.ROOT) + " " + a.toShortString() + " to " + b.toShortString();
    }
}
