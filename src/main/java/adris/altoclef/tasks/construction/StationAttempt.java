package adris.altoclef.tasks.construction;

// the bookkeeping of PlaceStationTask with the game taken out: which step we are in, how many tries have gone wrong,
// how many times we gave up on the neighbourhood. the old task had its fail count on a per-spot object that got thrown
// away with the spot, so "fail 4 times, try something else" never got to 4. pure so it tests without a world
final class StationAttempt {
    // a click that has not made a block appear by now is not going to
    static final int VERIFY_TICKS = 10;
    // the client puts the block there the moment we click, the server can take it back a few ticks later. it has to stay
    // this long before we believe it
    static final int SETTLE_TICKS = 3;
    // aiming is a few ticks (equip, turn, wait for the crosshair). more than this and the spot is not clickable
    static final int AIM_TICKS = 20;
    // tries on one patch of floor before we go somewhere else
    static final int TRIES_BEFORE_RELOCATE = 3;
    static final int MAX_RELOCATIONS = 2;

    enum Phase {
        // choose a spot
        PICK,
        // equip, turn, wait for the crosshair to land on the face, click
        AIM,
        // clicked, waiting for the block to show up and stay
        VERIFY,
        // walking to a better patch of floor
        RELOCATE,
        // the player way is out of ideas, let PlaceBlockNearbyTask have it
        FALLBACK,
        DONE
    }

    private Phase phase = Phase.PICK;
    private int tries;
    private int relocations;
    private int phaseTicks;
    private int landedTicks;

    Phase phase() {
        return phase;
    }

    int tries() {
        return tries;
    }

    int relocations() {
        return relocations;
    }

    int phaseTicks() {
        return phaseTicks;
    }

    // once per task tick
    void tick() {
        phaseTicks++;
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        landedTicks = 0;
    }

    void picked() {
        enter(Phase.AIM);
    }

    // nothing in reach is clickable
    void noSpot() {
        giveUpOnThisPatch();
    }

    boolean aimTimedOut() {
        return phase == Phase.AIM && phaseTicks >= AIM_TICKS;
    }

    // the click went out
    void clicked() {
        enter(Phase.VERIFY);
    }

    // one tick of watching the cell. the block has to stay SETTLE_TICKS, a flicker resets it
    void verify(boolean blockThere) {
        if (phase != Phase.VERIFY) {
            return;
        }
        landedTicks = blockThere ? landedTicks + 1 : 0;
        if (landedTicks >= SETTLE_TICKS) {
            enter(Phase.DONE);
        } else if (phaseTicks >= VERIFY_TICKS) {
            failed();
        }
    }

    // this try is lost (no hit, no block, click refused). the caller bans the spot
    void failed() {
        tries++;
        if (tries >= TRIES_BEFORE_RELOCATE) {
            giveUpOnThisPatch();
        } else {
            enter(Phase.PICK);
        }
    }

    private void giveUpOnThisPatch() {
        if (relocations < MAX_RELOCATIONS) {
            relocations++;
            tries = 0;
            enter(Phase.RELOCATE);
        } else {
            enter(Phase.FALLBACK);
        }
    }

    // nowhere better to stand within reach of here. asking again would find the same nothing
    void noStandpoint() {
        enter(Phase.FALLBACK);
    }

    // we are at the new patch (or the walk timed out, same thing: look again from where we are)
    void arrived() {
        if (phase == Phase.RELOCATE) {
            tries = 0;
            enter(Phase.PICK);
        }
    }
}
