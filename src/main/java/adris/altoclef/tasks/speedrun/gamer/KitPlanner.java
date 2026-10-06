package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// pure: what is still missing from the overworld kit, in the order we want it. never asks for what is already held
// (inventory, worn armor, offhand, and better tiers count: a diamond pickaxe is an iron pickaxe, a water bucket is a bucket)
public final class KitPlanner {
    private static final List<String> TIERS = List.of("stone", "iron", "diamond", "netherite");
    private static final List<String> TOOLS = List.of("pickaxe", "sword", "axe", "shovel", "hoe");
    private static final List<String> ARMOR = List.of("helmet", "chestplate", "leggings", "boots");
    // stations get placed and left behind, so once the starter phase is over we stop asking for them
    private static final Set<String> PLACEABLE = Set.of("furnace", "crafting_table");
    // chestplate first: biggest chunk of armor points per trip
    private static final List<String> FULL_IRON = List.of("iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots");
    private static final List<String> CHEST_HELMET = List.of("iron_chestplate", "iron_helmet");
    private static final Map<String, Integer> INGOTS = Map.ofEntries(
            Map.entry("iron_pickaxe", 3), Map.entry("iron_sword", 2), Map.entry("iron_axe", 3),
            Map.entry("iron_shovel", 1), Map.entry("iron_hoe", 2), Map.entry("bucket", 3),
            Map.entry("shield", 1), Map.entry("shears", 2), Map.entry("flint_and_steel", 1),
            Map.entry("iron_helmet", 5), Map.entry("iron_chestplate", 8), Map.entry("iron_leggings", 7),
            Map.entry("iron_boots", 4));

    private KitPlanner() {
    }

    // wood -> table -> stone tools -> furnace, then enough food to survive the next phase
    public static List<KitNeed> gather(GamerFacts f, OverworldConfig cfg) {
        List<KitNeed> out = new ArrayList<>(starter(f, cfg, true));
        addFood(out, f, cfg.minFoodUnits);
        return out;
    }

    // everything the overworld still owes us before the portal, in order: starter tools, food, ONE combined iron ingot
    // need (so the bot mines and smelts once instead of once per item), the crafts, armor on, wool, food top up.
    // food can show up twice (min early, target at the end), the first unsatisfied one is the one that matters
    public static List<KitNeed> plan(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> out = new ArrayList<>(starter(f, cfg, false));
        addFood(out, f, cfg.minFoodUnits);
        out.addAll(iron(f, cfg, endBeds));
        addFood(out, f, cfg.targetFoodUnits);
        return out;
    }

    private static List<KitNeed> starter(GamerFacts f, OverworldConfig cfg, boolean withStations) {
        List<KitNeed> out = new ArrayList<>();
        for (KitItem k : cfg.starterKit) {
            if (!withStations && PLACEABLE.contains(k.item)) {
                continue;
            }
            addItem(out, f, k.item, k.count);
        }
        return out;
    }

    private static List<KitNeed> iron(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> crafts = new ArrayList<>();
        int ingots = 0;
        for (KitItem k : ironItems(f, cfg)) {
            int have = have(f, k.item);
            if (have >= k.count) {
                continue;
            }
            ingots += (k.count - have) * INGOTS.getOrDefault(k.item, 0);
            crafts.add(new KitNeed(k.item, held(f, k.item) + k.count - have));
        }
        List<KitNeed> out = new ArrayList<>();
        // ingots cooking in a furnace we loaded count as held, or the bot would go mining a second batch while the first one cooks
        if (ingots > 0 && f.count(Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT) < ingots) {
            out.add(new KitNeed("iron_ingot", ingots));
        }
        out.addAll(crafts);
        int worn = toEquip(f, cfg).size();
        if (worn > 0) {
            out.add(new KitNeed(KitNeed.EQUIP_ARMOR, worn));
        }
        addWool(out, f, endBeds);
        return out;
    }

    private static List<KitItem> ironItems(GamerFacts f, OverworldConfig cfg) {
        List<KitItem> all = new ArrayList<>(cfg.ironKit);
        // one piece may be gold instead (PiglinGold), which is exactly the iron it no longer costs
        for (String armor : PiglinGold.swap(f, armorNames(cfg))) {
            all.add(new KitItem(armor, 1));
        }
        return all;
    }

    private static void addItem(List<KitNeed> out, GamerFacts f, String name, int count) {
        int have = have(f, name);
        if (have < count) {
            out.add(new KitNeed(name, held(f, name) + count - have));
        }
    }

    private static void addFood(List<KitNeed> out, GamerFacts f, int units) {
        if (f.foodUnits() < units) {
            out.add(new KitNeed(KitNeed.FOOD, units));
        }
    }

    // 3 wool a bed, and a bed we already carry is 3 wool we do not need to find. a bed wants three of ONE colour, so
    // 5 red and 4 white is one bed's worth, not nine wool
    private static void addWool(List<KitNeed> out, GamerFacts f, int endBeds) {
        int missing = woolShortfall(f, endBeds);
        if (missing > 0) {
            // the wool task counts every colour, so ask for what we hold plus the shortfall or it stops too early
            out.add(new KitNeed("wool", f.count(ItemHelper.WOOL) + missing));
        }
    }

    // wool still missing for this many beds, a held bed counting as its three wool. one formula for the wool need and for
    // VillageBeds, so a village bed shrinks exactly the need it is standing in for
    public static int woolShortfall(GamerFacts f, int beds) {
        return Math.max(0, 3 * beds - (usableWool(f) + 3 * f.count(ItemHelper.BED)));
    }

