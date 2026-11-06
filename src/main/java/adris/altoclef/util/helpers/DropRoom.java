package adris.altoclef.util.helpers;

import java.util.ArrayList;
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

    private static final List<Item> LATE = List.of(Items.RAW_GOLD, Items.FURNACE, Items.SHEARS, Items.DIAMOND, Items.COAL);

    // where an EVICT's slot comes from: the ordinary garbage pick, the armor last resort, or the core victim pick
    public enum Source { NONE, GARBAGE, WEAR, VICTIM }

    // slot is an index into the bag list, -1 for the armor last resort (StorageHelper finds that one itself)
    public record Decision(Kind kind, Source source, int slot) {
        public static final Decision FIT = new Decision(Kind.FIT, Source.NONE, -1);
        public static final Decision NO = new Decision(Kind.NONE, Source.NONE, -1);
    }

    // one bag stack for the victim pick. keep is the world's never (worn or wearable armor, enchanted or named, the
    // important list, food under the floor). build = a block we bridge with. gate = END_PREP's block count sees it
    // (AltoSettings.isBuildBlock and a throwaway, same as the facts), the shed won't throw below that gate
    public record Stack(ItemStack stack, int tier, boolean keep, boolean build, boolean gate) {
        // no word on the gate means a build stack counts. the safe guess, a shed that thinks it may throw the gate's blocks
        // sends END_PREP back to dig them up
        public Stack(ItemStack stack, int tier, boolean keep, boolean build) {
            this(stack, tier, keep, build, build);
        }
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
    // hold better, wool past the bed's three), then one build stack when we hold two or more, then any
    // plain tier-3 stack. the smallest stack of the winning group goes, it's the least to lose
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
            } else if (s.tier() == ItemValue.REST) {
                // a lone stack of sticks or string is still worth less than a pearl. the stuff whose last one matters
                // (crafting table, flint and steel, potions) sits in the never list or the kit tier, not here
                plain = smaller(bag, plain, i);
            }
        }
        if (surplus >= 0) return surplus;
        return build >= 0 ? build : plain;
    }

    // the bed errand's full bag and nobody else's: a bed in the bag is worth more than a pearl's victim list will give up. the
    // biggest build stack the gate can spare, then the garbage pick, then whatever a core drop could push out, then the late list.
    // -1 = nothing may go. the errand's own stuff (logs, planks, wool, beds) never goes: the garbage pick happily calls a single
    // log junk, and that log is exactly what the bed craft is short of. blockFloor = the gate's minBuildBlocks. a pick that's
    // somebody's keep doesn't count, the throw would refuse a reserved bed and we'd sit there asking again
    public static int shed(int garbagePick, List<Stack> bag, int blockFloor) {
        List<Stack> held = errandHolds(bag, blockFloor);
        int big = biggestBuild(held);
        if (big >= 0) return big;
        if (garbagePick >= 0 && !held.get(garbagePick).keep()) return garbagePick;
        int v = victim(held);
        return v >= 0 ? v : late(held);
    }

    // the bag with the errand's stuff and the gate's last blocks marked keep, so victim and late don't need to know about beds
    static List<Stack> errandHolds(List<Stack> bag, int blockFloor) {
        int gateBlocks = 0;
        for (Stack s : bag) {
            if (s.gate() && !s.stack().isEmpty()) gateBlocks += s.stack().getCount();
        }
        List<Stack> out = new ArrayList<>(bag.size());
        for (Stack s : bag) {
            ItemStack st = s.stack();
            boolean hold = !st.isEmpty() && (forTheBeds(st.getItem()) || s.gate() && gateBlocks - st.getCount() < blockFloor);
            out.add(hold && !s.keep() ? new Stack(st, s.tier(), true, s.build(), s.gate()) : s);
        }
        return out;
    }

    // a whole stack of cobble frees the same slot a lone leaf does and we'd rather keep the leaf's worth. biggest first, the bag
    // order breaks ties
    private static int biggestBuild(List<Stack> bag) {
        Map<Item, Integer> stacks = new HashMap<>();
        for (Stack s : bag) {
            if (!s.stack().isEmpty()) stacks.merge(s.stack().getItem(), 1, Integer::sum);
        }
        int best = -1;
        for (int i = 0; i < bag.size(); i++) {
            Stack s = bag.get(i);
            if (s.stack().isEmpty() || !s.build() || never(s, stacks)) continue;
            if (best < 0 || s.stack().getCount() > bag.get(best).stack().getCount()) best = i;
        }
        return best;
    }

    // what the bed errand turns into beds, or is
    public static boolean forTheBeds(Item item) {
        return isBed(item) || isWool(item) || in(ItemHelper.LOG, item) || in(ItemHelper.PLANKS, item);
    }

    // kit nothing else lets go of, cheapest to lose first. the bucket isn't here, the gate counts it. a furnace only goes when
    // the smoker can do its job, and the raw gold was only ever piglin money. diamonds sit on the important list out of the box,
    // so never holds on to them until somebody takes them off it
    static int late(List<Stack> bag) {
        Map<Item, Integer> stacks = new HashMap<>();
        for (Stack s : bag) {
            if (!s.stack().isEmpty()) stacks.merge(s.stack().getItem(), 1, Integer::sum);
        }
        for (Item item : LATE) {
            if (item == Items.FURNACE && !stacks.containsKey(Items.SMOKER)) continue;
            int best = -1;
            for (int i = 0; i < bag.size(); i++) {
                Stack s = bag.get(i);
                if (!s.stack().isEmpty() && s.stack().getItem() == item && !never(s, stacks)) best = smaller(bag, best, i);
            }
            if (best >= 0) return best;
        }
        return -1;
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

    // per bag stack: is it spoken for by the End. the first reserveBeds beds, and while that still leaves us short, the wool that makes
    // up the difference (whole threes of a colour only, scraps are scraps). a bed always looks like a duplicate, unstackable and all,
    // so without this a pearl pickup happily throws one
    public static boolean[] reserved(List<ItemStack> bag, int reserveBeds) {
        boolean[] out = new boolean[bag.size()];
        Map<Item, Integer> usableWool = new HashMap<>();
        int beds = 0;
        for (ItemStack st : bag) {
            if (isBed(st.getItem())) beds += st.getCount();
            if (isWool(st.getItem())) usableWool.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        usableWool.replaceAll((wool, total) -> total / 3 * 3);
        int bedsLeft = reserveBeds;
        int woolLeft = 3 * Math.max(0, reserveBeds - beds);
        for (int i = 0; i < out.length; i++) {
            ItemStack st = bag.get(i);
            if (st.isEmpty()) continue;
            if (isBed(st.getItem()) && bedsLeft > 0) {
                out[i] = true;
                bedsLeft -= st.getCount();
            } else if (isWool(st.getItem()) && woolLeft > 0 && usableWool.get(st.getItem()) > 0) {
                out[i] = true;
                // only what can become a bed pays off the debt, the scraps of 5 red don't stand in for 3 white
                int pays = Math.min(st.getCount(), usableWool.get(st.getItem()));
                usableWool.put(st.getItem(), usableWool.get(st.getItem()) - pays);
                woolLeft -= pays;
            }
        }
        return out;
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

    public static boolean isBed(Item item) {
        return in(ItemHelper.BED, item);
    }

    private static boolean isWool(Item item) {
        return in(ItemHelper.WOOL, item);
    }

    private static boolean in(Item[] items, Item item) {
        for (Item i : items) {
            if (i == item) return true;
        }
        return false;
    }

    private static int smaller(List<Stack> bag, int best, int i) {
        return best < 0 || bag.get(i).stack().getCount() < bag.get(best).stack().getCount() ? i : best;
    }
}
