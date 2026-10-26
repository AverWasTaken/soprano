package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
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
    // iron is the floor here too. axes count, a sword is what the kit makes and what WeaponPick holds first
    private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
    private static final Item[] PICKAXES = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE};

    private EndGear() {
    }

    // beds = how many short of bedsRequired, buildBlocks = how many to collect (target, not the gap) or 0 when fine,
    // armorToWear = pieces we hold that are better than what is worn on their slot (or the slot is bare)
    public record Gap(int beds, boolean weapon, boolean waterBucket, boolean pickaxe, int buildBlocks, boolean food,
                      List<Item> armorToWear) {
        public boolean none() {
            return beds == 0 && !weapon && !waterBucket && !pickaxe && buildBlocks == 0 && !food && armorToWear.isEmpty();
        }
    }

    // a second try goes with what it has: a stall retry bumps the attempt, but dying in the End regresses DRAGON to END_PREP
    // and PhaseMachine resets the attempt to 1 on every move, so the deaths are what says "we already tried this"
    public static boolean relaxed(int attempt, int endDeaths) {
        return attempt >= 2 || endDeaths >= 1;
    }

    // bedsRequired is a parameter because a second attempt goes with what it has (the sword strat needs no wool at all)
    // (weapon is a sword or an axe, whichever the bot made). food is the same count every other gate goes by (FoodPlan: raw meat
    // nobody can cook is worth its raw value, a smoker's pending batch counts), against the End's own floor
    // the water bucket is in the gate again: the fall bucket (mlg) protects every fling and the void edge, and the dragon flings.
    // bounded though, see wantsWater. the old collector wandered the whole budget away
    public static Gap missing(GamerFacts facts, RunState state, EndConfig cfg, int bedsRequired, FoodPlan food) {
        return missing(facts, state, cfg, bedsRequired, food, false);
    }

    // bucketGivenUp = this END_PREP attempt already spent its clock (or found no pond) on the water bucket
    public static Gap missing(GamerFacts facts, RunState state, EndConfig cfg, int bedsRequired, FoodPlan food, boolean bucketGivenUp) {
        int bedGap = bedShortfall(facts, state, cfg, bedsRequired, false);
        boolean weapon = !ownedOrDropped(facts, state, cfg, WEAPONS);
        boolean water = wantsWater(ownedOrDropped(facts, state, cfg, Items.WATER_BUCKET), facts.has(Items.BUCKET), bucketGivenUp);
        boolean pickaxe = !ownedOrDropped(facts, state, cfg, PICKAXES);
        int blocks = facts.buildBlocks() < cfg.minBuildBlocks ? cfg.buildBlocks : 0;
        return new Gap(Math.max(0, bedGap), weapon, water, pickaxe, blocks, food.shortOfEndFloor(), armorToWear(facts));
    }

    // 90 s of walking and scooping, then the attempt goes in without. counted in ticks the step actually ran
    public static final int BUCKET_BUDGET_TICKS = 1800;

    // the bucket gap: only when there is an empty bucket to fill (we do not craft one for this) and no water one held or
    // lying in the End, and only until the attempt gives up on it
    public static boolean wantsWater(boolean haveOrDropped, boolean emptyBucket, boolean givenUp) {
        return !haveOrDropped && emptyBucket && !givenUp;
    }

    public static boolean bucketTimedOut(int ticksSpent) {
        return ticksSpent >= BUCKET_BUDGET_TICKS;
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

    // the slots are best first, so the first piece we hold is the best one and the first one worn is what is on. a piece goes on
    // when it ranks strictly above what is worn (a diamond helmet over the iron one, the iron one then sits in the bag below it and
    // asks for nothing), or when the slot is bare. the same rule as KitPlanner.toEquip
    public static List<Item> armorToWear(GamerFacts facts) {
        List<Item> wear = new ArrayList<>();
        for (Item[] slot : ARMOR_SLOTS) {
            int worn = rank(slot, facts::armorEquipped);
            int held = rank(slot, facts::has);
            if (held >= 0 && (worn < 0 || held < worn)) {
                wear.add(slot[held]);
            }
        }
        return wear;
    }

    // index of the best piece of the slot the test says yes to, -1 when none
    private static int rank(Item[] slot, Predicate<Item> test) {
        for (int i = 0; i < slot.length; i++) {
            if (test.test(slot[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean anyEquipped(GamerFacts facts, Item[] slot) {
        return rank(slot, facts::armorEquipped) >= 0;
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
        // (a respawn bed the game confirmed near the portal, or one at the stronghold we just walked through, is spawn enough)
        return cfg.placeSpawnNearPortal && !Respawn.coversPortal(state) && !state.spawnBedGaveUp;
    }
}
