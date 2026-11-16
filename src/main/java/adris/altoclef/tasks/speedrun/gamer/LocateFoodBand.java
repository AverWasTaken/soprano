package adris.altoclef.tasks.speedrun.gamer;

// LOCATE's soft food band. the hard floor (FoodFloor, under 24) only fires on a bag that is already bad, and the walk to the
// stronghold is the last surface time before the End: a bot that turns up at the portal on 40 units eats it all in the first few
// landings. so with prey right there and the bag under the End's want, it grabs some on the way. soft on purpose: only with an
// animal near, never with an eye out, capped, and once it comes back short it stays off for the rest of the visit, the
// stronghold is the job. pure: numbers in, a log line out
public final class LocateFoodBand {
    // a top-up that hasn't got there in this long isn't going to, the eyes get the time back
    static final long CAP_TICKS = 240 * 20;
    // a batch in the smoker holds every exit (nothing in LOCATE ever comes back for one we walk off from), but not for ever
    static final long COOK_GRACE_TICKS = 120 * 20;

    private boolean active;
    // ended short this visit, no second go
    private boolean spent;
    private long since;

    public void reset() {
        active = false;
        spent = false;
    }

    // a watchdog retry of the same visit: off, but a band that already came back short stays spent
    public void standDown() {
        active = false;
    }

    public boolean active() {
        return active;
    }

    // one look per tick, in food units: held is the floor's count (FoodFloor goes by it), bag is the food really in the bag
    // (FoodTripRules.units: no meat sitting in a smoker screen, no batch left at some furnace we'll never see again), which is what
    // want and stop are judged on. stop = where the floor's trip ends too. cooking = our hunt has meat in the smoker right now.
    // returns a line when it starts or ends, null otherwise
    public String tick(int held, int bag, int floor, int want, int stop, boolean prey, boolean eyeOut, boolean cooking, long now) {
        if (!active) {
            if (spent || eyeOut || !prey || held < floor || bag >= want) {
                return null;
            }
            active = true;
            since = now;
            return "locate: prey near and only " + bag + " food, topping up toward " + stop + " (soft, want " + want + ")";
        }
        if (cooking && now >= since && now - since < CAP_TICKS + COOK_GRACE_TICKS) {
            return null;
        }
        if (bag >= stop) {
            active = false;
            return "locate: food top-up done at " + bag;
        }
        String why = endedShort(prey, now);
        if (why == null) {
            return null;
        }
        active = false;
        spent = true;
        return "locate: food top-up over at " + bag + ", " + why + ", not again this visit";
    }

    // the hard floor took over (held went under it): that trip runs to the stop anyway, so this one just steps aside, not spent
    public String drop(int held) {
        if (!active) {
            return null;
        }
        active = false;
        return "locate: food top-up handed to the food floor at " + held;
    }

    private String endedShort(boolean prey, long now) {
        if (!prey) {
            return "no prey near";
        }
        // (a clock that went backwards ends it too, rather than holding it for ever)
        if (now - since >= CAP_TICKS || now < since) {
            return CAP_TICKS / 20 + " s cap";
        }
        return null;
    }
}
