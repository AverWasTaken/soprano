package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import net.minecraft.world.item.Items;

import java.util.List;

// the standard speedrun move: three raw iron go in the furnace the moment we have them, and the iron pickaxe comes out of
// that instead of the stone one grinding through 39 ore. iron mines at speed 6 vs 4 and lasts 250 uses vs 131, and the
// kit wants one anyway. with shears still owed it is five, so the sheep get sheared instead of killed from then on.
// pure (facts in, answers out) so the trigger is testable, the planner and IronPhase ask these
public final class EarlyIronPick {
    // an iron pickaxe is three ingots, and 3 items is 30 s in a furnace, nothing to wait on
    public static final int INGOTS = 3;
    // shears are two more. sheared sheep give 1 to 3 wool each and stay alive for the next one, a killed sheep gives 1, so the
    // bed wool goes a lot faster with shears in hand from the first sheep on. 20 s more in the furnace
    public static final int SHEARS_INGOTS = 2;

    private EarlyIronPick() {
    }

    // the setting is on, the kit wants an iron pick and nothing in the bag (any tier above it) already is one
    static boolean wanted(GamerFacts f, OverworldConfig cfg) {
        if (!f.earlyIronPick() || KitPlanner.missing(f, "iron_pickaxe", 1) == 0) {
            return false;
        }
        for (KitItem k : cfg.ironKit) {
            if ("iron_pickaxe".equals(k.item)) {
                return true;
            }
        }
        return false;
    }

    // the kit still owes shears: they ride along in the early batch
    static boolean shearsOwed(GamerFacts f, OverworldConfig cfg) {
        return inKit(cfg, "shears") && KitPlanner.missing(f, "shears", 1) > 0;
    }

    // how many ingots the early batch is: the pick's three, plus the shears' two while the kit owes them
    public static int batch(GamerFacts f, OverworldConfig cfg) {
        return INGOTS + (shearsOwed(f, cfg) ? SHEARS_INGOTS : 0);
    }

    private static boolean inKit(OverworldConfig cfg, String item) {
        for (KitItem k : cfg.ironKit) {
            if (item.equals(k.item)) {
                return true;
            }
        }
        return false;
    }

    // time to put the first three in. pending ingots count: one early job per pick, it does not fire again while those cook,
    // nor once the ingots are in the bag, nor after the pick exists (wanted). totalIngots is the whole kit's, when all of it
    // is already mined (or cooking) the big batch is one smelt and two would only be slower
    public static boolean due(GamerFacts f, OverworldConfig cfg, int totalIngots) {
        if (!wanted(f, cfg)) {
            return false;
        }
        int ingots = f.count(Items.IRON_INGOT);
        int batch = batch(f, cfg);
        if (ingots >= batch || f.pendingOutput(Items.IRON_INGOT) > 0) {
            return false;
        }
        // the first raw iron going into the furnace used to end the trigger (the bag count dropped under 3), the head went to
        // the 39 need, and that one even fetched the ore back out of the furnace. once started it is due until it is cooking
        if (f.earlyLoadInFlight()) {
            return true;
        }
        int raw = f.count(Items.RAW_IRON);
        return raw >= batch - ingots && raw + ingots < totalIngots;
    }

    // IronPhase calls this every tick it plans. the tick the early batch becomes the head is the start of the load, and it
    // lasts until the job is recorded (GamerTask.takeLoadedFurnaces), the ingots are in the bag, or the window runs out
    // (FurnacePlan.earlyLoadInFlight)
    public static void track(RunState state, KitNeed head, GamerFacts f, OverworldConfig cfg) {
        if (isEarlyBatch(head, f, cfg)) {
            if (!f.earlyLoadInFlight()) {
                state.earlyLoadTick = f.gameTime();
            }
        } else if (state.earlyLoadTick >= 0 && !due(f, cfg, KitPlanner.ingotsNeeded(f, cfg))) {
            state.earlyLoadTick = -1;
        }
    }

    // a drained job ends the early load only if it is the iron cooking. a meat job landing mid load used to clear the latch,
    // due() dropped and the head flipped away from the furnace we were standing at (the #40 flip)
    public static boolean endsLoad(List<RunState.FurnaceJob> drained) {
        for (RunState.FurnaceJob job : drained) {
            if ("iron_ingot".equals(job.output)) {
                return true;
            }
        }
        return false;
    }

    // "hold 3 ingots" (5 with the shears), the catalogue smelts exactly that many and leaves the rest of the raw iron alone
    public static KitNeed need(GamerFacts f, OverworldConfig cfg) {
        return new KitNeed("iron_ingot", batch(f, cfg));
    }

    // is this head the early batch. SmeltSurface must leave it alone: 3 (or 5) items are smelted right where we stand, climbing out
    // of the mine for them would cost more than the furnace does
    public static boolean isEarlyBatch(KitNeed head, GamerFacts f, OverworldConfig cfg) {
        return head != null && "iron_ingot".equals(head.catalogueName()) && head.count() == batch(f, cfg)
                && due(f, cfg, KitPlanner.ingotsNeeded(f, cfg));
    }

    // the ingots for the pick are in the bag: craft it now, the rest of the ore can wait for a better pick
    // the pick's three are enough for the pick, whatever the batch was (a chest's three ingots, a load that came back short)
    public static boolean craftFirst(GamerFacts f, OverworldConfig cfg) {
        return wanted(f, cfg) && f.count(Items.IRON_INGOT) >= INGOTS;
    }

    // the whole batch is out: the shears go right behind the pick, same table visit
    public static boolean shearsWithThePick(GamerFacts f, OverworldConfig cfg) {
        return craftFirst(f, cfg) && shearsOwed(f, cfg) && f.count(Items.IRON_INGOT) >= INGOTS + SHEARS_INGOTS;
    }

    // the pick is made and two ingots of the early batch are left: the shears come next, before a single ore more, so the wool
    // need shears from its first sheep (CollectWoolTask shears whenever shears are in the bag). never while the pick is still
    // owed, those two ingots would be the pick's
    public static boolean shearsFirst(GamerFacts f, OverworldConfig cfg) {
        return f.earlyIronPick() && inKit(cfg, "iron_pickaxe") && !wanted(f, cfg) && shearsOwed(f, cfg)
                && f.count(Items.IRON_INGOT) >= SHEARS_INGOTS;
    }

    // a bot without the pick and with ingots cooking goes back the moment they are done, not at the next need boundary (the
    // mining need is one long task and its boundary is 36 ingots away)
    public static boolean collectNow(GamerFacts f, OverworldConfig cfg) {
        return wanted(f, cfg) && f.count(Items.IRON_INGOT) < batch(f, cfg) && f.pendingOutput(Items.IRON_INGOT) > 0;
    }
}
