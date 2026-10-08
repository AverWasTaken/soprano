package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;

import java.util.ArrayList;
import java.util.List;

// when does the kit's food need get to pull the IRON phase out of the mine. KitPlanner puts "hold 70 units" in front of the
// iron, and a bot with an apple and six mutton (a third of that, after the raw meat counts for what it is worth) climbed out
// for a hunt in the middle of a vein, 14:42 in the log. the food chain eats on its own, this is only the top-up trip.
// the rule: always below the floor (24), otherwise only where a top-up is cheap, which is the surface (a cook or a craft
// being next in a mine is not a cheap moment, the hunt is a climb either way), and once one starts it carries on to the full
// 70. a cook that is mid load is never cut short by the soft top-up. pure, IronPhase supplies the world bits
public final class FoodGate {
    private FoodGate() {
    }

    // where the kit's own food need is in the list (the minimum one, not the stock-up target or a filler's), -1 if none
    public static int index(List<KitNeed> needs, OverworldConfig cfg) {
        for (int i = 0; i < needs.size(); i++) {
            KitNeed need = needs.get(i);
            if (KitNeed.FOOD.equals(need.catalogueName()) && need.count() <= cfg.minFoodUnits) {
                return i;
            }
        }
        return -1;
    }

    // the job that would run if the food were not there is ore: a trip out costs the vein we are standing in
    public static boolean headIsOre(List<KitNeed> needs, int foodAt) {
        for (int i = 0; i < needs.size(); i++) {
            if (i != foodAt) {
                return "iron_ingot".equals(needs.get(i).catalogueName());
            }
        }
        return false;
    }

    // does the food need go first. `topUp` is a top-up already under way (see nextTopUp), `cookBusy` a cook that picked its
    // station and is still loading it: the soft top-up waits for that, the meat in the station is food too
    public static boolean leads(int held, OverworldConfig cfg, boolean onSurface, boolean topUp, boolean cookBusy) {
        if (held < cfg.minHeldFoodUnits) {
            return true;
        }
        if (held >= cfg.minFoodUnits || cookBusy) {
            return false;
        }
        return topUp || onSurface;
    }

    // food in the slots of a furnace or smoker screen we have open. a load is the bag emptying into the station a stack at a
    // time, and for those ticks the count dipped under the floor and sent the bot for cows with the smoker half full. once the
    // job is recorded the pending sum has it, so it is not counted twice
    public static int inStation(int stationUnits, int pendingUnits) {
        return pendingUnits > 0 ? 0 : stationUnits;
    }

    // a top-up that started on the soft rule keeps going until the full amount, it must not flip every tick as the bot walks
    // up and down or the count wobbles at the line. under the floor it was forced anyway, that does not start one
    public static boolean nextTopUp(boolean topUp, int held, OverworldConfig cfg, boolean leads) {
        if (held >= cfg.minFoodUnits) {
            return false;
        }
        return topUp || (leads && held >= cfg.minHeldFoodUnits);
    }

    public static List<KitNeed> without(List<KitNeed> needs, int at) {
        List<KitNeed> out = new ArrayList<>(needs);
        out.remove(at);
        return out;
    }

    // which side of the two lines the count is on (0 under the floor, 1 between, 2 at the minimum or over), for the log line
    public static int band(int held, OverworldConfig cfg) {
        return held < cfg.minHeldFoodUnits ? 0 : held < cfg.minFoodUnits ? 1 : 2;
    }
}
