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

    // a soft top-up shortfall this small is not worth a trip when the raw meat in the bag would close it. 10 is the most a
    // smoker standing nearby can swing the sum by: under CookGate.MIN_RAW the cook is only feasible with a station already
    // there, so that is MIN_RAW - 1 = two raw beef or porkchop at 5 apiece. a smaller cap left two beef churning like the chicken
    public static final int SMALL_GAP = 10;

    // does the food need go first. `topUp` is a top-up already under way (see nextTopUp), `cookBusy` a cook that picked its
    // station and is still loading it (see cookBusy): the soft top-up waits for that, the meat in the station is food too.
    // `rawLeftOut` is the cooked-over-raw value of the raw meat in the bag that `held` does not count (KitPlanner.rawGapLeftOut)
    public static boolean leads(int held, int rawLeftOut, OverworldConfig cfg, boolean onSurface, boolean topUp, boolean cookBusy) {
        if (held < cfg.minHeldFoodUnits) {
            return true;
        }
        if (held >= cfg.minFoodUnits || cookBusy || covered(held, rawLeftOut, cfg)) {
            return false;
        }
        return topUp || onSurface;
    }

    // the soft top-up has nothing left to do: the hole under the full amount is small and the raw meat in the bag, cooked, fills
    // it. one raw chicken is 2 units raw and 6 cooked, and `held` counts it at 6 only while a smoker stands nearby (a cook is
    // feasible then), so picking the smoker up or putting it down moves the sum by 4. a top-up that starts and stops on 4 units
    // the bag already holds is pure churn. the cap is the reason the raw valuation exists at all:
    // ten raw porkchop is a 50 unit gap, and that one still gets a real hunt. under the floor never counts, that rule is not
    // ours to soften. `rawLeftOut` is 0 while a cook is feasible (held has the gap in it already, adding it again would pay the
    // same chicken twice)
    public static boolean covered(int held, int rawLeftOut, OverworldConfig cfg) {
        return held >= cfg.minHeldFoodUnits && held < cfg.minFoodUnits && cfg.minFoodUnits - held <= SMALL_GAP
                && held + rawLeftOut >= cfg.minFoodUnits;
    }

    // is a cook or a furnace load in the way of the soft top-up. a cook with its station is (it owns the station, the meat in it
    // is food). a furnace screen open or a smelt task that had one a moment ago is too, that is somebody's load with the meat
    // hidden in the screen, except when the food need led last tick: nothing was ahead of it, so the screen is the top-up's own
    // (it walked to the smoker for the cooked chicken), and counting it would hand the head to the iron the moment it opened,
    // again and again. same idea as IronPhase.otherLoadInFlight: whoever is running owns the screen
    public static boolean cookBusy(boolean cookStation, boolean loadInFlight, boolean ledLastTick) {
        return cookStation || (loadInFlight && !ledLastTick);
    }

    // food in the slots of a furnace or smoker screen we have open. a load is the bag emptying into the station a stack at a
    // time, and for those ticks the count dipped under the floor and sent the bot for cows with the smoker half full. once the
    // job is recorded the pending sum has it, so it is not counted twice. only THIS station's job counts for that: smoker A
    // being recorded says nothing about the meat going into furnace B, and the old any-job rule dropped B's meat from the sum
    // (the bag read as short and the top-up went hunting with a full furnace). `screenAt` null = we do not know which block the
    // screen is, then any food job still hides it (the old answer, the safe one for double counting)
    public static int inStation(int stationUnits, List<RunState.FurnaceJob> jobs, RunState.Pos screenAt, String dimension) {
        if (stationUnits <= 0) {
            return 0;
        }
        for (RunState.FurnaceJob job : jobs) {
            if (job.count * job.unitsEach <= 0) {
                continue;
            }
            if (screenAt == null || (job.pos.equals(screenAt) && job.dimension.equals(dimension))) {
                return 0;
            }
        }
        return stationUnits;
    }

    // a top-up that started on the soft rule keeps going until the full amount, it must not flip every tick as the bot walks
    // up and down or the count wobbles at the line. under the floor it was forced anyway, that does not start one. one that
    // is covered ends (covered): leads() already says no for it, and a latch left on would wake up off the surface the next
    // time the raw meat is eaten
    public static boolean nextTopUp(boolean topUp, int held, int rawLeftOut, OverworldConfig cfg, boolean leads) {
        if (held >= cfg.minFoodUnits || covered(held, rawLeftOut, cfg)) {
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
