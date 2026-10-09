package adris.altoclef.util.helpers;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

// which melee weapon to hold. one on one the kill tasks wait for a full cooldown before every swing, so what matters is
// damage per swing and not damage per second, and an axe hits harder per swing than a sword of the same tier (wood 7 vs 4,
// iron 9 vs 6). the price is that it swings slower, which only decides it when two weapons hit for the same
//
// in a crowd that flips: we are not standing around for the full cooldown, we are swinging as it comes up and every
// second the axe takes to come back is a second of zombie. damage per second then (wooden axe 5.6 against a stone
// sword's 8, iron axe 8.1 against an iron sword's 9.6)
//
// a target with a raised shield is the one case the axe wins anyway: it knocks the shield down for a few seconds
public final class WeaponPick {

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

    // the weapon to hold, or null if there is no sword or axe in the list. a healthy weapon always beats a worn one, then
    // the harder hit wins, then the faster swing, and the first one listed wins what is left (hand first, so two
    // equal weapons never get swapped back and forth)
    public static Item best(Iterable<Candidate> candidates) {
        return best(candidates, false, false);
    }

    // the weapon to hold, or failing that the tool that hits hardest (a pickaxe in the bag beats a bare hand, so a bot with
    // one is armed as far as "fight a lone zombie" goes), or null for nothing better than fists. a stick is not a tool
    public static Item bestOrTool(Iterable<Candidate> candidates, boolean crowd, boolean shieldedTarget) {
        Item weapon = best(candidates, crowd, shieldedTarget);
        return weapon != null ? weapon : bestTool(candidates);
    }

    // pickaxes, shovels and whatever else hits harder than a fist, the same ranking as the weapons (the harder hit, hand first
    // so ties never swap). a worn one is not on the list at all: every swing costs a tool two uses, and the last of the only
    // pickaxe is worth more than a fight we can run from
    public static Item bestTool(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || isWeapon(c.item()) || !ItemHelper.hitsHarderThanFists(c.item())) continue;
            if (ToolWear.wornOut(c.damage(), c.maxDamage())) continue;
            if (best == null || beats(c, best, false, false)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    // crowd: two or more melee mobs around us, damage per second decides. shieldedTarget: something is holding a shield up
    // at us, any axe beats any sword (and it wins over the crowd rule, a shield up in a crowd still eats a sword's hits)
    public static Item best(Iterable<Candidate> candidates, boolean crowd, boolean shieldedTarget) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || !isWeapon(c.item())) continue;
            if (best == null || beats(c, best, crowd, shieldedTarget)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    private static boolean beats(Candidate a, Candidate b, boolean crowd, boolean shieldedTarget) {
        boolean aWorn = ToolWear.wornOut(a.damage(), a.maxDamage());
        boolean bWorn = ToolWear.wornOut(b.damage(), b.maxDamage());
        if (aWorn != bWorn) {
            return bWorn;
        }
        if (shieldedTarget) {
            boolean aAxe = a.item() instanceof AxeItem;
            if (aAxe != (b.item() instanceof AxeItem)) {
                return aAxe;
            }
        } else if (crowd) {
            float aDps = ItemHelper.getAttackDamage(a.item()) * ItemHelper.getAttackSpeed(a.item());
            float bDps = ItemHelper.getAttackDamage(b.item()) * ItemHelper.getAttackSpeed(b.item());
            // (float noise would flip two equal weapons back and forth, so only a real gap counts)
            if (Math.abs(aDps - bDps) > 1e-3) {
                return aDps > bDps;
            }
        }
        float aDamage = ItemHelper.getAttackDamage(a.item());
        float bDamage = ItemHelper.getAttackDamage(b.item());
        if (aDamage != bDamage) {
            return aDamage > bDamage;
        }
        return ItemHelper.getAttackSpeed(a.item()) > ItemHelper.getAttackSpeed(b.item());
    }
}
