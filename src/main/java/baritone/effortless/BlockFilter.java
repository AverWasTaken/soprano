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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// which blocks a job may touch. "only" empty means anything. entries are block ids or #tags, kept as text so the
// queue can save them and the gui can show them as typed. the hand and offhand get turned into ids when the job is made
public final class BlockFilter {

    public static final BlockFilter ALL = new BlockFilter(List.of(), List.of(), false, true);

    private final List<String> only;
    private final List<String> except;
    private final boolean skipContainers;
    private final boolean keepFluids;
    private final List<Entry> onlyCompiled;
    private final List<Entry> exceptCompiled;

    public BlockFilter(List<String> only, List<String> except, boolean skipContainers, boolean keepFluids) {
        this.only = List.copyOf(only);
        this.except = List.copyOf(except);
        this.skipContainers = skipContainers;
        this.keepFluids = keepFluids;
        this.onlyCompiled = compile(this.only);
        this.exceptCompiled = compile(this.except);
    }

    public List<String> only() {
        return only;
    }

    public List<String> except() {
        return except;
    }

    public boolean skipContainers() {
        return skipContainers;
    }

    public boolean keepFluids() {
        return keepFluids;
    }

    public BlockFilter withOnly(List<String> ids) {
        return new BlockFilter(ids, except, skipContainers, keepFluids);
    }

    public BlockFilter withExcept(List<String> ids) {
        return new BlockFilter(only, ids, skipContainers, keepFluids);
    }

    public BlockFilter withSkipContainers(boolean on) {
        return new BlockFilter(only, except, on, keepFluids);
    }

    public BlockFilter withKeepFluids(boolean on) {
        return new BlockFilter(only, except, skipContainers, on);
    }

    // the block itself passes, with air, fluids (when kept) and containers (when skipped) already left out. air never
    // matches: there is nothing to break and "only stone" shouldn't fill every hole
    public boolean matches(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (keepFluids && state.getBlock() instanceof LiquidBlock) {
            return false;
        }
        if (skipContainers && isContainer(state)) {
            return false;
        }
        if (!onlyCompiled.isEmpty() && onlyCompiled.stream().noneMatch(e -> e.test(state))) {
            return false;
        }
        return exceptCompiled.stream().noneMatch(e -> e.test(state));
    }

    // anything with a block entity behind it: chests, barrels, furnaces, hoppers, shulkers. close enough for "don't
    // dig up my storage", and it needs no level to answer
    public static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof BaseEntityBlock;
    }

    // what a typed list entry would match, or why it doesn't make sense. null when it's fine
    public static String problem(String id) {
        try {
            Entry e = parse(id);
            return e == null ? "\"" + id + "\" isn't a block or a #tag" : null;
        } catch (RuntimeException e) {
            return "\"" + id + "\" isn't a block or a #tag";
        }
    }

    public static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add(only.isEmpty() ? "all" : "only " + String.join(", ", only));
        if (!except.isEmpty()) {
            parts.add("except " + String.join(", ", except));
        }
        if (skipContainers) {
            parts.add("skip containers");
        }
        if (!keepFluids) {
            parts.add("fluids too");
        }
        return String.join(", ", parts);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockFilter f && f.only.equals(only) && f.except.equals(except)
                && f.skipContainers == skipContainers && f.keepFluids == keepFluids;
    }

    @Override
    public int hashCode() {
        return only.hashCode() * 31 + except.hashCode() * 7 + (skipContainers ? 2 : 0) + (keepFluids ? 1 : 0);
    }

    // ----- entries

    private interface Entry {
        boolean test(BlockState state);
    }

    private static List<Entry> compile(List<String> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Entry e = parse(id);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static Entry parse(String raw) {
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(id.substring(1));
            if (tagId == null) {
                return null;
            }
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            return state -> state.is(tag);
        }
        Identifier blockId = Identifier.tryParse(id);
        if (blockId == null) {
            return null;
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty()) {
            return null;
        }
        Block b = block.get();
        return state -> state.getBlock() == b;
    }
}
