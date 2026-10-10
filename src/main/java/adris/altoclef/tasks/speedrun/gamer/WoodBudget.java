package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// the ONE answer to "how much wood does the bot still need, how much is spare, how much may a furnace burn". pure facts in, numbers
// out, built once a tick (GamerContext.wood) and read by the kit planner, the cook gate, the filler, the coal detour and the
// furnace reserve. before this every one of them rebuilt its own view of the same bag and they drifted apart.
// no food in here on purpose: the food plan itself asks the cook gate about fuel, so the budget has to exist before it. the two
// answers that do depend on the food plan take it as an argument (cookFuelLogs, gatherChopLogs)
public record WoodBudget(
        // what the bag holds, every species of a group added up
        int logs, int planks, int sticks,
        // logs still to fetch for everything wooden the kit crafts, a log of slack included: the gather's form (axe, beds and all)
        int gatherLogsOwed,
        // the same once the plan is past the gather: no lost axe, no beds, one pick, no slack. a spare log there is a trip
        int ironLogsOwed,
        // planks the end fight's beds still cost, 3 a bed we do not hold. already inside gatherLogsOwed, the filler stocks it too
        int bedPlanksOwed,
        // the first small trip of the gather, just a table and the axe, so the axe is in hand for the real batch
        int firstBatchLogs,
        // wood spoken for, the rest is spare. it is the GATHER's view of what is owed (gatherLogsOwed) in every phase, which is
        // known-wrong in IRON: there the beds are not owed any more, but the reserve still keeps their planks (see WoodBudgetTest)
        WoodReserve.Keep keep,
        // what the furnaces are told to leave alone (FuelPolicy): keep in the overworld, all of it everywhere else
        WoodReserve.Keep fuelKeep,
        // logs a new smoker costs: its four, and one more for a table when there is none to craft it on
        int smokerLogs,
        // no smoker held, standing or cooking: one still has to be made, so the logs it eats are not fuel
        boolean smokerOwed,
        // logs and planks a furnace is allowed to touch at all (altoSupportedFuels), before the reserve comes off
        int burnableLogs, int burnablePlanks,
        // the most logs cookFuelLogs asks for: cfg.cookFuelLogs while no raw meat is in the bag, else 0. the rest is the food plan's say
        int cookFuelCap) {

    // planks and sticks per kind of tool: only the wooden tier pays the planks, every tier pays the sticks
    private static final Map<String, int[]> TOOL_WOOD = Map.of(
            "pickaxe", new int[]{3, 2}, "axe", new int[]{3, 2}, "sword", new int[]{2, 1},
            "shovel", new int[]{1, 2}, "hoe", new int[]{2, 2});
    private static final int PLANKS_PER_LOG = 4;
    // 4 sticks come out of 2 planks, so sticks are bought in fours
    private static final int STICKS_PER_CRAFT = 4;
    // a smoker is a furnace (8 cobble) and 4 logs
    private static final int SMOKER_LOGS = 4;

    public static WoodBudget of(GamerFacts f, OverworldConfig cfg, int endBeds) {
        int logs = f.count(ItemHelper.LOG);
        int planks = f.count(ItemHelper.PLANKS);
        int gather = owed(f, cfg, endBeds, true);
        WoodReserve.Keep keep = keepOf(f, cfg, endBeds, logs, planks, gather);
        // the run lets wood burn (GamerTask.withWoodFuel) for the overworld cook. nothing down in the nether budgets it, so a clear
        // reserve would let one smelt eat every log in the bag: keep all of it, coal still burns
        WoodReserve.Keep fuel = f.dimension() == Dimension.OVERWORLD ? keep : new WoodReserve.Keep(logs, planks);
        int iron = owed(f, cfg, endBeds, false);
        return new WoodBudget(logs, planks, f.count(Items.STICK), gather, iron, bedPlanksOwed(f, endBeds), firstBatchLogs(f, cfg),
                keep, fuel, smokerLogsFor(f), smokerOwedFor(f), burnable(f, ItemHelper.LOG), burnable(f, ItemHelper.PLANKS),
                cfg.cookFuelLogs <= 0 || CookGate.raw(f) > 0 ? 0 : cfg.cookFuelLogs);
    }

    // ---- what we hold beyond the reserve

    public int spareLogs() {
        return logs - keep.logs();
    }

    public int sparePlanks() {
        return planks - keep.planks();
    }

    // ---- what the planners chop

    // logs on top of the wood budget so the meat has something to burn when it comes in. the food has to be short, a trip due or on
    // (keyed on the same refill as the food need, logs for a hunt that is not coming are just more trees), and the spare logs we
    // already hold count, so the request settles at cfg.cookFuelLogs of surplus instead of chasing itself. past the food the answer
    // is 0 whatever the bag says, or burning them would send the gather back to the trees
    public int cookFuelLogs(FoodPlan food) {
        return cookFuelLogs(food, gatherLogsOwed);
    }

    // `logsShort` = how far the log need is from met. short of the budget every log is spoken for, so there is no spare to look for
    public int cookFuelLogs(FoodPlan food, int logsShort) {
        if (cookFuelCap <= 0 || !food.wantsRefill()) {
            return 0;
        }
        int spare = logsShort > 0 ? 0 : Math.max(0, spareLogs());
        return Math.max(0, cookFuelCap - spare);
    }

    // what the iron phase chops: the whole owed amount before the first ore is in the bag, nothing after. asked of the live facts,
    // not frozen into the budget: the early load latch (EarlyIronPick.track) can start in the middle of a tick, after the budget
    public int ironChopLogs(GamerFacts f) {
        return KitPlanner.ironStarted(f) ? 0 : ironLogsOwed;
    }

    // what the gather asks the trees for: the whole budget and the cook's logs on top
    public int gatherChopLogs(FoodPlan food) {
        return gatherLogsOwed + cookFuelLogs(food);
    }

    // the log stock-up counts planks as wood too (4 to a log), it is only there so we have wood to craft with. 0 = no trip.
    // a stock-up is not worth climbing out of the mine for, so down there it never happens (the iron phase asks for wood
    // itself while the surface is still close)
    public int stockUpLogs(int wantedLogs, boolean nearSurface) {
        return stockUp(logs, planks, wantedLogs, nearSurface);
    }

    // (the same question for a caller that only has the counts)
    static int stockUp(int logs, int planks, int wantedLogs, boolean nearSurface) {
        if (!nearSurface) {
            return 0;
        }
        int missing = 4 * wantedLogs - planks - 4 * logs;
        if (missing <= 0) {
            return 0;
        }
        // held logs already count, the need is a total like every other log need
        return wantedLogs - planks / 4;
    }

    // ---- what the furnace may burn

    // logs the smoker we still have to make will eat, 0 when there is no new smoker coming (CookGate's station decides that)
    public int logsSpokenFor(boolean smokerStation) {
        return smokerStation && smokerOwed ? smokerLogs : 0;
    }

    // smelts of fuel in the wood above the reserve: logs and planks the furnace may burn, `spokenLogs` taken off the spare logs first
    // (logs about to go into something else). a log and a plank are both 300 ticks of fire, 1.5 items (ItemHelper.getFuelAmount)
    public int burnableWoodSmelts(int spokenLogs) {
        int spareLogs = Math.max(0, burnableLogs - keep.logs() - spokenLogs);
        int sparePlanks = Math.max(0, burnablePlanks - keep.planks());
        return (spareLogs + sparePlanks) * 3 / 2;
    }

    // ---- the arithmetic, everything above is built from it

    // logs still to fetch for everything wooden the overworld will craft, one log of slack included, 0 when we hold enough. summed
    // over every kit item still missing (starter and iron kits, ladders, the axe, a table or two, beds) with the planks, sticks and
    // logs we hold taken off. gathering is false once the plan is past the gather: a lost axe is not worth a log, and neither are the beds
    static int owed(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering) {
        int[] wood = new int[2];
        List<KitItem> all = new ArrayList<>(cfg.starterKit);
        all.addAll(cfg.ironKit);
        for (KitItem k : all) {
            if (!gathering && KitPlanner.isAxe(k.item)) {
                continue;
            }
            int count = !gathering && k.item.endsWith("_pickaxe") ? Math.min(k.count, 1) : k.count;
            int m = KitPlanner.missing(f, k.item, count);
            addWood(wood, k.item, m);
            // a stone pick is made with a wooden one, unless something in the bag already mines stone
            boolean needsWooden = k.item.equals("stone_pickaxe") && m > 0 && KitPlanner.have(f, "stone_pickaxe") == 0;
            if (needsWooden && f.count(Items.WOODEN_PICKAXE) == 0) {
                addWood(wood, "wooden_pickaxe", 1);
            }
        }
        if (wood[0] + wood[1] > 0) {
            wood[0] += KitPlanner.tablePlanks(f);
        }
        // a bed is wool and 3 planks, a held bed is both already. a village handing us beds only shrinks the leftovers.
        // only the gather asks: the iron phase is done with wool in hand, the planks for the beds are not its business
        if (gathering) {
            wood[0] += bedPlanksOwed(f, endBeds);
        }
        // the margin is the gather's up-front chop. in the iron phase exactly enough planks is enough, a spare log is a trip
        return logsShort(f, wood[0], wood[1], gathering);
    }

    static int bedPlanksOwed(GamerFacts f, int endBeds) {
        return 3 * Math.max(0, endBeds - f.count(ItemHelper.BED));
    }

    // the first trip to the trees: just the table and the axe, so the axe is in hand for the real batch
    static int firstBatchLogs(GamerFacts f, OverworldConfig cfg) {
        int[] wood = new int[2];
        for (KitItem k : cfg.starterKit) {
            if (KitPlanner.isAxe(k.item)) {
                addWood(wood, k.item, KitPlanner.missing(f, k.item, k.count));
            }
        }
        if (wood[0] + wood[1] == 0) {
            return 0;
        }
        if (!KitPlanner.tableHeld(f)) {
            wood[0] += KitPlanner.TABLE_PLANKS;
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

    // wood spoken for. a log is surplus while the owed amount still says 0 with it gone, so the margin the budget builds in stays ours
    private static WoodReserve.Keep keepOf(GamerFacts f, OverworldConfig cfg, int endBeds, int logs, int planks, int owedNow) {
        if (owedNow > 0) {
            // still short of the budget: every piece of wood is spoken for
            return new WoodReserve.Keep(logs, planks);
        }
        // owed only falls as we hold more, so the biggest k with "still 0 after losing k" is a binary search
        int spareLogs = WoodReserve.most(logs, k -> owed(WoodReserve.without(f, ItemHelper.LOG, k), cfg, endBeds, true) == 0);
        GamerFacts afterLogs = WoodReserve.without(f, ItemHelper.LOG, spareLogs);
        int sparePlanks = WoodReserve.most(planks, k -> owed(WoodReserve.without(afterLogs, ItemHelper.PLANKS, k), cfg, endBeds, true) == 0);
        return new WoodReserve.Keep(logs - spareLogs, planks - sparePlanks);
    }

    // logs a new smoker costs: its four, and one more for a table when there is none to craft it on (the table's planks used to
    // take the fourth log, and the catalogue went chopping a tree from the bottom of the mine)
    static int smokerLogsFor(GamerFacts f) {
        return SMOKER_LOGS + (f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby() ? 0 : 1);
    }

    // a smoker we still have to make: nothing held, standing or cooking for us
    static boolean smokerOwedFor(GamerFacts f) {
        return !f.has(Items.SMOKER) && !f.smokerPlacedNearby() && f.cookStation() == null;
    }

    // how many of these we hold that a furnace may burn (f.burnable, altoSupportedFuels)
    private static int burnable(GamerFacts f, Item[] items) {
        int total = 0;
        for (Item item : items) {
            if (f.burnable(item)) {
                total += f.count(item);
            }
        }
        return total;
    }
}
