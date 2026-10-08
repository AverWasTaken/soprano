package adris.altoclef.util.helpers;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
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

    // how much of each stack may burn. the log reserve is one pool over every species, a stack gives up what is left of it.
    // the pool is handed out by item and size, never by where a stack sits: by list order, lifting the acacia onto the cursor
    // to put it in the furnace moved it in the list, the reserve landed on it instead of the oak, the oak became the pick, the
    // acacia went back, and the bot swapped logs on the cursor four times a second with the furnace window open
    public static int[] usable(List<ItemStack> stacks) {
        int[] out = new int[stacks.size()];
        int logs = keepLogs;
        int planks = keepPlanks;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, String>comparing(i -> stacks.get(i).getItem().getDescriptionId())
                .thenComparing(i -> -stacks.get(i).getCount()));
        for (int i : order) {
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
        // a tie (two log species both covering the job) goes to the same item every tick, not to whichever stack the list shows
        // first, same reason as the order in usable
        candidates.sort(Comparator.comparing(i -> stacks.get(i).getItem().getDescriptionId()));
        int best = -1;
        double closestDelta = Double.NEGATIVE_INFINITY;
        for (int i : candidates) {
            double delta = needs - fuelPerItem.applyAsDouble(stacks.get(i).getItem()) * usable[i];
            if (best == -1 || (closestDelta > 0 && delta < closestDelta) || (closestDelta < 0 && delta < 0 && delta > closestDelta)) {
                best = i;
                closestDelta = delta;
            }
        }
        if (best == -1) {
            return null;
        }
        Item item = stacks.get(best).getItem();
        int count = usable[best];
        // wood only goes in as far as the job needs it: a stack of coal in the slot comes back out at the collect and costs nothing,
        // but fourteen logs sitting in a smoker are fourteen logs the wood budget cannot see until then
        double per = fuelPerItem.applyAsDouble(item);
        if (!isCoal(item) && per > 0) {
            count = Math.min(count, Math.max(1, (int) Math.ceil(needs / per)));
        }
        return new Pick(stacks.get(best), count);
    }

    // choose, but null unless the pick alone covers the job. the collect visit uses it: a stalled station gets its fuel only when
    // that is the whole job, otherwise the input comes back out as it always did
    public static Pick chooseCovering(List<ItemStack> stacks, double needs, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        Pick pick = choose(stacks, needs, supported, fuelPerItem);
        if (pick == null || fuelPerItem.applyAsDouble(pick.stack().getItem()) * pick.count() < needs) {
            return null;
        }
        return pick;
    }
}
