package adris.altoclef.util.helpers;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

// which melee weapon to hold. the kill tasks wait for a full cooldown before every swing anyway, so what matters is damage
// per swing and not damage per second, and an axe hits harder per swing than a sword of the same tier (wood 7 vs 4,
// iron 9 vs 6). the price is that it swings slower, which only decides it when two weapons hit for the same
//
// an axe also knocks a raised shield down for a few seconds, which is a bonus we get to keep with no code at all
public final class WeaponPick {

    // same idea as KitPlanner.wornOut: past this much of its durability a tool is as good as gone and we would rather swing
    // something healthy. a different number would be fine, it is just the same one so the bot has one idea of worn
    private static final double WORN_FRACTION = 0.85;

    private WeaponPick() {
    }

    // what we know about a stack without carrying the stack around, so the ranking can be tested without an inventory
    public record Candidate(Item item, int damage, int maxDamage) {
        public static Candidate of(ItemStack stack) {
            return new Candidate(stack.getItem(), stack.getDamageValue(), stack.getMaxDamage());
        }
    }

    public static boolean isWeapon(Item item) {
        return item instanceof SwordItem || item instanceof AxeItem;
    }

    public static boolean wornOut(int damage, int maxDamage) {
        return maxDamage > 0 && damage >= WORN_FRACTION * maxDamage;
    }

    // the weapon to hold, or null if there is no sword or axe in the list. a healthy weapon always beats a worn one, then
    // the harder hit wins, then the faster swing, and the first one listed wins what is left (hand first, so two
    // equal weapons never get swapped back and forth)
    public static Item best(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || !isWeapon(c.item())) continue;
            if (best == null || beats(c, best)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    private static boolean beats(Candidate a, Candidate b) {
        boolean aWorn = wornOut(a.damage(), a.maxDamage());
        boolean bWorn = wornOut(b.damage(), b.maxDamage());
        if (aWorn != bWorn) {
            return bWorn;
        }
        float aDamage = ItemHelper.getAttackDamage(a.item());
        float bDamage = ItemHelper.getAttackDamage(b.item());
        if (aDamage != bDamage) {
            return aDamage > bDamage;
        }
        return ItemHelper.getAttackSpeed(a.item()) > ItemHelper.getAttackSpeed(b.item());
    }
}
