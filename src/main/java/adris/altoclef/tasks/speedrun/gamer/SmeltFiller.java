package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// what to do while the iron cooks. pure (facts and config in, needs out) so the order of the filler can be tested without a game.
// nothing in here walks anywhere: IronPhase turns it into tasks. when to go back for the output is FurnacePlan's call.
// there is no leash: a furnace in an unloaded chunk just pauses, so the bot goes where the work is and walks back for the
// output when it is due (and whatever is in the furnace when we get there is the truth, see CollectFromFurnaceTask)
public final class SmeltFiller {
    // runnable = what we can work on right now, most useful first. blocked = needs that want ingots we do not hold yet.
    // stockUps = the entries of runnable that are only there to use the wait (cfg.smeltExtras), a due job cuts those short
    public record Schedule(List<KitNeed> runnable, List<KitNeed> blocked, List<KitNeed> stockUps) {
        public Schedule(List<KitNeed> runnable, List<KitNeed> blocked) {
            this(runnable, blocked, List.of());
        }

        // is this need one of the stock-ups. record equality, so a plan need that happens to match one counts too, which is fine
        public boolean isStockUp(KitNeed need) {
            return need != null && stockUps.contains(need);
        }
    }

    private SmeltFiller() {
    }

    // with no job running this is exactly KitPlanner.plan. with one: crafts that want more ingots than we hold wait in
    // `blocked`, and the runnable list grows by what is useful to do meanwhile, in this order:
    //   a. the rest of the kit that needs no iron (food, wool, armor we already hold) and the portal's build blocks
    //   b. prep for the blocked crafts: flint, planks and sticks
    //   c. stock-up, one entry of cfg.smeltExtras at a time
    // the engine passes the tick's FoodPlan, the two short forms (package only) build their own
    static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return schedule(f, cfg, endBeds, true);
    }

    static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface) {
        return schedule(f, cfg, endBeds, nearSurface, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    // nearSurface = SmeltSurface.shallow: the log stock-up only happens where a tree is a short walk (see logStockTarget)
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food) {
        List<KitNeed> needs = KitPlanner.plan(f, cfg, endBeds, food);
        if (f.furnaceJobs().isEmpty()) {
            return new Schedule(needs, List.of());
        }
        List<KitNeed> runnable = new ArrayList<>();
        List<KitNeed> blocked = new ArrayList<>();
        split(f, needs, runnable, blocked);
        if (f.buildBlocks() < cfg.portalBuildBlocks) {
            runnable.add(new KitNeed(KitNeed.BUILD_BLOCKS, cfg.portalBuildBlocks));
        }
        prep(f, cfg, endBeds, blocked, runnable);
        List<KitNeed> stockUps = new ArrayList<>();
        extras(f, cfg, endBeds, nearSurface, food, stockUps);
        runnable.addAll(stockUps);
        runnable.removeIf(need -> foodBlocked(f, need));
        return new Schedule(runnable, blocked, stockUps);
    }

    // a craft is runnable while the ingots we hold cover it, handed out in plan order (the pickaxe gets its three before
    // the boots get theirs). the ones we cannot pay for yet wait for the furnace
    private static void split(GamerFacts f, List<KitNeed> needs, List<KitNeed> runnable, List<KitNeed> blocked) {
        int ingots = f.count(Items.IRON_INGOT);
        for (KitNeed need : needs) {
            if (foodBlocked(f, need)) {
                blocked.add(need);
                continue;
            }
            int price = KitPlanner.ingotCost(need.catalogueName()) * (need.count() - KitPlanner.held(f, need.catalogueName()));
            if (price <= 0) {
                runnable.add(need);
            } else if (price <= ingots) {
                ingots -= price;
                runnable.add(need);
            } else {
                blocked.add(need);
            }
        }
    }

    // the food need while a smoker is still cooking the last batch and the bag has more raw meat for it: there is one input
    // slot, so the next batch has to wait for the first to be collected. same shape as a craft waiting for ingots, and the
    // food task would otherwise stand around "cooking" at a smoker that is busy
    static boolean foodBlocked(GamerFacts f, KitNeed need) {
        return KitNeed.FOOD.equals(need.catalogueName()) && f.pendingFoodUnits() > 0 && f.count(ItemHelper.RAW_FOODS) > 0;
    }

    // what GATHER can work on while its food cooks. there are no ingots to wait for here, the one thing that can be stuck is
    // food behind its own smoker
    static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return gatherRunnable(f, cfg, endBeds, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    public static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food) {
        List<KitNeed> out = new ArrayList<>();
        for (KitNeed need : KitPlanner.gather(f, cfg, endBeds, food)) {
            if (!foodBlocked(f, need)) {
                out.add(need);
            }
        }
        return out;
    }

    // the one blocked-need case worth cutting a need short for: the next need is the food and the smoker is still on the last
    // batch. (an iron craft heading the plan does not count, that is every tick of a long cook and the bot got pulled off a
    // ladder need for it)
    public static boolean gatherBlocking(GamerFacts f, List<KitNeed> plan) {
        return !plan.isEmpty() && foodBlocked(f, plan.get(0));
    }

    private static void prep(GamerFacts f, OverworldConfig cfg, int endBeds, List<KitNeed> blocked, List<KitNeed> out) {
        boolean flintAndSteel = isBlocked(blocked, "flint_and_steel");
        if (flintAndSteel && f.count(Items.FLINT) < 1 && f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) < 1) {
            out.add(new KitNeed("flint", 1));
        }
        int planks = planksWanted(blocked, f, cfg, endBeds);
        if (planks > 0 && f.count(ItemHelper.PLANKS) < planks) {
            out.add(new KitNeed("planks", planks));
        }
    }

    // shield is 6 planks, the pickaxe and axe 4 sticks between them (2 planks make 4), a bed is 3 planks (a few beds'
    // worth is stocked, the rest is chopped when the beds are made)
    static int planksWanted(List<KitNeed> blocked, GamerFacts f, OverworldConfig cfg, int endBeds) {
        int planks = 0;
        if (isBlocked(blocked, "shield")) {
            planks += 6;
        }
        if (isBlocked(blocked, "iron_pickaxe") || isBlocked(blocked, "iron_axe") || isBlocked(blocked, "iron_sword")) {
            planks += 2;
        }
        int bedsShort = Math.max(0, endBeds - f.count(ItemHelper.BED));
        return planks + Math.min(cfg.smeltBedPlanks, 3 * bedsShort);
    }

    private static boolean isBlocked(List<KitNeed> blocked, String name) {
        return blocked.stream().anyMatch(n -> n.catalogueName().equals(name));
    }

    // the log stock-up counts planks as wood too (4 to a log), it is only there so we have wood to craft with. 0 = no trip.
    // a stock-up is not worth climbing out of the mine for, so down there it never happens (the iron phase asks for wood
    // itself while the surface is still close)
    static int logStockTarget(GamerFacts f, int wantedLogs, boolean nearSurface) {
        if (!nearSurface) {
            return 0;
        }
        int missing = 4 * wantedLogs - f.count(ItemHelper.PLANKS) - 4 * f.count(ItemHelper.LOG);
        if (missing <= 0) {
            return 0;
        }
        // held logs already count, the need is a total like every other log need
        return wantedLogs - f.count(ItemHelper.PLANKS) / 4;
    }

    // every stock-up is surface work (sheep, animals, a tree, loose stone), so none of them is worth climbing out of the mine for:
    // down there the list is empty and the wait is spent standing at the furnace instead. IronPhase asks for what it needs
    // itself while the surface is still close
    private static void extras(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food, List<KitNeed> out) {
        if (cfg.smeltExtras == null) {
            return;
        }
        for (KitItem extra : cfg.smeltExtras) {
            if (extra.item == null || extra.count <= 0) {
                continue;
            }
            switch (extra.item) {
                case "food" -> {
                    int units = food.stockUp(extra.count);
                    if (nearSurface && food.shortOf(units)) {
                        out.add(new KitNeed(KitNeed.FOOD, units));
                    }
                }
                case "wool_beds" -> {
                    int missing = KitPlanner.woolShortfall(f, endBeds + extra.count);
                    if (nearSurface && missing > 0) {
                        out.add(new KitNeed("wool", f.count(ItemHelper.WOOL) + missing));
                    }
                }
                case "build_blocks" -> {
                    int blocks = cfg.portalBuildBlocks + extra.count;
                    if (nearSurface && f.buildBlocks() < blocks) {
                        out.add(new KitNeed(KitNeed.BUILD_BLOCKS, blocks));
                    }
                }
                case "log" -> {
                    int logs = logStockTarget(f, extra.count, nearSurface);
                    if (logs > 0) {
                        out.add(new KitNeed("log", logs));
                    }
                }
                default -> {
                    // a typo in the config is a stock-up that never happens, not a crash in the middle of a run
                }
            }
        }
    }
}