    // the same shortfall in whole beds, which is what a village hands out (rounded up, a bed is not two thirds)
    public static int bedsShort(GamerFacts f, int beds) {
        return (woolShortfall(f, beds) + 2) / 3;
    }

    // wool that turns into beds: whole sets of three per colour
    public static int usableWool(GamerFacts f) {
        int usable = 0;
        for (Item wool : ItemHelper.WOOL) {
            usable += f.count(wool) / 3 * 3;
        }
        return usable;
    }

    // what is left of GATHER once the food is not counted: both stone tools. enough to carry on and let IRON do the food
    public static boolean stoneToolsMet(GamerFacts f) {
        return have(f, "stone_pickaxe") >= 1 && have(f, "stone_sword") >= 1;
    }

    // armor pieces we carry but are not wearing (best tier we hold per slot), for EquipArmorTask
    public static List<Item> toEquip(GamerFacts f, OverworldConfig cfg) {
        List<Item> out = new ArrayList<>();
        for (String name : PiglinGold.swap(f, armorNames(cfg))) {
            Item[] options = counted(name);
            boolean worn = Arrays.stream(options).anyMatch(f::armorEquipped);
            if (worn) {
                continue;
            }
            Item best = null;
            for (Item option : options) {
                if (f.count(option) > 0) {
                    best = option;
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        PiglinGold.addToEquip(f, out);
        return out;
    }

    public static List<String> armorNames(OverworldConfig cfg) {
        ArmorPlan plan = cfg.armorPlan == null ? ArmorPlan.NONE : cfg.armorPlan;
        return switch (plan) {
            case FULL_IRON -> FULL_IRON;
            case CHEST_HELMET -> CHEST_HELMET;
            case NONE -> List.of();
        };
    }

    // ingots one of this item costs, 0 for everything that is not made of iron
    public static int ingotCost(String name) {
        return INGOTS.getOrDefault(name, 0);
    }

    // ingots the missing iron items still need in total (what the combined iron need is sized by)
    public static int ingotsNeeded(GamerFacts f, OverworldConfig cfg) {
        int ingots = 0;
        for (KitItem k : ironItems(f, cfg)) {
            int have = have(f, k.item);
            if (have < k.count) {
                ingots += (k.count - have) * INGOTS.getOrDefault(k.item, 0);
            }
        }
        return ingots;
    }

    // enough to walk into the portal with: pickaxe, light, and the two buckets. what a timed out iron phase may be
    // skipped with (wool and armor are nice, these are not)
    public static boolean essentialsMet(GamerFacts f, OverworldConfig cfg) {
        return have(f, "iron_pickaxe") >= 1
                && f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) >= 1
                && have(f, "bucket") >= 2;
    }

    // what we own of this name, better tiers and filled buckets included
    public static int have(GamerFacts f, String name) {
        return f.count(counted(name));
    }

    // what we own of exactly the thing the catalogue would collect for this name
    public static int held(GamerFacts f, String name) {
        return f.count(exact(name));
    }

    // for the "did that need get closer" check
    public static int progressOf(GamerFacts f, KitNeed need) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> f.foodUnits();
            case KitNeed.BUILD_BLOCKS -> f.buildBlocks();
            case KitNeed.EQUIP_ARMOR -> -need.count();
            default -> have(f, need.catalogueName());
        };
    }

    // plan() runs a few times a tick and these are registry lookups, so the answers are kept (callers never write to them)
    private static final Map<String, Item[]> EXACT = new ConcurrentHashMap<>();
    private static final Map<String, Item[]> COUNTED = new ConcurrentHashMap<>();

    static Item[] exact(String name) {
        return EXACT.computeIfAbsent(name, n -> switch (n) {
            case "wool" -> ItemHelper.WOOL;
            case "bed" -> ItemHelper.BED;
            case "log" -> ItemHelper.LOG;
            case "planks" -> ItemHelper.PLANKS;
            default -> lookup(n);
        });
    }

    static Item[] counted(String name) {
        return COUNTED.computeIfAbsent(name, n -> {
            List<Item> out = new ArrayList<>(Arrays.asList(exact(n)));
            switch (n) {
                case "bucket" -> out.addAll(List.of(Items.WATER_BUCKET, Items.LAVA_BUCKET));
                case "furnace" -> out.add(Items.BLAST_FURNACE);
                default -> addBetterTiers(out, n);
            }
            return out.toArray(new Item[0]);
        });
    }

    // stone_pickaxe is also iron/diamond/netherite, iron_helmet is also diamond/netherite (stone armor does not exist)
    private static void addBetterTiers(List<Item> out, String name) {
        int us = name.indexOf('_');
        if (us < 0) {
            return;
        }
        int tier = TIERS.indexOf(name.substring(0, us));
        String kind = name.substring(us + 1);
        boolean valid = TOOLS.contains(kind) || (ARMOR.contains(kind) && tier >= 1);
        if (tier < 0 || !valid) {
            return;
        }
        for (int i = tier + 1; i < TIERS.size(); i++) {
            out.addAll(Arrays.asList(lookup(TIERS.get(i) + "_" + kind)));
        }
    }

    private static Item[] lookup(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(id))
                .map(item -> new Item[]{item}).orElse(new Item[0]);
    }
}
