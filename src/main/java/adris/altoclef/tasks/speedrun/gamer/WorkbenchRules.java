package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

// every decision about the tables, furnaces and smokers this run put down, with no game in sight (Workbenches is the world half
// that feeds these and acts on what they say). the old rules lived in four places and each priced the walk back differently
// (a path cost of 20, a stretched one of 60, an infinite one, a hand picked 75), which is how a table five blocks away up a
// shaft got forgotten and another one crafted next to it. now there is one distance, NEAR, a straight line in all three axes
public final class WorkbenchRules {
    // "ours is near enough to reuse" and "we are still in its area" are the same number on purpose. height counts: a table 15
    // across and 15 up is 21.2 away and not near, 10 / 10 / 10 is 17.3 and is
    public static final double NEAR = WalkCost.STATION_NEAR;
    // past this (straight line) a station is a lost cause and gets forgotten
    public static final double FORGET_DISTANCE = WalkCost.STATION_FORGET;
    // the next needs of the plan that count as "soon", on top of the current one
    public static final int LOOKAHEAD = 3;
    // outside NEAR this long without a break (5 s) and the station is picked up even if the plan still wants it. a short step
    // outside, to a tree or around a corner, is not a trip back
    public static final long OUTSIDE_TICKS = 100;
    // a block that went down a moment ago gets a second before anything may take it back (the hook that records it, the first
    // tick of the craft)
    public static final long PLACE_GUARD_TICKS = 20;
    // the screen has to have been shut this long. CraftInTableTask closes and reopens it between the steps of one chain
    public static final long SETTLE_TICKS = 20;
    // after a pickup that could not finish, the next one waits this long
    public static final long RETRY_GAP_TICKS = 100;
    // pickups that ran out of time (or could not even start) before the entry is written off
    public static final int MAX_TRIES = 3;
    // one try gets this long (30 s) to break the block. it was the tablePickupSeconds config knob, nobody ever turned it
    public static final long PICKUP_TRY_TICKS = 600;
    // being in another dimension has to last this long before we call the station left behind (a loading screen is not leaving)
    public static final long DIMENSION_TICKS = 20;
    // a pickup nobody ran for this long is not in flight any more: it stops vetoing the placers, and goes back to standing
    public static final long DRIVE_GRACE_TICKS = 100;
    // ...unless the block is already down and only the drop is left, that one gets a good while longer before it is given up
    public static final long DROP_GIVE_UP_TICKS = 1200;
    // a station that comes back into NEAR after it was out of it only counts for the planner again once we are well inside
    public static final double RETURN_SHARE = 0.75;
    // furnace and smoker get a smaller latch than the table: once out of NEAR they only count for the planner again this many
    // blocks inside it, so a bot dithering over the line flips the flag once and not every step, and a smoker at 20 blocks is a
    // smoker whichever way we got there. it only ever reaches inward: the planner may say "not held" where the container task would
    // walk to ours (a few cobble too many), but never "held" where the task would make a second one the plan did not budget for
    public static final double LATCH_BAND = 1.0;
    // a table this far away (a straight line) is not worth the walk back when we can craft another one: it is forgotten with a
    // log line instead. a furnace or smoker keeps the long trip up to FORGET_DISTANCE, it cost stone and a table to make
    public static final double FAR_TABLE_DISTANCE = 48.0;
    // a block that shows up further away than we can place from did not come from us (another player, a chunk update)
    public static final double PLACE_REACH = 8.0;
    // "never happened" for the tick stamps (game time starts at 0, so -1 is safely before everything)
    public static final long NEVER = -1;
    // a smelt task had its screen in hand this recently (AsyncSmelting.working) counts as a load in flight
    public static final long LOAD_GRACE_TICKS = 20;

    private WorkbenchRules() {
    }

    // ---- where the next station comes from (rule 1)
    // ours standing within NEAR, else a world one within NEAR, else the one in the bag, else craft. the container tasks are alto
    // and can't call this class, so the decision itself is util/helpers/StationChoice (fed by StationHook, which Workbenches
    // answers), and the NEAR test itself is in WalkCost

    // blocks in the world (not ours) can pop into existence near us too, so "near" is the only evidence we have of who placed
    // it. measured from the player to the middle of the block
    public static boolean placedByUs(double px, double py, double pz, RunState.Pos block) {
        double dx = block.x + 0.5 - px;
        double dy = block.y + 0.5 - py;
        double dz = block.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz <= PLACE_REACH * PLACE_REACH;
    }

