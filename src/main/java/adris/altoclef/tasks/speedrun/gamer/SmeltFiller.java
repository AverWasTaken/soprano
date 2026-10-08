package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// what to do while the iron cooks, and when to go back for it. pure (facts and config in, needs out) so the order of the
// filler and the trip rules can be tested without a game. nothing in here walks anywhere: IronPhase turns it into tasks.
// there is no leash: a furnace in an unloaded chunk just pauses, so the bot goes where the work is and walks back for the
// output when it is due (and whatever is in the furnace when we get there is the truth, see CollectFromFurnaceTask)
public final class SmeltFiller {
    // runnable = what we can work on right now, most useful first. blocked = needs that want ingots we do not hold yet.
    // plan = the kit's own list before any of that, its first entry is "the next need"
    public record Schedule(List<KitNeed> runnable, List<KitNeed> blocked, List<KitNeed> plan) {
        // the next need cannot start until the furnace gives something up, the filler is only a way to pass the time
        public boolean blocking() {
            return !plan.isEmpty() && blocked.contains(plan.get(0));
        }
    }

    public enum Trip {
        // carry on with the first runnable need
        FILLER,
        // go to the furnace and take what is done (leave the rest if it is not nearly done)
        COLLECT,
        // nothing left to do out here, go and stand at the furnace until it is all out
        WAIT
    }

    // why a collect trip starts, goes in the log
    public enum Why {
        NONE("not going"),
        // the job is due and we are between two needs
        BOUNDARY("due, and the need we were on is done"),
        // the job is due and the next need is waiting on its output
        BLOCKING("due, and the next need is blocked on it"),
        // the phase has nothing left but this furnace (or nothing else to do while it cooks)
        PHASE_END("due, and the phase has nothing left but this");

        public final String text;

        Why(String text) {
            this.text = text;
        }
    }

    public record Decision(Trip trip, Why why) {
    }

    private SmeltFiller() {
    }

    // with no job running this is exactly KitPlanner.plan. with one: crafts that want more ingots than we hold wait in
    // `blocked`, and the runnable list grows by what is useful to do meanwhile, in this order:
    //   a. the rest of the kit that needs no iron (food, wool, armor we already hold) and the portal's build blocks
    //   b. prep for the blocked crafts: flint, planks and sticks
    //   c. stock-up, one entry of cfg.smeltExtras at a time
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> needs = KitPlanner.plan(f, cfg, endBeds);
        if (f.furnaceJobs().isEmpty()) {
            return new Schedule(needs, List.of(), needs);
        }
        List<KitNeed> runnable = new ArrayList<>();
        List<KitNeed> blocked = new ArrayList<>();
        split(f, needs, runnable, blocked);
        if (f.buildBlocks() < cfg.portalBuildBlocks) {
            runnable.add(new KitNeed(KitNeed.BUILD_BLOCKS, cfg.portalBuildBlocks));
        }
        prep(f, cfg, endBeds, blocked, runnable);
        extras(f, cfg, endBeds, runnable);
        runnable.removeIf(need -> foodBlocked(f, need));
        return new Schedule(runnable, blocked, needs);
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
    public static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> out = new ArrayList<>();
        for (KitNeed need : KitPlanner.gather(f, cfg, endBeds)) {
            if (!foodBlocked(f, need)) {
                out.add(need);
            }
        }
        return out;
    }

    // GATHER's version of Schedule.blocking: the next need is the food and the smoker is still on the last batch
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

    // shield is 6 planks, the pickaxe and sword 3 sticks between them (2 planks make 4), a bed is 3 planks (a few beds'
    // worth is stocked, the rest is chopped when the beds are made)
    static int planksWanted(List<KitNeed> blocked, GamerFacts f, OverworldConfig cfg, int endBeds) {
        int planks = 0;
        if (isBlocked(blocked, "shield")) {
            planks += 6;
        }
        if (isBlocked(blocked, "iron_pickaxe") || isBlocked(blocked, "iron_sword")) {
            planks += 2;
        }
        int bedsShort = Math.max(0, endBeds - f.count(ItemHelper.BED));
        return planks + Math.min(cfg.smeltBedPlanks, 3 * bedsShort);
    }

    private static boolean isBlocked(List<KitNeed> blocked, String name) {
        return blocked.stream().anyMatch(n -> n.catalogueName().equals(name));
    }

    private static void extras(GamerFacts f, OverworldConfig cfg, int endBeds, List<KitNeed> out) {
        if (cfg.smeltExtras == null) {
            return;
        }
        for (KitItem extra : cfg.smeltExtras) {
            if (extra.item == null || extra.count <= 0) {
                continue;
            }
            switch (extra.item) {
                case "food" -> {
                    int units = cfg.targetFoodUnits + extra.count;
                    if (f.foodUnits() + f.pendingFoodUnits() < units) {
                        out.add(new KitNeed(KitNeed.FOOD, units));
                    }
                }
                case "wool_beds" -> {
                    int missing = KitPlanner.woolShortfall(f, endBeds + extra.count);
                    if (missing > 0) {
                        out.add(new KitNeed("wool", f.count(ItemHelper.WOOL) + missing));
                    }
                }
                case "build_blocks" -> {
                    int blocks = cfg.portalBuildBlocks + extra.count;
                    if (f.buildBlocks() < blocks) {
                        out.add(new KitNeed(KitNeed.BUILD_BLOCKS, blocks));
                    }
                }
                case "log" -> {
                    if (f.count(ItemHelper.LOG) < extra.count) {
                        out.add(new KitNeed("log", extra.count));
                    }
                }
                default -> {
                    // a typo in the config is a stock-up that never happens, not a crash in the middle of a run
                }
            }
        }
    }

    // ---- when to go back

    // fillerLeft = the runnable list is not empty. atBoundary = the need we were on is done (we are between two needs).
    // blocking = the next need cannot start without this output (a pickaxe waiting on ingots). phaseEnding = the plan is
    // empty and the furnace is all that is left. a due job is fetched when one of those holds and never mid-need just because
    // it is due: a walk back from a far sheep is only worth it for something that is actually waiting
    public static Decision decide(boolean fillerLeft, boolean atBoundary, boolean blocking, boolean phaseEnding, long now,
                                  List<RunState.FurnaceJob> jobs, OverworldConfig cfg) {
        boolean due = FurnaceJobs.anyDue(jobs, now, Math.round(cfg.furnaceWaitSeconds * 20));
        if (!fillerLeft) {
            return due ? new Decision(Trip.COLLECT, Why.PHASE_END) : new Decision(Trip.WAIT, Why.NONE);
        }
        if (!due) {
            return new Decision(Trip.FILLER, Why.NONE);
        }
        if (phaseEnding) {
            return new Decision(Trip.COLLECT, Why.PHASE_END);
        }
        if (blocking) {
            return new Decision(Trip.COLLECT, Why.BLOCKING);
        }
        return atBoundary ? new Decision(Trip.COLLECT, Why.BOUNDARY) : new Decision(Trip.FILLER, Why.NONE);
    }
}
