package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;

import java.util.ArrayList;
import java.util.List;

// when does the kit's food need get to pull the IRON phase out of the mine. KitPlanner puts "hold 70 units" in front of the
// iron, and a bot with an apple and six mutton (a third of that, after the raw meat counts for what it is worth) climbed out
// for a hunt in the middle of a vein, 14:42 in the log. the food chain eats on its own, this is only the top-up trip.
// the rule: always below the floor (24), otherwise only where a top-up is cheap, which is the surface or a moment when the
// next job is not ore, and once one starts it carries on to the full 70. pure, IronPhase supplies the world bits
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

    // does the food need go first. `topUp` is a top-up already under way (see nextTopUp)
    public static boolean leads(int held, OverworldConfig cfg, boolean onSurface, boolean headIsOre, boolean topUp) {
        if (held < cfg.minHeldFoodUnits) {
            return true;
        }
        if (held >= cfg.minFoodUnits) {
            return false;
        }
        return topUp || onSurface || !headIsOre;
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