    // the planner counts a station of ours as held inside NEAR, and once it has been out of it only inside a share of NEAR, or the
    // plan flips every time we cross the line (a log trip up, a cobble trip down)
    public static double returnRadius(boolean wasFar) {
        return wasFar ? NEAR * RETURN_SHARE : NEAR;
    }

    // the same latch for a furnace or smoker, a band instead of a share: `held` is last look's answer (null before the first one,
    // where the plain line is the only fair test). held stays held to the line, and not held needs NEAR - band to come back
    public static double bandRadius(Boolean held) {
        return held == null || held ? NEAR : NEAR - LATCH_BAND;
    }

    // a table past FAR_TABLE_DISTANCE that the bag can make again: 4 planks, or a log to make them from
    public static boolean canRecraftTable(int planks, int logs) {
        return planks >= 4 || logs >= 1;
    }

    // ---- what needs which station

    // some needs craft inside themselves (the planner never sees those crafts, they are not KitNeeds), so a plan with no craft in
    // it can still want a table. food: hoe, wheat, bread. anything that mines with a tool we do not hold yet: the mining task
    // crafts the pick itself at the table. `holdsPick` / `holdsStonePick` are "any pickaxe of at least that tier in the bag"
    public static boolean needCraftsInside(String need, boolean holdsPick, boolean holdsStonePick) {
        if (need == null) {
            return false;
        }
        return switch (need) {
            case KitNeed.FOOD -> true;
            // wooden pick tier: stone and coal
            case KitPlanner.COBBLE, "coal" -> !holdsPick;
            // the ore needs a stone pick, and that is made at a table
            case "iron_ingot" -> !holdsStonePick;
            default -> false;
        };
    }

    // does this one need use that station: a craft needs a table, a smelt a furnace, a cook a smoker or a furnace (food cooks what
    // it hunts, so it wants the smoker too)
    public static boolean needsStation(Kind kind, String need, boolean holdsPick, boolean holdsStonePick) {
        if (need == null) {
            return false;
        }
        return switch (kind) {
            case TABLE -> KitNeed.isCraftName(need) || needCraftsInside(need, holdsPick, holdsStonePick);
            case FURNACE -> "iron_ingot".equals(need) || KitNeed.COOK_FURNACE.equals(need);
            case SMOKER -> KitNeed.COOK_SMOKER.equals(need) || KitNeed.FOOD.equals(need);
        };
    }

    // the current need or one of the LOOKAHEAD after it wants the station. `plan` is the phase's need list in order, names only
    public static boolean neededSoon(Kind kind, List<String> plan, boolean holdsPick, boolean holdsStonePick) {
        int last = Math.min(plan.size(), 1 + LOOKAHEAD);
        for (int i = 0; i < last; i++) {
            if (needsStation(kind, plan.get(i), holdsPick, holdsStonePick)) {
                return true;
            }
        }
        return false;
    }

    // ---- rule 2 and 4: keep, pick up, forget

    // what the world half saw this tick. distance is the straight line from us to the middle of the block
    // `canRecraft`: the bag can make this kind again right now (only a table asks, see FAR_TABLE_DISTANCE)
    public record Look(long now, double distance, boolean sameDimension, boolean blockGone, boolean idle, boolean holdsStuff, boolean jobHere,
                       boolean neededSoon, boolean canBreak, long pickupLimitTicks, boolean canRecraft) {
        public Look(long now, double distance, boolean sameDimension, boolean blockGone, boolean idle, boolean holdsStuff, boolean jobHere,
                    boolean neededSoon, boolean canBreak, long pickupLimitTicks) {
            this(now, distance, sameDimension, blockGone, idle, holdsStuff, jobHere, neededSoon, canBreak, pickupLimitTicks, false);
        }
    }

