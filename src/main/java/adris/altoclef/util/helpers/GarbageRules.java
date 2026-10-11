package adris.altoclef.util.helpers;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// who gets thrown when the bag is full, as plain numbers so it can be tested without a game
public final class GarbageRules {

    // one throwable stack. keepUnits is what it counts for toward the food floor (0 for anything that is not plain food),
    // ownUnits is what it gives as it is, so raw meat goes before the cooked stack worth the same. spared is food we would
    // rather not throw (plain food, gapples, chorus fruit) even when it does not count toward the floor: rotten flesh is not
    public record Cand(boolean tool, int toolUses, int damage, int keepUnits, int ownUnits, int count, boolean spared) {
    }

    private GarbageRules() {
    }

    public static Cand cand(ItemStack stack) {
        Item item = stack.getItem();
        boolean tool = ItemHelper.isTool(item);
        FoodHelper.Kind kind = FoodHelper.kindOf(item);
        boolean spared = kind == FoodHelper.Kind.NORMAL || kind == FoodHelper.Kind.RESERVED || kind == FoodHelper.Kind.CHORUS_FRUIT;
        return new Cand(tool, tool ? ItemHelper.getToolDurability(item) : 0, stack.getDamageValue(), keepUnits(stack),
                FoodHelper.ownNutrition(item) * stack.getCount(), stack.getCount(), spared);
    }

    // same measure as GamerFacts.foodUnits: normal food only, raw meat at its cooked value, nutrition times count. the old sum
    // here added nutrition once per stack, so one stack of steak looked like 8 and a bag of 64 looked the same as a bag of 1
    public static int keepUnits(ItemStack stack) {
        if (stack.isEmpty() || FoodHelper.kindOf(stack.getItem()) != FoodHelper.Kind.NORMAL) {
            return 0;
        }
        return FoodHelper.plannedNutrition(stack.getItem()) * stack.getCount();
    }

    // food is only garbage when what is left without it still clears the floor. so a bag under the floor throws no food at all,
    // and over it only whole stacks that are pure surplus. a floor of 0 turns this off
    public static boolean keepsFood(int bagUnits, int stackUnits, int floor) {
        return stackUnits > 0 && bagUnits - stackUnits < floor;
    }

    // index of the stack to throw, -1 for none. non-food first (junk, then tools, worst first), food only after every one of
    // those and only when it is surplus, lowest units first
    public static int pick(List<Cand> cands, int bagUnits, int floor) {
        int best = -1;
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            if (keepsFood(bagUnits, c.keepUnits(), floor)) {
                continue;
            }
            if (best < 0 || compare(c, cands.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    private static int rank(Cand c) {
        if (c.spared()) {
            return 2;
        }
        return c.tool() ? 1 : 0;
    }

    private static int compare(Cand l, Cand r) {
        int byRank = Integer.compare(rank(l), rank(r));
        if (byRank != 0) {
            return byRank;
        }
        if (l.tool()) {
            // material first, then durability
            if (l.toolUses() != r.toolUses()) {
                return l.toolUses() - r.toolUses();
            }
            return l.damage() - r.damage();
        }
        if (l.spared()) {
            // the floor measure first, so 8 raw beef (64 planned) does not go before 4 cooked (32). what it gives as it is only
            // breaks a tie, which is where raw goes before the cooked stack worth the same. a gapple has no floor units, its own
            // hunger stands in so it is not thrown ahead of every steak
            int lv = Math.max(l.keepUnits(), l.ownUnits());
            int rv = Math.max(r.keepUnits(), r.ownUnits());
            if (lv != rv) {
                return lv - rv;
            }
            return l.ownUnits() - r.ownUnits();
        }
        // just discard the one with the smallest quantity, but this doesn't really matter
        return l.count() - r.count();
    }
}
