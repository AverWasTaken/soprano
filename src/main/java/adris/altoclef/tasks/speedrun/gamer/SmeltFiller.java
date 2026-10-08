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
    // runnable = what we can work on right now, most useful first. blocked = needs that want ingots we do not hold yet
    public record Schedule(List<KitNeed> runnable, List<KitNeed> blocked) {
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
        // the job is due and something is worth cutting the current need short for (the early pick, a smoker holding up the food)
        INTERRUPT("due, and the output is holding up the plan"),
        // the job is due and there is nothing else useful to do
        IDLE("due, and there is nothing else to do");

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
        return schedule(f, cfg, endBeds, true);
    }

    // nearSurface = SmeltSurface.shallow: the log stock-up only happens where a tree is a short walk (see logStockTarget)
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface) {
        List<KitNeed> needs = KitPlanner.plan(f, cfg, endBeds);
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
        extras(f, cfg, endBeds, nearSurface, runnable);
        runnable.removeIf(need -> foodBlocked(f, need));
        return new Schedule(runnable, blocked);
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

    private static void extras(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, List<KitNeed> out) {
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

    // ---- standing by for a smoker

    // a smoker does 5 s an item, a batch of 8 meat is 40 s. mining for iron and walking back costs more than that, so while one
    // is running the bot waits at it instead of working the filler (the furnace is 10 s an item and still gets the filler)
    public static final String SMOKER = "smoker";
    // past the estimate by this much and the stand by is given up, a smoker with no fuel or in a chunk that stopped ticking must
    // not hold the run. the collect trip has its own cap on the same number (FurnaceWatch.collectJob)
    private static final long STAND_BY_SLACK_TICKS = 30 * 20;

    // the smoker job that is ready first, null when no smoker is running
    public static RunState.FurnaceJob smokerJob(List<RunState.FurnaceJob> jobs) {
        RunState.FurnaceJob best = null;
        for (RunState.FurnaceJob job : jobs) {
            if (SMOKER.equals(job.kind) && (best == null || job.doneTick < best.doneTick)) {
                best = job;
            }
        }
        return best;
    }

    // game tick the stand by for this job ends at whatever happens, taken when it starts. the job object is the same one across
    // visits (afterVisit re-stamps it in place), so a smoker that keeps coming up short cannot restart the clock
    public static long standByUntil(RunState.FurnaceJob smoker, long now) {
        return Math.max(now, smoker.doneTick) + STAND_BY_SLACK_TICKS;
    }

    // a batch with more than this left when we first see it (a stack of 64 is five minutes) is not "fast": it is worked like a
    // furnace job. decided once, at first sight, so a bot halfway up a ladder is not pulled back when the clock reaches the mark
    private static final long STAND_BY_MAX_TICKS = 60 * 20;

    public static boolean quickEnough(RunState.FurnaceJob smoker, long now) {
        return smoker.doneTick - now <= STAND_BY_MAX_TICKS;
    }

    // stand by at the smoker instead of working the filler? `until` = standByUntil of the job we started on (-1 = not started)
    public static boolean standBy(List<RunState.FurnaceJob> jobs, long now, long until) {
        return smokerJob(jobs) != null && (until < 0 || now <= until);
    }

    // ---- when to go back

    // fillerLeft = the runnable list is not empty. atBoundary = the need we were on is done (we are between two needs).
    // interrupt = the one thing that may cut a need short: the early pick wants its ingots (EarlyIronPick.collectNow), or the
    // food is stuck behind its own smoker (gatherBlocking). a due job otherwise waits for the need to end, however loudly an
    // iron craft is waiting on it: the bot got pulled off a ladder need for exactly that. an empty filler list is the other
    // way out, there is nothing left to cut short
    public static Decision decide(boolean fillerLeft, boolean atBoundary, boolean interrupt, long now,
                                  List<RunState.FurnaceJob> jobs, OverworldConfig cfg) {
        boolean due = FurnaceJobs.anyDue(jobs, now, Math.round(cfg.furnaceWaitSeconds * 20));
        if (!fillerLeft) {
            return due ? new Decision(Trip.COLLECT, Why.IDLE) : new Decision(Trip.WAIT, Why.NONE);
        }
        if (!due) {
            return new Decision(Trip.FILLER, Why.NONE);
        }
        if (interrupt) {
            return new Decision(Trip.COLLECT, Why.INTERRUPT);
        }
        return atBoundary ? new Decision(Trip.COLLECT, Why.BOUNDARY) : new Decision(Trip.FILLER, Why.NONE);
    }
}