    public enum Call {
        // leave it where it is
        KEEP,
        // a job or our items are in it, it is never taken
        BUSY,
        // it should come down but something short holds it (a screen is open, it was just placed, the retry gap)
        WAIT,
        // start taking it back now
        PICK_UP,
        // it cannot be broken from here, that is a failed try
        UNREACHABLE,
        // a pickup is running, keep driving it
        CONTINUE,
        // a pickup is running and the station turned out to hold our stuff
        ABORT_BUSY,
        // the block pickup ran out of its time
        TIMED_OUT,
        // the block is down and the drop never made it into the bag
        DROP_LOST,
        FORGET_GONE,
        FORGET_TOO_FAR,
        // a table out past FAR_TABLE_DISTANCE and we can craft another
        FORGET_FAR_TABLE,
        FORGET_LEFT_DIMENSION,
        // busy in a dimension we left: it stays registered, a visit when we are back takes it down
        ELSEWHERE
    }

    // one station, one tick. moves the bookkeeping on the bench (the outside clock, the dimension clock, BUSY / STANDING) and
    // says what to do about it. the pickup bookkeeping (start, finish, a failed try) is below
    public static Call decide(Bench b, Look in) {
        long now = in.now();
        if (!in.sameDimension()) {
            if (b.otherDimensionSince == NEVER) {
                b.otherDimensionSince = now;
            }
            // a furnace or smoker with something of ours in it stays registered for the visit that takes it down when we are back
            // (the job is what brings us, the entry only has to still be there). the state is last seen's, or a recorded job in
            // that dimension after a relog rebuilt every entry as standing. nothing here forgets it, the run ending does
            if (b.state == Bench.State.BUSY || in.jobHere()) {
                b.state = Bench.State.BUSY;
                return Call.ELSEWHERE;
            }
            // not forgotten at once: the dimension reads wrong for a tick or two around a portal and a loading screen
            return now - b.otherDimensionSince >= DIMENSION_TICKS ? Call.FORGET_LEFT_DIMENSION : Call.KEEP;
        }
        b.otherDimensionSince = NEVER;
        boolean pickingUp = b.state == Bench.State.PICKING_UP;
        // our own break makes the block go away, that is progress and not a forget
        if (in.blockGone() && !pickingUp) {
            return Call.FORGET_GONE;
        }
        // a station with a job in it is not forgotten by distance: the job is what brings us back, and the visit takes the station.
        // holdsStuff counts too, the state is last tick's (and a relog rebuilds every bench as STANDING)
        if (in.distance() > FORGET_DISTANCE && b.state != Bench.State.BUSY && !in.holdsStuff()) {
            return Call.FORGET_TOO_FAR;
        }
        if (pickingUp) {
            return decidePickup(b, in);
        }
        // the outside clock runs whatever the station is doing, so a job that finishes while we were away for a minute does not
        // get another five seconds of grace
        if (in.distance() <= NEAR) {
            b.outsideSince = NEVER;
        } else if (b.outsideSince == NEVER) {
            b.outsideSince = now;
        }
        // seen empty, or with a real job in it again: whatever was given up on is over
        if (!in.holdsStuff() || in.jobHere()) {
            b.clearGivenUp();
        }
        if (in.holdsStuff()) {
            b.state = Bench.State.BUSY;
            return Call.BUSY;
        }
        b.state = Bench.State.STANDING;
        // out past the far line for the usual five seconds with the wood to craft another: not worth the walk back, whatever the plan
        // wants (the planner no longer counts it as held out there, so it asks for the planks)
        if (b.kind == Kind.TABLE && in.distance() > FAR_TABLE_DISTANCE && in.canRecraft() && outsideLongEnough(b, now)) {
            return Call.FORGET_FAR_TABLE;
        }
        if (in.neededSoon() && !outsideLongEnough(b, now)) {
            return Call.KEEP;
        }
        return gates(b, in);
    }

    // a furnace or smoker of ours with our items in it and no job pointing at it (a load cut off before its job was recorded, a job
    // dropped as stale): nothing would ever visit it and a phase would end around it. it is adopted as a stranded job so the normal
    // collect visit empties it, and the empty-after-visit rule takes it down. only when nothing is working at it: a load in flight
    // records its own job a tick later
    public static boolean adoptable(Bench b, Look in) {
        if (b.kind == Kind.TABLE || b.state == Bench.State.PICKING_UP || !in.sameDimension() || in.blockGone()) {
            return false;
        }
        if (!in.holdsStuff() || in.jobHere() || !in.idle() || b.givenUp) {
            return false;
        }
        long now = in.now();
        if (now - b.placedTick < PLACE_GUARD_TICKS) {
            return false;
        }
        return b.lastUsedTick == NEVER || now - b.lastUsedTick >= SETTLE_TICKS;
    }

