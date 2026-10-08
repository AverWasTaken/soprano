package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// when does the bot cook the raw meat it is carrying. foodUnits() counts raw meat at its cooked value (so a bag of mutton does not
// send us hunting), which means the kit's food need is "met" by dinner nobody has cooked, and the food chain ate it raw. this is
// the separate "convert what we have" need: pure (facts in, a need or nothing out) so the rules can be tested without a game.
// where it sits in the list is the same trick as FoodGate: lead on the surface or between two jobs, never in front of the ore,
// and at the very end of the plan regardless, which is what keeps the nether free of raw meat while a furnace can cook it
public final class CookGate {
    // meat and fish only. a raw potato is a side dish (1 unit raw, 5 baked) and nobody builds a smoker for it
    public static final Item[] RAW_MEAT = {Items.PORKCHOP, Items.BEEF, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON};
    // this much raw meat is worth a trip of its own. fewer than this only gets cooked as the last thing, and only where a
    // station is already standing (nobody crafts a smoker for one rabbit)
    public static final int MIN_RAW = 3;
    // a smoker is a furnace (8 cobble) and 4 logs
    private static final int SMOKER_COBBLE = 8;
    private static final int SMOKER_LOGS = 4;
    // smelts a coal burns, the unit the smelt tasks count fuel in
    private static final int COAL_SMELTS = 8;

    public enum Station {
        NONE, SMOKER, FURNACE
    }

    private CookGate() {
    }

    public static int raw(GamerFacts f) {
        return f.count(RAW_MEAT);
    }

    // is a furnace of ours busy with iron right now. food jobs never get this far (the cook waits for them), so any job on a
    // furnace is ore, and the furnace has ONE input slot: loading meat on top would swap the ore back out
    static boolean furnaceHasIron(GamerFacts f) {
        for (RunState.FurnaceJob job : f.furnaceJobs()) {
            if ("furnace".equals(job.kind)) {
                return true;
            }
        }
        return false;
    }

    // where the meat goes, cheapest first: a smoker we have (twice as fast), then a furnace that is free (standing or in the bag),
    // then, if allowed to spend wood and stone, a new smoker or furnace. NONE = nothing sensible right now, so no cook need.
    // a furnace busy with iron queues the meat behind it: the smoker is the way round that, and only if one can be had
    public static Station station(GamerFacts f, OverworldConfig cfg, boolean mayCraft) {
        if (f.has(Items.SMOKER) || f.smokerPlacedNearby()) {
            return Station.SMOKER;
        }
        boolean ironInFurnace = furnaceHasIron(f);
        if (!ironInFurnace && (f.has(Items.FURNACE) || f.furnacePlacedNearby())) {
            return Station.FURNACE;
        }
        if (!mayCraft) {
            return Station.NONE;
        }
        // the stone floor is the cobble the kit still owes its tools, a smoker is not allowed to eat the stone pick
        int free = f.count(Items.COBBLESTONE) - KitPlanner.stoneFloor(f, cfg);
        boolean furnaceInBag = f.has(Items.FURNACE);
        if (f.count(ItemHelper.LOG) >= SMOKER_LOGS && (furnaceInBag || free >= SMOKER_COBBLE)) {
            return Station.SMOKER;
        }
        return !ironInFurnace && free >= SMOKER_COBBLE ? Station.FURNACE : Station.NONE;
    }

    // smelts of fuel the cook could burn: coal and charcoal always, wood only above what the run keeps for crafting (the same
    // reserve FuelPolicy honours, or the gate would say yes to a stack of logs the furnace is not allowed to touch)
    public static int fuelSmelts(GamerFacts f, OverworldConfig cfg, int endBeds) {
        int smelts = f.count(Items.COAL, Items.CHARCOAL) * COAL_SMELTS;
        WoodReserve.Keep keep = WoodReserve.keep(f, cfg, endBeds);
        int logs = Math.max(0, f.count(ItemHelper.LOG) - keep.logs());
        int planks = Math.max(0, f.count(ItemHelper.PLANKS) - keep.planks());
        // a log burns 1.5 items, a plank 0.75
        return smelts + logs * 3 / 2 + planks * 3 / 4;
    }

    // the cook need, or null when there is nothing to do about the raw meat right now
    public static KitNeed need(GamerFacts f, OverworldConfig cfg, int endBeds) {
        int raw = raw(f);
        // a smoker cooking for us already is the one input slot taken, the next kind of meat waits for it to be collected
        if (raw == 0 || f.dimension() != Dimension.OVERWORLD || f.cookSuspended() || f.pendingFoodUnits() > 0) {
            return null;
        }
        Station station = station(f, cfg, raw >= MIN_RAW);
        if (station == Station.NONE || fuelSmelts(f, cfg, endBeds) < raw) {
            return null;
        }
        return new KitNeed(station == Station.SMOKER ? KitNeed.COOK_SMOKER : KitNeed.COOK_FURNACE, MIN_RAW);
    }

    // a furnace or smoker we just emptied, with meat in the bag: load it before it goes back in the bag (FurnaceWatch). no
    // station question, we are standing at one
    public static boolean reusable(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return raw(f) >= MIN_RAW && !f.cookSuspended() && f.pendingFoodUnits() == 0 && fuelSmelts(f, cfg, endBeds) >= raw(f);
    }

    public static int index(List<KitNeed> needs) {
        for (int i = 0; i < needs.size(); i++) {
            if (KitNeed.isCookName(needs.get(i).catalogueName())) {
                return i;
            }
        }
        return -1;
    }

    // does the cook go in front of the rest. the plan always ends with it (that is the "mandatory before the nether" part), this is
    // the early start: enough meat to be worth it, and we are on the surface or the next job is not ore. `latched` is a start
    // that already happened, it carries on or the bot would flip every time it walks over the heightmap line
    public static boolean leads(List<KitNeed> needs, int at, int raw, boolean surfaced, boolean latched) {
        if (raw < MIN_RAW) {
            return false;
        }
        return latched || surfaced || !FoodGate.headIsOre(needs, at);
    }

    // the cook moved to the front, behind a food need that is leading (hunting first, cooking what it brought back after)
    public static List<KitNeed> lead(List<KitNeed> needs, int at) {
        List<KitNeed> out = new ArrayList<>(needs);
        KitNeed cook = out.remove(at);
        int to = !out.isEmpty() && KitNeed.FOOD.equals(out.get(0).catalogueName()) ? 1 : 0;
        out.add(to, cook);
        return out;
    }
}
