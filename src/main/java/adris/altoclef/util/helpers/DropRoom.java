package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// "a drop and a full bag": does it fit, what goes to make room, or is it a no. one answer for the death pile's filter, the
// pickup and the throw, so the pile never walks to a drop the pickup then refuses. only the core items (ItemValue.CORE) may
// push a stack we would otherwise keep out, and only one that is worth less. kit never evicts anything: the stack it threw
// would be kit too, and we would pick it straight back up
public final class DropRoom {

    public enum Kind { FIT, EVICT, NONE }

    // where an EVICT's slot comes from: the ordinary garbage pick, the armor last resort, or the core victim pick
    public enum Source { NONE, GARBAGE, WEAR, VICTIM }

    // slot is an index into the bag list, -1 for the armor last resort (StorageHelper finds that one itself)
    public record Decision(Kind kind, Source source, int slot) {
        public static final Decision FIT = new Decision(Kind.FIT, Source.NONE, -1);
        public static final Decision NO = new Decision(Kind.NONE, Source.NONE, -1);
    }

    // one bag stack for the victim pick. keep is the world's never (worn or wearable armor, enchanted or named, the
    // important list, food under the floor). build = a block we bridge with
    public record Stack(ItemStack stack, int tier, boolean keep, boolean build) {
    }

    private DropRoom() {
    }

    // the order: it fits; the garbage pick (unless the drop would be the next garbage, that is a loop); armor we would wear
    // takes the last resort like it always did; a core drop picks a victim
    public static Decision decide(boolean fits, int garbagePick, boolean dropIsNextGarbage, boolean wearRoom, boolean dropCore,
                                  List<Stack> bag) {
        if (fits) {
            return Decision.FIT;
        }
        if (garbagePick >= 0 && !dropIsNextGarbage) {
            return new Decision(Kind.EVICT, Source.GARBAGE, garbagePick);
        }
        if (wearRoom) {
            return new Decision(Kind.EVICT, Source.WEAR, -1);
        }
        if (dropCore) {
            int v = victim(bag);
            if (v >= 0) {
                return new Decision(Kind.EVICT, Source.VICTIM, v);
            }
        }
        return Decision.NO;
    }

    // who a core drop pushes out, -1 for nobody. first a surplus (a second stack of something, a worse tool of a kind we
    // hold better, wool past the bed's three), then one build stack when we hold two or more, then a
    // plain stack that is neither. the smallest stack of the winning group goes, it's the least to lose
    public static int victim(List<Stack> bag) {
        Map<Item, Integer> stacks = new HashMap<>();
        Map<String, Integer> bestTool = new HashMap<>();
        int wool = 0;
        int builds = 0;
        for (Stack s : bag) {
            ItemStack st = s.stack();
            if (st.isEmpty()) continue;
            stacks.merge(st.getItem(), 1, Integer::sum);
            String kind = ItemHelper.toolKind(st.getItem());
            if (kind != null) {
                bestTool.merge(kind, ItemHelper.getToolDurability(st.getItem()), Math::max);
            }
            if (isWool(st.getItem())) wool += st.getCount();
        }
        for (Stack s : bag) {
            if (!s.stack().isEmpty() && s.build() && !never(s, stacks)) builds++;
        }
        int surplus = -1;
        int build = -1;
        int plain = -1;
        for (int i = 0; i < bag.size(); i++) {
            Stack s = bag.get(i);
            if (s.stack().isEmpty() || never(s, stacks)) continue;
            if (surplus(s, stacks, bestTool, wool)) {
                surplus = smaller(bag, surplus, i);
            } else if (s.build()) {
                if (builds >= 2) build = smaller(bag, build, i);
            } else if (s.tier() == ItemValue.REST && stacks.get(s.stack().getItem()) >= 2) {
                // never the last of its item. a second stack already reads as surplus above, so today this one never wins
                plain = smaller(bag, plain, i);
            }
        }
        if (surplus >= 0) return surplus;
        return build >= 0 ? build : plain;
    }

    private static boolean surplus(Stack s, Map<Item, Integer> stacks, Map<String, Integer> bestTool, int wool) {
        Item item = s.stack().getItem();
        // a bed is three, whatever is past that is fluff. asked before the duplicate rule, two half stacks can still be one bed
        if (isWool(item)) return wool - s.stack().getCount() >= 3;
        // nuggets are piglin money, a second stack is not spare
        if (item == Items.GOLD_NUGGET) return false;
        String kind = ItemHelper.toolKind(item);
        if (kind != null && ItemHelper.getToolDurability(item) < bestTool.get(kind)) return true;
        // a second stack of anything, the spare table / furnace / smoker included
        return stacks.get(item) >= 2;
    }

    // the stacks nothing pushes out
    static boolean never(Stack s, Map<Item, Integer> stacks) {
        if (s.keep() || s.tier() == ItemValue.CORE) return true;
        ItemStack st = s.stack();
        Item item = st.getItem();
        if (item == Items.WATER_BUCKET || item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) return true;
        if (FoodHelper.kindOf(item) == FoodHelper.Kind.RESERVED) return true;
        boolean last = stacks.getOrDefault(item, 0) <= 1;
        if (last && (item == Items.CRAFTING_TABLE || item == Items.FLINT_AND_STEEL)) return true;
        return "pickaxe".equals(ItemHelper.toolKind(item)) && lastPickaxe(stacks);
    }

    private static boolean lastPickaxe(Map<Item, Integer> stacks) {
        int picks = 0;
        for (Map.Entry<Item, Integer> e : stacks.entrySet()) {
            if ("pickaxe".equals(ItemHelper.toolKind(e.getKey()))) picks += e.getValue();
        }
        return picks <= 1;
    }

    private static boolean isWool(Item item) {
        for (Item w : ItemHelper.WOOL) {
            if (w == item) return true;
        }
        return false;
    }

    private static int smaller(List<Stack> bag, int best, int i) {
        return best < 0 || bag.get(i).stack().getCount() < bag.get(best).stack().getCount() ? i : best;
    }
}