    private static Call decidePickup(Bench b, Look in) {
        long now = in.now();
        long running = now - b.pickupStart;
        if (b.broken) {
            // only the drop is left
            return running > DROP_GIVE_UP_TICKS ? Call.DROP_LOST : Call.CONTINUE;
        }
        // a table pickup that has not broken anything yet and is out past the far line, with the wood to make another (the bag got
        // some since it started): not worth the rest of the walk
        if (b.kind == Kind.TABLE && !in.blockGone() && in.distance() > FAR_TABLE_DISTANCE && in.canRecraft()) {
            return Call.FORGET_FAR_TABLE;
        }
        if (!in.blockGone() && in.jobHere()) {
            return Call.ABORT_BUSY;
        }
        // a block that is already gone is our break landing, the world half notices and moves on to the drop
        if (!in.blockGone() && running > in.pickupLimitTicks()) {
            return Call.TIMED_OUT;
        }
        return Call.CONTINUE;
    }

    // the things that only delay a pickup that is owed. none of them counts as a failed try
    private static Call gates(Bench b, Look in) {
        long now = in.now();
        if (!in.idle()) {
            return Call.WAIT;
        }
        if (now - b.placedTick < PLACE_GUARD_TICKS) {
            return Call.WAIT;
        }
        if (b.lastUsedTick != NEVER && now - b.lastUsedTick < SETTLE_TICKS) {
            return Call.WAIT;
        }
        if (b.retryAt != NEVER && now < b.retryAt) {
            return Call.WAIT;
        }
        return in.canBreak() ? Call.PICK_UP : Call.UNREACHABLE;
    }

    public static boolean outsideLongEnough(Bench b, long now) {
        return b.outsideSince != NEVER && now - b.outsideSince >= OUTSIDE_TICKS;
    }

    // plain words for why a pickup started, they go in the log
    public static String reason(Bench b, Look in) {
        if (!in.neededSoon()) {
            return "nothing in the next " + (LOOKAHEAD + 1) + " needs wants the " + b.kind.word();
        }
        return "we have been outside " + Math.round(NEAR) + " blocks of it for " + OUTSIDE_TICKS / 20 + " s";
    }

    // ---- pickup bookkeeping

    public static void beginPickup(Bench b, long now, int bagBefore, String why) {
        b.state = Bench.State.PICKING_UP;
        b.pickupStart = now;
        b.drivenTick = now;
        b.broken = false;
        b.bagBefore = bagBefore;
        b.why = why;
    }

    // the break phase is over (the block is down), what is left is the drop
    public static void blockBroken(Bench b, long now) {
        b.broken = true;
        // the drop gets its own clock
        b.pickupStart = now;
    }

    // a try that did not work out (timed out, or could not start). true = that was the last one, forget the station
    public static boolean failedTry(Bench b, long now) {
        b.tries++;
        b.state = Bench.State.STANDING;
        b.pickupStart = NEVER;
        b.broken = false;
        b.retryAt = now + RETRY_GAP_TICKS;
        return b.tries >= MAX_TRIES;
    }

    // the item is back once there is one more than when we began
    public static boolean pickupDone(boolean broken, int bagNow, int bagBefore) {
        return broken && bagNow > bagBefore;
    }

    // ---- rule 3: the visit that empties a station

    public enum Visit {
        // leave it, something is still cooking in it (or it is not ours to take)
        LEAVE,
        // empty and ours: it comes down now, before we leave it
        PICK_UP,
        // empty, ours, and we carry meat the free station can cook first. the pickup follows the cook
        COOK_THEN_PICK_UP
    }

    public static Visit afterVisit(int inputLeft, boolean ours, boolean otherJobHere, boolean cookHere) {
        if (inputLeft > 0 || !ours || otherJobHere) {
            return Visit.LEAVE;
        }
        return cookHere ? Visit.COOK_THEN_PICK_UP : Visit.PICK_UP;
    }

    // ---- cooking: the smoker wins

    // raw meat goes in a smoker of ours when one stands within NEAR or sits in the bag (walk to it, or put it down). twice as fast
    // as a furnace, and the furnace was busy with the iron anyway
    public static boolean cookInSmoker(boolean smokerStandingNear, boolean smokerInBag) {
        return smokerStandingNear || smokerInBag;
    }

