package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// what is still missing before we walk into the End. pure over facts + RunState.endDrops (gear we left lying in the End
// counts as owned, we go and pick it up there instead of crafting it again)
public final class EndGear {
    // best first, per slot: helmet, chest, legs, boots. iron is the floor, anything worse is not worth a swap
    private static final Item[][] ARMOR_SLOTS = {
            {Items.NETHERITE_HELMET, Items.DIAMOND_HELMET, Items.IRON_HELMET},
            {Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE},
            {Items.NETHERITE_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS},
            {Items.NETHERITE_BOOTS, Items.DIAMOND_BOOTS, Items.IRON_BOOTS}
    };
    // iron is the floor here too. axes count, the kit makes an axe and not a sword (it hits harder, see WeaponPick)
    private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
    private static final Item[] PICKAXES = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE};

    private EndGear() {
    }

    // beds = how many short of bedsRequired, buildBlocks = how many to collect (target, not the gap) or 0 when fine,
    // armorToWear = pieces we hold of a slot where nothing iron or better is worn
    public record Gap(int beds, boolean weapon, boolean waterBucket, boolean pickaxe, int buildBlocks, boolean food,
                      List<Item> armorToWear) {
        public boolean none() {
            return beds == 0 && !weapon && !waterBucket && !pickaxe && buildBlocks == 0 && !food && armorToWear.isEmpty();
        }
    }

    // bedsRequired is a parameter because a second attempt goes with what it has (the sword strat needs no wool at all)
    // (weapon is a sword or an axe, whichever the bot made)
    public static Gap missing(GamerFacts facts, RunState state, EndConfig cfg, int bedsRequired) {
        int bedGap = bedShortfall(facts, state, cfg, bedsRequired, false);
        boolean weapon = !ownedOrDropped(facts, state, cfg, WEAPONS);
        boolean bucket = !ownedOrDropped(facts, state, cfg, Items.WATER_BUCKET);
        boolean pickaxe = !ownedOrDropped(facts, state, cfg, PICKAXES);
        int blocks = facts.buildBlocks() < cfg.minBuildBlocks ? cfg.buildBlocks : 0;
        return new Gap(Math.max(0, bedGap), weapon, bucket, pickaxe, blocks, facts.foodUnits() < cfg.minFoodUnits,
                armorToWear(facts));
    }

    // beds still to get: required (+1 for the spawn bed) minus what we hold and what lies in the End. <= 0 means a spare
    // (the spawn bed is what the spare is for: placing it takes one out of the inventory, so ask this again afterwards)
    public static int bedShortfall(GamerFacts facts, RunState state, EndConfig cfg, int bedsRequired, boolean wantSpawnBed) {
        int have = facts.count(ItemHelper.BED) + EndRules.dropped(state, facts.gameTime(), cfg, ItemHelper.BED);
        return bedsRequired + (wantSpawnBed ? 1 : 0) - have;
    }

    private static boolean ownedOrDropped(GamerFacts facts, RunState state, EndConfig cfg, Item... items) {
        return facts.count(items) + EndRules.dropped(state, facts.gameTime(), cfg, items) > 0;
    }

    public static List<Item> armorToWear(GamerFacts facts) {
        List<Item> wear = new ArrayList<>();
        for (Item[] slot : ARMOR_SLOTS) {
            if (anyEquipped(facts, slot)) {
                continue;
            }
            for (Item piece : slot) {
                if (facts.has(piece)) {
                    wear.add(piece);
                    break;
                }
            }
        }
        return wear;
    }

    private static boolean anyEquipped(GamerFacts facts, Item[] slot) {
        for (Item piece : slot) {
            if (facts.armorEquipped(piece)) {
                return true;
            }
        }
        return false;
    }

    // what to walk to first while we are in the End and something of ours is lying around (dropped = the item entity is
    // tracked). order is the old BM2 one: beds, tools, bucket, armor. null = nothing worth a detour
    public static Item nextPickup(GamerFacts facts, EndConfig cfg, Predicate<Item> dropped) {
        if (facts.count(ItemHelper.BED) < cfg.beds) {
            Item bed = firstDropped(ItemHelper.BED, dropped);
            if (bed != null) {
                return bed;
            }
        }
        Item tool = missingTool(facts, WEAPONS, dropped);
        if (tool == null) {
            tool = missingTool(facts, PICKAXES, dropped);
        }
        if (tool == null && !facts.has(Items.WATER_BUCKET) && dropped.test(Items.WATER_BUCKET)) {
            tool = Items.WATER_BUCKET;
        }
        return tool != null ? tool : droppedArmor(facts, dropped);
    }

    private static Item missingTool(GamerFacts facts, Item[] tiers, Predicate<Item> dropped) {
        return facts.count(tiers) > 0 ? null : firstDropped(tiers, dropped);
    }

    private static Item droppedArmor(GamerFacts facts, Predicate<Item> dropped) {
        for (Item[] slot : ARMOR_SLOTS) {
            if (!anyEquipped(facts, slot) && facts.count(slot) == 0) {
                Item piece = firstDropped(slot, dropped);
                if (piece != null) {
                    return piece;
                }
            }
        }
        return null;
    }

    private static Item firstDropped(Item[] items, Predicate<Item> dropped) {
        for (Item item : items) {
            if (dropped.test(item)) {
                return item;
            }
        }
        return null;
    }

    // bed self damage is why this exists, see DragonStrat
    public static boolean armorLow(GamerFacts facts, EndConfig cfg) {
        return facts.armorPoints() < cfg.bedMinArmor;
    }

    // a spare bed for the spawn point: the hard gate is cfg.beds, one more is a bonus
    public static int bedTarget(EndConfig cfg, boolean wantSpawnBed) {
        return cfg.beds + (wantSpawnBed ? 1 : 0);
    }

    public static boolean wantSpawnBed(RunState state, EndConfig cfg) {
        return cfg.placeSpawnNearPortal && !state.spawnBedSet && !state.spawnBedGaveUp;
    }
}
