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

package baritone.behavior.trip;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// which items a trip gather goes for and which blocks drop them. the items come from acceptableThrowawayItems as the
// user has it: collecting tuff because altoclef likes it is no use once the task is over and plain baritone won't place it
public final class GatherSources {

    // what a stone pickaxe is made of. any of these will do
    public static final List<Block> STONE_TOOL_BLOCKS = List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE);

    private static List<Block> logs;

    private GatherSources() {
    }

    // stone only drops itself with silk touch, sand and gravel fall on whoever is bridging with them
    public static List<Item> items(List<Item> throwaway) {
        List<Item> out = new ArrayList<>();
        for (Item item : throwaway) {
            if (item instanceof BlockItem && item != Items.STONE && item != Items.SAND && item != Items.GRAVEL && !out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    // the item's own block, plus the common blocks that break into it. nobody finds cobblestone lying around, they find stone
    public static List<Block> sources(List<Item> items) {
        Set<Block> out = new LinkedHashSet<>();
        for (Item item : items) {
            if (item instanceof BlockItem blockItem) {
                out.add(blockItem.getBlock());
            }
            if (item == Items.COBBLESTONE) {
                out.add(Blocks.STONE);
            } else if (item == Items.COBBLED_DEEPSLATE) {
                out.add(Blocks.DEEPSLATE);
            } else if (item == Items.DIRT) {
                out.add(Blocks.GRASS_BLOCK);
                out.add(Blocks.PODZOL);
                out.add(Blocks.MYCELIUM);
                out.add(Blocks.DIRT_PATH);
                out.add(Blocks.FARMLAND);
            }
        }
        return new ArrayList<>(out);
    }

    // drops nothing without the right tool. for the throwaway blocks that's always a pickaxe
    public static boolean needsPickaxe(Block block) {
        return block.defaultBlockState().requiresCorrectToolForDrops();
    }

    // logs and stems, for a wooden pickaxe and a crafting table on the way to a stone one
    public static synchronized List<Block> logs() {
        if (logs == null) {
            List<Block> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block.defaultBlockState().is(BlockTags.LOGS)) {
                    found.add(block);
                }
            }
            logs = List.copyOf(found);
        }
        return logs;
    }
}