    // the furnace a visit just emptied of iron only takes meat when no smoker of ours is within NEAR or in the bag. a smoker that was
    // just emptied is the smoker, it can always take the next batch. either way the emptied station comes down after (afterVisit)
    public static boolean emptiedStationMayCook(boolean visitedIsSmoker, boolean smokerStandingNear, boolean smokerInBag) {
        return visitedIsSmoker || !cookInSmoker(smokerStandingNear, smokerInBag);
    }

    // ---- rule 5: a pickup and a placement of the same kind never overlap

    // a pickup is in flight and somebody is still driving it
    public static boolean inFlight(Bench b, long now) {
        return b.state == Bench.State.PICKING_UP && b.drivenTick != NEVER && now - b.drivenTick <= DRIVE_GRACE_TICKS;
    }

    // no container task may place or craft a station of this kind while one of them is coming down: the block it would find
    // missing is the one we are breaking, and a new one would stand right where the old one was
    public static boolean placeVetoed(Collection<Bench> benches, Kind kind, long now) {
        for (Bench b : benches) {
            if (b.kind == kind && inFlight(b, now)) {
                return true;
            }
        }
        return false;
    }

    // ...and none may walk to the block that is coming down
    public static boolean targetVetoed(Collection<Bench> benches, RunState.Pos pos, long now) {
        for (Bench b : benches) {
            if (b.pos.equals(pos) && inFlight(b, now)) {
                return true;
            }
        }
        return false;
    }

    // a pickup nobody has run for a while while the block still stands goes back to standing, so a phase that never drives it
    // (or a run that was interrupted for good) cannot hold the veto forever. true when it changed
    public static boolean dropStaleDrive(Bench b, long now) {
        if (b.state != Bench.State.PICKING_UP || b.broken || b.drivenTick == NEVER || now - b.drivenTick <= DRIVE_GRACE_TICKS) {
            return false;
        }
        b.state = Bench.State.STANDING;
        b.pickupStart = NEVER;
        return true;
    }

    // ---- rule 6: a phase may end

    // nothing of ours is standing or coming down in this dimension. called with an empty plan (a phase that is out of needs):
    // an idle station left standing at that point is exactly the one that gets left behind. a busy one with a job is the job's
    // business (the phase waits for those on its own), a busy one with no job is an interrupted load that has not been adopted yet
    // (adoptable) and the phase waits for that. what stands in another dimension stays registered and does not hold this one
    public static boolean phaseMayEnd(Collection<Bench> benches, String dimension, Predicate<Bench> hasJob) {
        for (Bench b : benches) {
            if (!b.dimension.equals(dimension)) {
                continue;
            }
            // (a load the stale rule gave up on is not adopted either, so it can't hold the phase: it was never worth a walk)
            if (b.state == Bench.State.STANDING || b.state == Bench.State.PICKING_UP
                    || (b.state == Bench.State.BUSY && !b.givenUp && !hasJob.test(b))) {
                return false;
            }
        }
        return true;
    }

    // the ones phaseMayEnd lets go on purpose: busy in this dimension, no job, and the stale rule gave up on them. they stay standing
    // with our items in them, the world half says so in the log
    public static List<Bench> leftGivenUp(Collection<Bench> benches, String dimension, Predicate<Bench> hasJob) {
        List<Bench> out = new java.util.ArrayList<>();
        for (Bench b : benches) {
            if (b.dimension.equals(dimension) && b.state == Bench.State.BUSY && b.givenUp && !hasJob.test(b)) {
                out.add(b);
            }
        }
        return out;
    }

    // ---- loads

    // a pickup preempts the kit task, and a furnace load is a handful of clicks: ore, then fuel, then the job is recorded. a
    // pickup that started between the ore and the fuel closed the screen on a furnace holding 37 raw iron and no job. so while
    // some container screen is open, or a smelt task touched one a moment ago, no pickup STARTS
    public static boolean loadInFlight(boolean containerScreenOpen, long lastSmeltWork, long now) {
        if (containerScreenOpen) {
            return true;
        }
        return lastSmeltWork >= 0 && now >= lastSmeltWork && now - lastSmeltWork <= LOAD_GRACE_TICKS;
    }
}
