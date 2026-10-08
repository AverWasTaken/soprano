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

    // the biggest pile of one kind. the cook loads one kind at a time (one input slot), so this is what the first load burns,
    // and fuel for the whole bag is not needed up front
    public static int pile(GamerFacts f) {
        int best = 0;
        for (Item meat : RAW_MEAT) {
            best = Math.max(best, f.count(meat));
        }
        return best;
    }

    // ingots the kit still wants smelted, beyond the ones in the bag or already cooking. same sum KitPlanner.iron uses for
    // the iron need. while there is any, the plain furnace is the iron's: the early pick and the big batch both walk back to
    // the furnace they remember, and one with the meat in it is a furnace they would mix it up in
    public static boolean ironOwed(GamerFacts f, OverworldConfig cfg) {
        return KitPlanner.ingotsNeeded(f, cfg) > f.count(Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT);
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
    // `mayCraft` = enough meat to be worth a trip. without it only a station standing on the ground counts: a furnace in the bag
    // is a place, cook, walk back, pick up for two rabbit, and the phase waits on all of it
    public static Station station(GamerFacts f, OverworldConfig cfg, boolean mayCraft) {
        boolean ironInFurnace = furnaceHasIron(f);
        // the furnace is for meat only once the iron is done with it (or never needs it): while iron is owed the meat gets a
        // smoker of its own, which cooks twice as fast anyway, and a bag with no way to make one just keeps the meat until
        // the end of the plan
        boolean furnaceFree = !ironInFurnace && !ironOwed(f, cfg);
        // a cook already under way keeps its station, it is busy eating the logs and cobble that picked it
        String running = f.cookStation();
        if ("smoker".equals(running)) {
            return Station.SMOKER;
        }
        if ("furnace".equals(running) && !ironInFurnace) {
            return Station.FURNACE;
        }
        if (f.smokerPlacedNearby() || (mayCraft && f.has(Items.SMOKER))) {
            return Station.SMOKER;
        }
        if (furnaceFree && (f.furnacePlacedNearby() || (mayCraft && f.has(Items.FURNACE)))) {
            return Station.FURNACE;
        }
        if (!mayCraft) {
            return Station.NONE;
        }
        // the stone floor is the cobble the kit still owes its tools, a smoker is not allowed to eat the stone pick
        int free = f.count(Items.COBBLESTONE) - KitPlanner.stoneFloor(f, cfg);
        boolean furnaceInBag = f.has(Items.FURNACE);
        if (f.count(ItemHelper.LOG) >= smokerLogs(f) && (furnaceInBag || free >= SMOKER_COBBLE)) {
            return Station.SMOKER;
        }
        return furnaceFree && free >= SMOKER_COBBLE ? Station.FURNACE : Station.NONE;
    }

    // logs a new smoker costs: its four, and one more for a table when there is none to craft it on (the table's planks used to
    // take the fourth log, and the catalogue went chopping a tree from the bottom of the mine)
    private static int smokerLogs(GamerFacts f) {
        return SMOKER_LOGS + (f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby() ? 0 : 1);
    }

    // a smoker we still have to make: the logs it eats are not fuel
    private static boolean smokerToMake(GamerFacts f, Station station) {
        return station == Station.SMOKER && !f.has(Items.SMOKER) && !f.smokerPlacedNearby() && f.cookStation() == null;
    }

    // smelts of fuel the cook could burn: coal and charcoal always, wood only above what the run keeps for crafting (the same
    // reserve FuelPolicy honours, or the gate would say yes to a stack of logs the furnace is not allowed to touch)
    public static int fuelSmelts(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return fuelSmelts(f, cfg, endBeds, 0);
    }

    // `logsSpoken` = logs about to go into something else (a smoker), taken off the spare wood first
    static int fuelSmelts(GamerFacts f, OverworldConfig cfg, int endBeds, int logsSpoken) {
        int smelts = f.count(Items.COAL, Items.CHARCOAL) * COAL_SMELTS;
        WoodReserve.Keep keep = WoodReserve.keep(f, cfg, endBeds);
        int logs = Math.max(0, f.count(ItemHelper.LOG) - keep.logs() - logsSpoken);
        int planks = Math.max(0, f.count(ItemHelper.PLANKS) - keep.planks());
        // a log burns 1.5 items, a plank 0.75
        return smelts + logs * 3 / 2 + planks * 3 / 4;
    }

    // the cook need, or null when there is nothing to do about the raw meat right now
    public static KitNeed need(GamerFacts f, OverworldConfig cfg, int endBeds) {
        int raw = raw(f);
        // a cook that is mid load has the meat in the station's slot, not the bag: with the last kind in there raw reads 0, and
        // dropping the need then swaps the task out from under a half loaded smoker
        boolean loading = f.cookStation() != null;
        // a smoker cooking for us already is the one input slot taken, the next kind of meat waits for it to be collected
        if ((raw == 0 && !loading) || f.dimension() != Dimension.OVERWORLD || f.cookSuspended() || f.pendingFoodUnits() > 0) {
            return null;
        }
        Station station = station(f, cfg, raw >= MIN_RAW);
        if (station == Station.NONE) {
            return null;
        }
        // a smoker still to make eats wood the fuel count would otherwise have burned. the smoker got built and then sat there cold
        int spoken = smokerToMake(f, station) ? smokerLogs(f) : 0;
        // the first load is the biggest pile, the others cook after it has been collected
        if (fuelSmelts(f, cfg, endBeds, spoken) < pile(f)) {
            return null;
        }
        return new KitNeed(station == Station.SMOKER ? KitNeed.COOK_SMOKER : KitNeed.COOK_FURNACE, MIN_RAW);
    }

    // a furnace or smoker we just emptied, with meat in the bag: load it before it goes back in the bag (FurnaceWatch). no
    // station question, we are standing at one
    public static boolean reusable(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return raw(f) >= MIN_RAW && !f.cookSuspended() && f.pendingFoodUnits() == 0 && fuelSmelts(f, cfg, endBeds) >= pile(f);
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
