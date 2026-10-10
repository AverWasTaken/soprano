package adris.altoclef.util.helpers;

import baritone.utils.ToolSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// which stack to break a block with. the choosing is plain numbers (the tests feed it those, item tags are not bound
// outside a world so a real pickaxe reads as "not correct for anything" there), StorageHelper does the looking at slots
// and the caller does the equipping
public final class ToolPick {
    private ToolPick() {
    }

    // one stack as it matters for one block. speed is what ToolSet.calculateSpeedVsBlock says (progress per tick, bigger is faster)
    public record Option(boolean tool, boolean correct, boolean shears, double speed) {
    }

    public static Option optionOf(ItemStack stack, BlockState state) {
        boolean tool = ItemHelper.isTool(stack.getItem());
        boolean shears = stack.getItem() == Items.SHEARS && ItemHelper.areShearsEffective(state.getBlock());
        // only a tool that could matter gets the speed worked out, most of a bag is cobble and food
        boolean worthAsking = (tool || shears) && !stack.isEmpty();
        return new Option(tool, tool && stack.isCorrectToolForDrops(state), shears, worthAsking ? ToolSet.calculateSpeedVsBlock(stack, state) : 0);
    }

    public static List<Option> optionsOf(List<ItemStack> stacks, BlockState state) {
        List<Option> out = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            out.add(optionOf(stack, state));
        }
        return out;
    }

    // "iron_pickaxe", or "bare hand" for nothing. for log lines
    public static String nameOf(ItemStack stack) {
        return stack.isEmpty() ? "bare hand" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    // index of the stack to swing, -1 when nothing in there beats the bare hand (handSpeed).
    // the right tool for the block wins when we have one (shears jump the queue on leaves and friends). when nobody has the
    // right tool the fastest wrong one still beats a bucket: iron on obsidian takes 42 s and a bucket takes 250, and
    // an empty answer here used to mean "keep swinging whatever is in the hand"
    public static int best(List<Option> options, double handSpeed) {
        int best = -1;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (o.tool() && o.correct() && o.speed() > highest) {
                highest = o.speed();
                best = i;
            }
            if (o.shears()) {
                // shears take priority over leaf blocks
                return i;
            }
        }
        return best >= 0 ? best : fastestWrongTool(options, handSpeed);
    }

    // strictly faster than the bare hand or it isn't worth the swap. same idea as baritone's ToolSet.getBestSlot, which
    // never asks whether the tool is "correct" at all
    private static int fastestWrongTool(List<Option> options, double handSpeed) {
        double highest = handSpeed;
        int best = -1;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (o.tool() && o.speed() > highest) {
                highest = o.speed();
                best = i;
            }
        }
        return best;
    }

    // the real thing: these stacks against this block
    public static int best(List<ItemStack> stacks, BlockState state) {
        return best(optionsOf(stacks, state), ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, state));
    }
}
