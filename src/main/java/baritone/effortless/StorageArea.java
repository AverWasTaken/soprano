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

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

// a box of chests, barrels and shulkers marked with an empty hand. output takes what break jobs collect, input is where
// place jobs with the storage source take their blocks from, both is both
public record StorageArea(BlockPos a, BlockPos b, Role role, String id, String name,
                          Match match, List<String> items, List<Box> extra) {
    public enum Match { ALL, ONLY, EXCEPT }
    public record Box(BlockPos a, BlockPos b) {
        public Box { a = a.immutable(); b = b.immutable(); }
    }

    public StorageArea(BlockPos a, BlockPos b, Role role) {
        this(a, b, role, UUID.randomUUID().toString(), "Storage", Match.ALL, List.of(), List.of());
    }

    public List<Box> boxes() {
        List<Box> out = new ArrayList<>(); out.add(new Box(a,b)); out.addAll(extra); return List.copyOf(out);
    }

    public List<BlockRange> ranges(Dimension dimension) {
        return boxes().stream().map(p -> new BlockRange(p.a(), p.b(), dimension)).toList();
    }

    public boolean accepts(Item item) {
        if (match == Match.ALL) return true;
        boolean found = items.stream().anyMatch(raw -> {
            if (raw.startsWith("#")) return item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, Identifier.parse(raw.substring(1))));
            return BuiltInRegistries.ITEM.getKey(item).equals(Identifier.parse(raw));
        });
        return match == Match.ONLY ? found : !found;
    }

    public static List<String> parseItems(String text) {
        if (text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : text.trim().split("[,\s]+")) {
            boolean tag = raw.startsWith("#");
            Identifier key;
            try { key = Identifier.parse(tag ? raw.substring(1) : raw); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid item id: " + raw); }
            if (!tag && (!BuiltInRegistries.ITEM.containsKey(key) || BuiltInRegistries.ITEM.getValue(key) == net.minecraft.world.item.Items.AIR))
                throw new IllegalArgumentException("Unknown item: " + raw);
            out.add((tag ? "#" : "") + key);
        }
        return List.copyOf(out);
    }

    public StorageArea edited(String name, Role role, Match match, String text, BlockPos a, BlockPos b) {
        List<String> ids = match == Match.ALL ? List.of() : parseItems(text);
        if (match != Match.ALL && ids.isEmpty()) throw new IllegalArgumentException("Choose at least one item, or select All items");
        return new StorageArea(a,b,role,id,name,match,ids,extra);
    }

    public StorageArea merge(StorageArea other) {
        if (role != other.role || match != other.match || !new HashSet<>(items).equals(new HashSet<>(other.items)))
            throw new IllegalArgumentException("Match the role and item rules before merging");
        LinkedHashSet<Box> parts = new LinkedHashSet<>(boxes()); parts.addAll(other.boxes()); parts.remove(new Box(a,b));
        return new StorageArea(a,b,role,id,name,match,items,List.copyOf(parts));
    }


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
        Objects.requireNonNull(role); Objects.requireNonNull(match);
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = name == null || name.isBlank() ? "Storage" : name.trim();
        items = List.copyOf(items); extra = List.copyOf(extra);
    }

    public BlockRange range(Dimension dimension) {
        return new BlockRange(a, b, dimension);
    }

    public BlockPos center() {
        return new BlockPos((a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2, (a.getZ() + b.getZ()) / 2);
    }

    public StorageArea withRole(Role r) {
        return new StorageArea(a, b, r, id, name, match, items, extra);
    }

    public String describe() {
        return name + " · " + role.name().toLowerCase(Locale.ROOT) + " " + a.toShortString() + " to " + b.toShortString() + (extra.isEmpty() ? "" : " + " + extra.size() + " areas");
    }
}
