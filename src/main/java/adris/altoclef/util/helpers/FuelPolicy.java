package adris.altoclef.util.helpers;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// which of the fuel in the bag a furnace may eat. the default is everything supported, same as ever. a caller with plans for
// the wood (the gamer, which chops its whole wood budget up front) sets a reserve, and then logs and planks only burn above
// it, and coal or charcoal is picked first when it covers the job. static because the smelt tasks cannot see their owner
public final class FuelPolicy {
    // wood we keep, logs and planks counted separately, shared across species
    private static volatile int keepLogs;
    private static volatile int keepPlanks;
    private static volatile boolean preferCoal;

    private FuelPolicy() {
    }

    public static void set(int logs, int planks, boolean coalFirst) {
        keepLogs = Math.max(0, logs);
        keepPlanks = Math.max(0, planks);
        preferCoal = coalFirst;
    }

    public static void clear() {
        set(0, 0, false);
    }

    private static boolean isCoal(Item item) {
        return item == Items.COAL || item == Items.CHARCOAL;
    }

    private static boolean in(Item item, Item[] group) {
        for (Item i : group) {
            if (i == item) {
                return true;
            }
        }
        return false;
    }

    // how much of each stack may burn, in the order of the list. the log reserve is one pool over every species, a stack
    // gives up what is left of it
    public static int[] usable(List<ItemStack> stacks) {
        int[] out = new int[stacks.size()];
        int logs = keepLogs;
        int planks = keepPlanks;
        for (int i = 0; i < out.length; i++) {
            ItemStack stack = stacks.get(i);
            int count = stack.getCount();
            if (in(stack.getItem(), ItemHelper.LOG)) {
                int kept = Math.min(count, logs);
                logs -= kept;
                count -= kept;
            } else if (in(stack.getItem(), ItemHelper.PLANKS)) {
                int kept = Math.min(count, planks);
                planks -= kept;
                count -= kept;
            }
            out[i] = count;
        }
        return out;
    }

    // fuel units in the bag that may burn, what calculateInventoryFuelCount is
    public static double usableFuel(List<ItemStack> stacks, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        int[] usable = usable(stacks);
        double total = 0;
        for (int i = 0; i < usable.length; i++) {
            Item item = stacks.get(i).getItem();
            if (supported.test(item)) {
                total += fuelPerItem.applyAsDouble(item) * usable[i];
            }
        }
        return total;
    }

    public record Pick(ItemStack stack, int count) {
    }

    // the stack to put in the fuel slot for a job that needs `needs` smelts of fuel, null when nothing may burn. the choice
    // among the candidates is the one the smelt tasks always had (closest to the need without going under, else the biggest
    // that falls short); the only new things are the reserve and "coal first if coal alone covers it"
    public static Pick choose(List<ItemStack> stacks, double needs, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        int[] usable = usable(stacks);
        boolean coalOnly = false;
        if (preferCoal) {
            double coal = 0;
            for (int i = 0; i < usable.length; i++) {
                Item item = stacks.get(i).getItem();
                if (isCoal(item) && supported.test(item)) {
                    coal += fuelPerItem.applyAsDouble(item) * usable[i];
                }
            }
            coalOnly = coal >= needs;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < usable.length; i++) {
            Item item = stacks.get(i).getItem();
            if (usable[i] > 0 && supported.test(item) && (!coalOnly || isCoal(item))) {
                candidates.add(i);
            }
        }
        int best = -1;
        double closestDelta = Double.NEGATIVE_INFINITY;
        for (int i : candidates) {
            double delta = needs - fuelPerItem.applyAsDouble(stacks.get(i).getItem()) * usable[i];
            if (best == -1 || (closestDelta > 0 && delta < closestDelta) || (closestDelta < 0 && delta < 0 && delta > closestDelta)) {
                best = i;
                closestDelta = delta;
            }
        }
        return best == -1 ? null : new Pick(stacks.get(best), usable[best]);
    }
}
