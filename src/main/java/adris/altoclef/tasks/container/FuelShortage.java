package adris.altoclef.tasks.container;

// "we are out of fuel" for the smelt tasks, but only once it has been true for a moment. the tick after a fuel click the bag, the
// cursor and the cached furnace slots disagree for a bit (seen twice in the live log: coal on its way into the slot read as no
// fuel anywhere, and the bot left the open smoker for a coal trip with the fuel half in). the real thing stays true, the
// blip does not. pure so the debounce can be tested without a game
final class FuelShortage {
    // half a second, longer than the two clicks of a move at the default 0.2 s delay
    static final long HOLD_TICKS = 10;
    // asked less often than this and it is a new question, not the same shortage still going
    private static final long GAP_TICKS = 40;

    private long since = -1;
    private long last = -1;

    // feed it whenever the question is asked. true once the shortage has held for HOLD_TICKS
    boolean confirmed(boolean lacking, long now) {
        boolean stale = last >= 0 && now - last > GAP_TICKS;
        last = now;
        if (!lacking || stale) {
            since = -1;
        }
        if (!lacking) {
            return false;
        }
        if (since < 0) {
            since = now;
        }
        return now - since >= HOLD_TICKS;
    }

    void reset() {
        since = -1;
        last = -1;
    }

    // smelts of fuel a station with `input` items in it is still short of: its own slot, what is lit and the progress on the
    // item in hand all count, same sum the smelt tasks use for fuelNeeded
    static double missing(int input, double lit, double progress, double slotFuel) {
        return input - (lit + progress + slotFuel);
    }

    // out of fuel with the screen open: it is short and nothing in the bag (usable, see FuelPolicy) makes up the difference.
    // then the bot closes up and fetches it instead of standing at the gui saying "Waiting..." for ever
    static boolean dry(int input, double lit, double progress, double slotFuel, double bagFuel) {
        double missing = missing(input, lit, progress, slotFuel);
        return input > 0 && missing > 0 && bagFuel < missing;
    }
}
