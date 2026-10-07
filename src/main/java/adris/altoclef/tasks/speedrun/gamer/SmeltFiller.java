package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// what to do while the iron cooks, and when to go back for it. pure (facts and config in, needs out) so the order of the
// filler and the leash numbers can be tested without a game. nothing in here walks anywhere: IronPhase turns it into tasks
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

    // when the leash pulls us back it brings us this close (a fraction of the leash), not to the edge, or the filler walks
    // straight out again and we spend the whole cook being pulled
    public static final double PULL_BACK_FRACTION = 0.5;
    // never tighter than this, a one chunk leash is a bot that cannot do anything
    private static final int MIN_LEASH = 16;

    private SmeltFiller() {
    }

    // with no job running this is exactly KitPlanner.plan. with one: crafts that want more ingots than we hold wait in
    // `blocked`, and the runnable list grows by what is useful to do meanwhile, in this order:
    //   a. the rest of the kit that needs no iron (food, wool, armor we already hold) and the portal's build blocks
    //   b. prep for the blocked crafts: flint, planks and sticks
    //   c. stock-up, one entry of cfg.smeltExtras at a time
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return schedule(f, cfg, endBeds, Nearby.ANYWHERE, false);
    }

    // the same with the leash in mind, see Nearby. capped = the leash already dragged us back too often
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, Nearby nearby, boolean capped) {
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
        extras(f, cfg, endBeds, runnable);
        runnable.removeIf(need -> !withinLeash(need, nearby, capped));
        return new Schedule(runnable, blocked);
    }

    // a craft is runnable while the ingots we hold cover it, handed out in plan order (the pickaxe gets its three before
    // the boots get theirs). the ones we cannot pay for yet wait for the furnace
    private static void split(GamerFacts f, List<KitNeed> needs, List<KitNeed> runnable, List<KitNeed> blocked) {
        int ingots = f.count(Items.IRON_INGOT);
        for (KitNeed need : needs) {
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
                    if (f.foodUnits() < units) {
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

    // ---- what is worth doing inside the leash

    // what the entity tracker can see within fillerRadius of the furnace. the scheduler cannot walk anywhere so it is told
    public record Nearby(boolean sheep, boolean food) {
        // for the callers (and tests) that do not care
        public static final Nearby ANYWHERE = new Nearby(true, true);
    }

    // the filler stays this far inside the leash, chasing a sheep over the line is a pull back on the next tick
    public static final int LEASH_MARGIN = 8;

    public static double fillerRadius(int leash) {
        return Math.max(MIN_LEASH / 2.0, leash - LEASH_MARGIN);
    }

    // wool and food are the two fillers that go somewhere (to a sheep, to an animal) and the bot used to walk 65 blocks to one
    // and get pulled straight back. they only run when the tracker has one in range. logs, blocks, flint and the like
    // cannot be located without a scan, so they stay (the pull back is the net for those) until the leash has already
    // dragged us back too often: then only what is known to be close, or needs no walking at all (crafts), is left
    static boolean withinLeash(KitNeed need, Nearby nearby, boolean capped) {
        return switch (need.catalogueName()) {
            case "wool" -> nearby.sheep();
            case KitNeed.FOOD -> nearby.food();
            case "iron_ingot", KitNeed.BUILD_BLOCKS, "flint", "log", "planks", "coal" -> !capped;
            default -> true;
        };
    }

    // ---- when to go back

    // fillerLeft = the runnable list is not empty (already cut down to what the leash allows). atBoundary = the need we were
    // on is done (we are between two needs), the only time we turn around for a furnace that finished
    public static Trip trip(boolean fillerLeft, boolean atBoundary, long now, List<RunState.FurnaceJob> jobs, OverworldConfig cfg) {
        boolean due = FurnaceJobs.anyDue(jobs, now, Math.round(cfg.furnaceWaitSeconds * 20));
        if (!fillerLeft) {
            return due ? Trip.COLLECT : Trip.WAIT;
        }
        return atBoundary && due ? Trip.COLLECT : Trip.FILLER;
    }

    // the leash has dragged us back often enough that the filler stops going out: it was a loop, not a one off
    public static boolean capped(int pullbacks, OverworldConfig cfg) {
        return pullbacks >= cfg.furnaceMaxPullbacks;
    }

    // ---- the leash

    // how far from the furnace we may go. a server's simulation distance is how far from the player blocks tick, minus a
    // chunk so the furnace is not right on the edge. 0 or less = unknown (a server we cannot ask), the config value alone
    public static int leashBlocks(int simulationChunks, int configured) {
        if (simulationChunks <= 0) {
            return configured;
        }
        return Math.max(MIN_LEASH, Math.min(simulationChunks * 16 - 16, configured));
    }

    public static double horizontal(double px, double pz, RunState.Pos at) {
        double dx = at.x + 0.5 - px;
        double dz = at.z + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // pulling = we are already walking back: keep going until we are well inside, not just under the line
    public static boolean pullBack(boolean pulling, double distance, double leash) {
        return distance > (pulling ? leash * PULL_BACK_FRACTION : leash);
    }
}
