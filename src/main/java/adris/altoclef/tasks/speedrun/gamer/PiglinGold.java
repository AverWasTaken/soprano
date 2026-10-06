package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// pure: piglins only leave us alone while ONE piece of gold armor is on, so the kit swaps one iron piece for a gold one
// when that is cheap. a gold helmet is 2 armor like the iron one (and saves 5 iron), gold boots lose 1 armor and save 4.
// never a reason to mine for gold, only gold we hold or can unpack/smelt from what we hold counts
public final class PiglinGold {
    // the order we would rather give a slot up, by armor lost vs iron: helmet 0, chestplate 1, boots 1, leggings 2
    private static final String[] SLOTS = {"helmet", "chestplate", "boots", "leggings"};
    private static final Item[] GOLD = {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS};
    public static final int HELMET_INGOTS = 5;
    public static final int BOOTS_INGOTS = 4;

    private PiglinGold() {
    }

    public static boolean worn(GamerFacts f) {
        for (Item piece : GOLD) {
            if (f.armorEquipped(piece)) {
                return true;
            }
        }
        return false;
    }

    // gold ingots we could end up with without leaving the spot: ingots, blocks (9), nuggets (9 a piece) and raw gold
    // (smelted by CollectGoldIngotTask). what we are wearing or carrying as armor is counted separately
    public static int available(GamerFacts f) {
        return f.count(Items.GOLD_INGOT) + 9 * f.count(Items.GOLD_BLOCK) + f.count(Items.GOLD_NUGGET) / 9 + f.count(Items.RAW_GOLD);
    }

    // "iron_helmet" -> "golden_helmet" for the one slot we are going gold in, everything else as it was. recomputed from
    // the facts every call, the held/worn rule is what makes the swap stick once the piece exists
    public static List<String> swap(GamerFacts f, List<String> names) {
        String slot = slot(f, names);
        if (slot == null) {
            return names;
        }
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(name.equals("iron_" + slot) ? "golden_" + slot : name);
        }
        return out;
    }

    // the slot of the plan that goes gold, or null. a gold piece we wear or hold in a plan slot wins (worn first, then by
    // armor lost), otherwise the cheapest slot we can pay for. any gold piece held outside the plan is just equipped,
    // no point paying for a second one
    static String slot(GamerFacts f, List<String> names) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < SLOTS.length; i++) {
                boolean has = pass == 0 ? f.armorEquipped(GOLD[i]) : f.has(GOLD[i]);
                if (has && names.contains("iron_" + SLOTS[i])) {
                    return SLOTS[i];
                }
            }
        }
        if (f.count(GOLD) > 0) {
            return null;
        }
        int gold = available(f);
        if (gold >= HELMET_INGOTS && names.contains("iron_helmet")) {
            return "helmet";
        }
        if (gold >= BOOTS_INGOTS && names.contains("iron_boots")) {
            return "boots";
        }
        return null;
    }

    // a gold piece in the bag while none is on gets worn even when its slot is not in the plan (lighter plans, chestplate
    // or leggings out of a chest). appended to what toEquip already found if that is not the same piece
    public static void addToEquip(GamerFacts f, List<Item> equip) {
        if (worn(f)) {
            return;
        }
        for (Item piece : GOLD) {
            if (f.has(piece)) {
                if (!equip.contains(piece)) {
                    equip.add(piece);
                }
                return;
            }
        }
    }

    // last chance before the portal: nothing gold on, nothing gold in the bag, but the gold for a helmet (or boots) is
    // there. the catalogue crafts it, the equip step that follows puts it on. empty when there is nothing to do
    public static List<KitNeed> gate(GamerFacts f) {
        if (worn(f) || f.count(GOLD) > 0) {
            return List.of();
        }
        int gold = available(f);
        if (gold >= HELMET_INGOTS) {
            return List.of(new KitNeed("golden_helmet", 1));
        }
        return gold >= BOOTS_INGOTS ? List.of(new KitNeed("golden_boots", 1)) : List.of();
    }
}
