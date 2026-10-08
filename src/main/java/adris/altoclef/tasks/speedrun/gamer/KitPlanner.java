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
    // wooden first, so a stone axe is a wooden axe too and nobody re-makes the axe because the first one got upgraded
    private static final List<String> TIERS = List.of("wooden", "stone", "iron", "diamond", "netherite");
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

    // a pick past this much of its durability does not count as owned (see wornOut). stone gets 131 uses, so this is
    // about a hundred blocks of mining, and then we would rather make a fresh one at a table than find out in a cave
    private static final double WORN_FRACTION = 0.85;
    private static final int PLANKS_PER_LOG = 4;
    // 4 sticks come out of 2 planks, so sticks are bought in fours
    private static final int STICKS_PER_CRAFT = 4;
    private static final int TABLE_PLANKS = 4;
    // planks and sticks per kind of tool: only the wooden tier pays the planks, every tier pays the sticks
    private static final Map<String, int[]> TOOL_WOOD = Map.of(
            "pickaxe", new int[]{3, 2}, "axe", new int[]{3, 2}, "sword", new int[]{2, 1},
            "shovel", new int[]{1, 2}, "hoe", new int[]{2, 2});

    // wood -> axe -> the rest of the wood -> table -> stone tools -> furnace, then enough food to survive the next phase
    public static List<KitNeed> gather(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> out = new ArrayList<>(starter(f, cfg, endBeds, true, new ArrayList<>()));
        addFood(out, f, cfg.minFoodUnits);
        return out;
    }

    // everything the overworld still owes us before the portal, in order: starter tools, food, ONE combined iron ingot
    // need (so the bot mines and smelts once instead of once per item), the crafts, armor on, wool, food top up.
    // food can show up twice (min early, target at the end), the first unsatisfied one is the one that matters
    public static List<KitNeed> plan(GamerFacts f, OverworldConfig cfg, int endBeds) {
        // a worn pick we still carry gets replaced after the iron, not in the middle of it (see starter)
        List<KitNeed> late = new ArrayList<>();
        List<KitNeed> out = new ArrayList<>(starter(f, cfg, endBeds, false, late));
        addFood(out, f, cfg.minFoodUnits);
        out.addAll(iron(f, cfg, endBeds));
        out.addAll(late);
        addFood(out, f, cfg.targetFoodUnits);
        return out;
    }

    // gathering: all the wood comes first, so nothing climbs out of a cave for one more log later. a small batch (table
    // and axe), the axe, the rest of the budget with the axe in hand, then the stone tools.
    // not gathering (IRON on): stations are not asked for again, a lost axe stays lost and the spare pick is not worth a
    // trip. wood is only asked for before the first ore, while the surface is still right here.
    // `late` is where the IRON phase parks a pick replacement: a stone pick that is merely worn (85%, see wornOut) is still
    // a pick, and swapping it mid-mine once cost a 34 s log trip. only a bag with no pick at all keeps it up front
    private static List<KitNeed> starter(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering, List<KitNeed> late) {
        List<KitNeed> out = new ArrayList<>();
        List<KitItem> axes = new ArrayList<>();
        List<KitItem> rest = new ArrayList<>();
        for (KitItem k : cfg.starterKit) {
            if (isAxe(k.item)) {
                axes.add(k);
            } else if (gathering || !PLACEABLE.contains(k.item)) {
                rest.add(k);
            }
        }
        if (gathering) {
            if (axes.stream().anyMatch(a -> missing(f, a.item, a.count) > 0)) {
                addLogs(out, f, firstBatchLogs(f, cfg));
            }
            for (KitItem a : axes) {
                addItem(out, f, a.item, a.count);
            }
            addLogs(out, f, woodNeed(f, cfg, endBeds, true));
            // every cobble the stone kit eats in one trip, before the first stone craft, so the bot doesn't go back down
            // for the furnace's cobble after crafting the axe
            addCobble(out, f, stoneNeed(f, cfg));
        } else if (!ironStarted(f)) {
            addLogs(out, f, woodNeed(f, cfg, endBeds, false));
        }
        for (KitItem k : rest) {
            // the spare pick is for the gather. once underground one fresh pick is the plan and the next is a craft
            boolean pick = !gathering && k.item.endsWith("_pickaxe");
            addItem(pick && holdsAnyPick(f) ? late : out, f, k.item, pick ? Math.min(k.count, 1) : k.count);
        }
        return out;
    }

    // any pickaxe at all in the bag, worn out ones included: it still breaks blocks, it just is not one we count on
    public static boolean holdsAnyPick(GamerFacts f) {
        for (String tier : TIERS) {
            for (Item pick : exact(tier + "_pickaxe")) {
                if (f.count(pick) + f.spent(pick) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // the axe for the logs, not the weapon: stone_axe and iron_axe are in the kit now too (instead of the swords) and they
    // are crafts like any other. calling them the log axe would have fetched them before the stone pick and skipped their
    // sticks once the iron phase starts
    private static boolean isAxe(String name) {
        return name.equals("wooden_axe");
    }

    // ore in the bag, raw or cooking: the cave trip has begun and wood is no reason to leave it.
    // an early load in flight counts too, mid-move the bag is empty and we'd go chop a tree with the furnace open
    private static boolean ironStarted(GamerFacts f) {
        return f.count(Items.RAW_IRON, Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT) > 0 || f.earlyLoadInFlight();
    }

    private static void addLogs(List<KitNeed> out, GamerFacts f, int logs) {
        if (logs > 0) {
            out.add(new KitNeed("log", held(f, "log") + logs));
        }
    }

    private static void addCobble(List<KitNeed> out, GamerFacts f, int cobble) {
        if (cobble > 0) {
            out.add(new KitNeed(COBBLE, held(f, COBBLE) + cobble));
        }
    }

    // the catalogue name of the stone gather. a gathering name (KitNeed), so it never holds the table
    public static final String COBBLE = "cobblestone";
    // cobble a stray placement can eat before the crafts, so one misplaced block is not a second trip
    private static final int STONE_SLACK = 2;
    // cobble per item: the tool shapes (pickaxe and axe 3, sword and hoe 2, shovel 1) and the furnace's ring of 8.
    // the kit makes the axe and not the sword (3 cobble, not 2), so a fresh run mines 19
    private static final Map<String, Integer> COBBLE_COST = Map.of(
            "stone_pickaxe", 3, "stone_axe", 3, "stone_sword", 2, "stone_hoe", 2, "stone_shovel", 1, "furnace", 8);

    // cobble still to mine for every stone item the kit is missing, slack included, held cobble taken off. 0 when we hold
    // enough. only plain cobblestone counts as held: the catalogue recipes (and so CraftInTableTask) put Items.COBBLESTONE
    // in the slots and nothing else, so cobbled deepslate in the bag would be a second trip wearing a disguise.
    // the slack is part of what we hold ourselves to (same as woodNeed), the crafts eat held and wanted in step so the
    // answer stays 0 through them. the gather only: once ore is in the bag a worn pick is a just in time craft
    public static int stoneNeed(GamerFacts f, OverworldConfig cfg) {
        int wanted = stoneWanted(f, cfg, false);
        if (wanted <= 0) {
            return 0;
        }
        return Math.max(0, wanted + STONE_SLACK - held(f, COBBLE));
    }

    // the cobble the stone items still missing from the kit will eat, held cobble NOT taken off and no slack. this is what
    // the bag has to keep hold of for the crafts, as opposed to stoneNeed, which is what is left to go and mine. the
    // movements get it as a floor (BotBehaviour.setReserveFloor): a task only reserves the recipe it is on right now (6 for
    // two picks), the axe and the furnace were up for grabs as scaffolding and the gather came right back for them.
    // a furnace of ours standing close by is a furnace we own (8 cobble the kit is not about to spend), same as a placed
    // table is held for the planks. plain cobblestone only, the build blocks the iron phase places on purpose are not in here
    public static int stoneFloor(GamerFacts f, OverworldConfig cfg) {
        return stoneWanted(f, cfg, f.furnacePlacedNearby());
    }

    private static int stoneWanted(GamerFacts f, OverworldConfig cfg, boolean furnaceStanding) {
        List<KitItem> all = new ArrayList<>(cfg.starterKit);
        all.addAll(cfg.ironKit);
        int wanted = 0;
        for (KitItem k : all) {
            Integer cost = COBBLE_COST.get(k.item);
            if (cost == null) {
                continue;
            }
            int missing = missing(f, k.item, k.count);
            if (furnaceStanding && k.item.equals("furnace")) {
                missing = Math.max(0, missing - 1);
            }
            wanted += cost * missing;
        }
        return wanted;
    }

    // logs still to fetch for everything wooden the overworld will craft, one log of slack included, 0 when we hold
    // enough. summed over every kit item still missing (starter and iron kits, ladders, the axe, a table or two, beds)
    // with the planks, sticks and logs we hold taken off
    public static int woodNeed(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return woodNeed(f, cfg, endBeds, true);
    }

    // gathering is false once the plan is past the gather: a lost axe is not worth a log, and neither are the beds
    private static int woodNeed(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering) {
        int[] wood = new int[2];
        List<KitItem> all = new ArrayList<>(cfg.starterKit);
        all.addAll(cfg.ironKit);
        for (KitItem k : all) {
            if (!gathering && isAxe(k.item)) {
                continue;
            }
            int count = !gathering && k.item.endsWith("_pickaxe") ? Math.min(k.count, 1) : k.count;
            int m = missing(f, k.item, count);
            addWood(wood, k.item, m);
            // a stone pick is made with a wooden one, unless something in the bag already mines stone
            boolean needsWooden = k.item.equals("stone_pickaxe") && m > 0 && have(f, "stone_pickaxe") == 0;
            if (needsWooden && f.count(Items.WOODEN_PICKAXE) == 0) {
                addWood(wood, "wooden_pickaxe", 1);
            }
        }
        if (wood[0] + wood[1] > 0) {
            wood[0] += tablePlanks(f);
        }
        // a bed is wool and 3 planks, a held bed is both already. a village handing us beds only shrinks the leftovers.
        // only the gather asks: the iron phase is done with wool in hand, the planks for the beds are not its business
        if (gathering) {
            wood[0] += 3 * Math.max(0, endBeds - f.count(ItemHelper.BED));
        }
        return logsShort(f, wood[0], wood[1], true);
    }

    // planks for a table (or two): with no tool made yet the first one is still ahead of us, and the second is the
    // spare for the iron crafts if the first is too far to walk back to
    private static int tablePlanks(GamerFacts f) {
        if (tableHeld(f)) {
            return 0;
        }
        boolean madeTools = f.count(ItemHelper.WOODEN_TOOLS) + f.count(ItemHelper.STONE_TOOLS) + f.count(ItemHelper.IRON_TOOLS) > 0;
        return TABLE_PLANKS * (madeTools ? 1 : 2);
    }

    // a table standing next to us is coming back to the bag, so it is as good as in it. without this the plan asked for
    // table planks the second the table went down and walked off to chop with the axe still uncrafted
    private static boolean tableHeld(GamerFacts f) {
        return f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby();
    }

    // the first trip to the trees: just the table and the axe, so the axe is in hand for the real batch
    private static int firstBatchLogs(GamerFacts f, OverworldConfig cfg) {
        int[] wood = new int[2];
        for (KitItem k : cfg.starterKit) {
            if (isAxe(k.item)) {
                addWood(wood, k.item, missing(f, k.item, k.count));
            }
        }
        if (wood[0] + wood[1] == 0) {
            return 0;
        }
        if (!tableHeld(f)) {
            wood[0] += TABLE_PLANKS;
        }
        return logsShort(f, wood[0], wood[1], false);
    }

    private static void addWood(int[] wood, String item, int count) {
        if (count <= 0) {
            return;
        }
        switch (item) {
            case "shield" -> wood[0] += 6 * count;
            case "bed" -> wood[0] += 3 * count;
            // 7 sticks make 3 ladders
            case "ladder" -> wood[1] += 7 * ((count + 2) / 3);
            default -> {
                int us = item.indexOf('_');
                int[] tool = us < 0 ? null : TOOL_WOOD.get(item.substring(us + 1));
                if (tool != null) {
                    wood[0] += item.startsWith("wooden_") ? tool[0] * count : 0;
                    wood[1] += tool[1] * count;
                }
            }
        }
    }

    // planks and sticks to logs: sticks are bought in fours (two planks), logs in fours (planks), what we hold comes off
    private static int logsShort(GamerFacts f, int planks, int sticks, boolean margin) {
        int stickShort = Math.max(0, sticks - f.count(Items.STICK));
        int total = planks + 2 * ((stickShort + STICKS_PER_CRAFT - 1) / STICKS_PER_CRAFT);
        if (total <= 0) {
            return 0;
        }
        // the slack is part of what we hold ourselves to, not a bonus when short, or the need would flicker at the edge
        if (margin) {
            total += PLANKS_PER_LOG;
        }
        int missingPlanks = total - f.count(ItemHelper.PLANKS) - PLANKS_PER_LOG * f.count(ItemHelper.LOG);
        return missingPlanks <= 0 ? 0 : (missingPlanks + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG;
    }

    private static List<KitNeed> iron(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> crafts = new ArrayList<>();
        int ingots = 0;
        for (KitItem k : ironItems(f, cfg)) {
            int missing = missing(f, k.item, k.count);
            if (missing == 0) {
                continue;
            }
            ingots += missing * INGOTS.getOrDefault(k.item, 0);
            crafts.add(new KitNeed(k.item, held(f, k.item) + missing));
        }
        List<KitNeed> out = new ArrayList<>();
        // the three ingots for the pick are in the bag: make it before mining a single ore more (EarlyIronPick)
        if (EarlyIronPick.craftFirst(f, cfg)) {
            crafts.stream().filter(n -> n.catalogueName().equals("iron_pickaxe")).findFirst().ifPresent(pick -> {
                out.add(pick);
                crafts.remove(pick);
            });
        } else if (EarlyIronPick.due(f, cfg, ingots)) {
            // three raw iron and no pick: smelt just those, the big batch is what comes after (and is in the list as well)
            out.add(EarlyIronPick.need());
        }
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
        int missing = missing(f, name, count);
        if (missing > 0) {
            out.add(new KitNeed(name, held(f, name) + missing));
        }
    }

    // how many of this we still have to make to hold `count` of it. a better pickaxe than the one asked for covers the
    // whole quantity: two stone picks are a spare for the gather, and once there is an iron one in the bag a stone pick
    // is a waste of cobble and a trip to a table
    static int missing(GamerFacts f, String name, int count) {
        if (outclassed(f, name)) {
            return 0;
        }
        return Math.max(0, count - have(f, name));
    }

    private static boolean outclassed(GamerFacts f, String name) {
        if (!name.endsWith("_pickaxe")) {
            return false;
        }
        return f.count(counted(name)) > f.count(exact(name));
    }

    // a wooden, stone or iron pickaxe this far gone is as good as gone: we would rather craft the next one while we still
    // have a table and the materials than have it break in a cave. MinecraftFacts keeps those out of count()
    public static boolean wornOut(Item item, int damage, int maxDamage) {
        boolean ours = item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE;
        return ours && maxDamage > 0 && damage >= WORN_FRACTION * maxDamage;
    }

    private static void addFood(List<KitNeed> out, GamerFacts f, int units) {
        if (f.foodUnits() + f.pendingFoodUnits() < units) {
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

    // what is left of GATHER once the food is not counted: both stone tools. enough to carry on and let IRON do the food.
    // the weapon is whatever swings: the kit makes an axe, but a sword out of a chest is a stone sword too (a better tier
    // of either counts, have() sees to that)
    public static boolean stoneToolsMet(GamerFacts f) {
        return have(f, "stone_pickaxe") >= 1 && (have(f, "stone_axe") >= 1 || have(f, "stone_sword") >= 1);
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
            ingots += missing(f, k.item, k.count) * INGOTS.getOrDefault(k.item, 0);
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

    // what we own of exactly the thing the catalogue would collect for this name. worn out picks are in here even though
    // they are not in count(): the catalogue sees them in the bag, so its target has to start above them
    public static int held(GamerFacts f, String name) {
        Item[] exact = exact(name);
        int total = f.count(exact);
        for (Item item : exact) {
            total += f.spent(item);
        }
        return total;
    }

    // for the "did that need get closer" check
    public static int progressOf(GamerFacts f, KitNeed need) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> f.foodUnits() + f.pendingFoodUnits();
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
