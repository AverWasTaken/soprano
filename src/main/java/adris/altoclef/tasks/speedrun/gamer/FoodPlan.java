package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;

import java.util.List;

// how much food we hold and what every gate makes of it, built once per tick (GamerContext.food). every gate used to do its own sum
// and they drifted: the End gate read the bare bag, two phases kept their own copy of the latch, and two configs both call their
// line minFoodUnits. held is the one valuation: the bag and the furnace screen we have open (raw meat at its cooked value), plus
// what a smoker is cooking for us, minus the cooked value of raw meat nothing can cook (it gets eaten raw, 3 a porkchop and not 8).
// what can cook depends on the phase: GATHER, IRON and PORTAL run the cook, so a standing smoker with fuel and three raw meat
// makes dinner, from the nether on nothing does and the meat counts raw, a pending batch or not (GamerPhase.cooks).
// counted is the same without that correction and is only for "did we get closer", held jumps by a chicken whenever a smoker comes
// or goes. the lines are named so the two minFoodUnits cannot be mixed up: overworldMinimum is 70 (OverworldConfig), endFloor is 24
// (EndConfig), floor is OverworldConfig.minHeldFoodUnits. pure: facts and configs in, numbers out
public final class FoodPlan {
    // a soft top-up shortfall this small is not worth a trip when the raw meat in the bag would close it. 10 is the most a smoker
    // standing nearby can swing the sum by: under CookGate.MIN_RAW the cook is only feasible with a station already there, so that
    // is MIN_RAW - 1 = two raw beef or porkchop at 5 apiece. a smaller cap left two beef churning like the chicken did
    public static final int SMALL_GAP = 10;
    // the End hunt aims this far over its floor, or it would stop on the very unit the gate starts at and flip with every bite
    public static final int END_MARGIN = 8;

    private final int bag;
    private final int pending;
    private final int rawLeftOut;
    private final int junk;
    private final int station;
    private final int skipped;
    private final int floor;
    private final int minimum;
    private final int target;
    private final int endFloor;

    FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, OverworldConfig ow, EndConfig end) {
        this.bag = bag;
        this.pending = pending;
        this.rawLeftOut = rawLeftOut;
        this.junk = junk;
        this.station = station;
        this.skipped = skipped;
        this.floor = ow.minHeldFoodUnits;
        this.minimum = ow.minFoodUnits;
        this.target = ow.targetFoodUnits;
        this.endFloor = end.minFoodUnits;
    }

    // the phase is what says whether a cook can happen at all, so there is no way to ask without one
    public static FoodPlan of(GamerFacts f, GamerConfig cfg, GamerPhase phase) {
        return of(f, cfg.overworld, cfg.end, phase.cooks());
    }

    // `cooksHere` = the phase runs the cook (GamerPhase.cooks)
    public static FoodPlan of(GamerFacts f, OverworldConfig ow, EndConfig end, boolean cooksHere) {
        return new FoodPlan(f.foodUnits(), f.pendingFoodUnits(), rawLeftOut(f, ow, end.beds, cooksHere), f.junkFoodUnits(),
                f.stationFoodUnits(), f.stationFoodSkipped(), ow, end);
    }

    // for the planners' short entry points, which know the bed count and nothing else of the End. the End lines are the config
    // defaults there, so a caller that reads endFloor() wants the other overload. the planners behind them are the kit's, which
    // run in the cooking phases
    public static FoodPlan ofBeds(GamerFacts f, OverworldConfig ow, int endBeds) {
        EndConfig end = new EndConfig();
        end.beds = endBeds;
        return of(f, ow, end, true);
    }

    // the cooked value of the raw meat that held does not count: all of CookGate.rawGap while no cook can happen, none while one
    // can (the bag figure has it in already, adding it again would pay the same chicken twice). in a phase that never cooks all of it
    // stays out, whatever stands there or cooks there: nobody loads a station and nothing collects one after PORTAL, so a batch
    // still pending is not food we will get and the raw meat behind it never reaches a smoker (CollectFoodTask will not load a
    // second one while the first is out). a standing smoker and fuel are only a dream there
    private static int rawLeftOut(GamerFacts f, OverworldConfig ow, int endBeds, boolean cooksHere) {
        int gap = CookGate.rawGap(f);
        if (gap <= 0) {
            return 0;
        }
        return cooksHere && CookGate.cookFeasible(f, ow, endBeds) ? 0 : gap;
    }

    // ---- the numbers

    public int held() {
        return bag + pending - rawLeftOut;
    }

    public int counted() {
        return bag + pending;
    }

    // what facts.foodUnits() said: the bag, raw meat at the cooked value, and the open screen's share
    public int bag() {
        return bag;
    }

    public int pending() {
        return pending;
    }

    public int rawLeftOut() {
        return rawLeftOut;
    }

    // ---- the lines

    // OverworldConfig.minHeldFoodUnits: under it food always goes first
    public int floor() {
        return floor;
    }

    // OverworldConfig.minFoodUnits: the kit's food need, the portal gate, where a top-up ends. not the End's line
    public int overworldMinimum() {
        return minimum;
    }

    // OverworldConfig.targetFoodUnits: the stock-up at the end of the kit
    public int target() {
        return target;
    }

    // EndConfig.minFoodUnits: the least we walk into the End with. not the overworld's line
    public int endFloor() {
        return endFloor;
    }

    // a stock-up on top of the target (SmeltFiller's smeltExtras), in the same units
    public int stockUp(int extra) {
        return target + extra;
    }

    // ---- what to ask the food task for

    // CollectFoodTask counts anything edible but held only what we would eat, so with a bag of junk it thinks it is done while
    // the gate stays short and the bot wanders. ask it for the junk on top
    public int collect(int units) {
        return units + junk;
    }

    // the End hunt: the floor and a margin, plus the junk
    public int endCollect() {
        return collect(endFloor + END_MARGIN);
    }

    // ---- the verdicts

    public boolean shortOf(int units) {
        return held() < units;
    }

    public boolean shortOfMinimum() {
        return shortOf(minimum);
    }

    public boolean shortOfEndFloor() {
        return shortOf(endFloor);
    }

    // which side of the two overworld lines held is on (0 under the floor, 1 between, 2 at the minimum or over), for the log line
    public int band() {
        return held() < floor ? 0 : held() < minimum ? 1 : 2;
    }

    // the soft top-up has nothing left to do: the hole under the minimum is small and the raw meat in the bag, cooked, fills it. one
    // raw chicken is 2 units raw and 6 cooked, and held counts it at 6 only while a smoker stands nearby (a cook is feasible then),
    // so picking the smoker up or putting it down moves the sum by 4. a top-up that starts and stops on 4 units the bag already
    // holds is pure churn. the cap is the reason the raw valuation exists at all: ten raw porkchop is a 50 unit gap, and that one
    // still gets a real hunt. under the floor never counts, that rule is not ours to soften
    public boolean covered() {
        int held = held();
        return held >= floor && held < minimum && minimum - held <= SMALL_GAP && held + rawLeftOut >= minimum;
    }

    // does the food need go first in the IRON phase. always below the floor, otherwise only where a top-up is cheap, which is the
    // surface (a cook or a craft being next in a mine is not a cheap moment, the hunt is a climb either way), and once one starts
    // it carries on to the minimum. `topUp` is one already under way (nextTopUp), `cookBusy` a cook that picked its station and is
    // still loading it or somebody's furnace screen (FoodGate.cookBusy): the soft top-up waits for that, the meat in the station
    // is food too
    public boolean leads(boolean onSurface, boolean topUp, boolean cookBusy) {
        int held = held();
        if (held < floor) {
            return true;
        }
        if (held >= minimum || cookBusy || covered()) {
            return false;
        }
        return topUp || onSurface;
    }

    // a top-up that started on the soft rule keeps going until the minimum, it must not flip every tick as the bot walks up and
    // down or the count wobbles at the line. under the floor it was forced anyway, that does not start one. one that is covered
    // ends: leads() already says no for it, and a latch left on would wake up off the surface the next time the raw meat is eaten
    public boolean nextTopUp(boolean topUp, boolean leads) {
        int held = held();
        if (held >= minimum || covered()) {
            return false;
        }
        return topUp || (leads && held >= floor);
    }

    // the food floor of the phases with no food need of their own (FoodFloor): starts under the floor and carries on to the
    // minimum, flipping at the line would stop the hunt halfway. no covered() here on purpose, those phases never run the cook,
    // so a hole is not closed by raw meat that only counts once it is cooked. (held is phase aware for the same reason: no raw
    // meat at the cooked value in a phase that never cooks, covered() just stays out of it)
    public boolean floorNext(boolean active) {
        if (held() >= minimum) {
            return false;
        }
        return active || held() < floor;
    }

    // the line KitRunner logs when the food need shows up in or drops out of the plan. `units` is what the need asks for (the
    // minimum when there is none). the numbers are the ones held is made of, so a flip is readable straight off two lines
    public String line(boolean on, int units, String running) {
        return "food: need " + (on ? "on" : "off") + ", held " + held() + " of " + units + " (station " + station + ", pending " + pending
                + ", raw left out " + rawLeftOut + "), running " + (running == null ? "nothing" : running)
                + (skipped > 0 ? ", " + skipped + " was already in the open screen, not counted" : "");
    }

    // ---- the furnace screen we have open (what facts.foodUnits() adds to the bag)

    // food in the slots of a furnace or smoker screen we have open. a load is the bag emptying into the station a stack at a time,
    // and for those ticks the count dipped under the floor and sent the bot for cows with the smoker half full. once the job is
    // recorded the pending sum has it, so it is not counted twice. only THIS station's job counts for that: smoker A being
    // recorded says nothing about the meat going into furnace B, and the any-job rule dropped B's meat from the sum (the bag read
    // as short and the top-up went hunting with a full furnace). `screenAt` null = we do not know which block the screen is, then
    // any food job still hides it (the safe answer for double counting)
    public static int inStation(int stationUnits, List<RunState.FurnaceJob> jobs, RunState.Pos screenAt, String dimension) {
        return inStation(stationUnits, jobs, screenAt, dimension, 0);
    }

    // `leftover` is what the station already held when the food task opened it (see leftover below), 0 for anybody else's screen.
    // that part is not food we have, it is the reason the task walked up, and counting it ended the need that opened the screen
    public static int inStation(int stationUnits, List<RunState.FurnaceJob> jobs, RunState.Pos screenAt, String dimension, int leftover) {
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
        return Math.max(0, stationUnits - Math.max(0, leftover));
    }

    // the food in the slots of a screen the food task opened that is not the task's own work. the first look (`before` -1) is what
    // was there, after that it only goes down: the task takes the output out and the bag gets it, and what it puts in on top is
    // the bag emptying into the station, the dip inStation exists to hide. `synced` = the slots have arrived, an empty look at a
    // screen a tick after it opened would put the baseline at 0 and the cooked output under it would count after all. -1 = nothing
    // to subtract (no screen, not the food task's, slots not here yet)
    public static int leftover(int before, int stationUnits, boolean synced, boolean foodTaskRunning) {
        if (!synced || !foodTaskRunning) {
            return -1;
        }
        return before < 0 ? stationUnits : Math.min(before, stationUnits);
    }
}
