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
    // holes we dig for room before we try walking somewhere else. a hole that didn't help, it's the wall's fault, not ours
    static final int MAX_CARVES = 3;

    enum Phase {
        // choose a spot
        PICK,
        // nothing clickable around: mine a block out of the wall beside us and put the station in the hole, like a player
        // in a tunnel would
        CARVE,
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
    private int carves;
    private int phaseTicks;
    private int landedTicks;
    // why the player way ran dry, for the log line when the old placer takes over
    private String fallbackWhy = "";

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

    int carves() {
        return carves;
    }

    String fallbackWhy() {
        return fallbackWhy;
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

    // nothing in reach is clickable. dig out some room first, then walk, then the old placer. this used to go straight to
    // the walk, and a shaft has nowhere to walk to, so it went straight to the old placer, which pillared
    void noSpot() {
        if (carves < MAX_CARVES) {
            enter(Phase.CARVE);
        } else {
            giveUpOnThisPatch("nothing clickable and " + carves + " holes dug didn't help");
        }
    }

    // nothing around us that is worth (or safe) to mine out
    void noCarve() {
        giveUpOnThisPatch("nothing clickable and nothing safe to mine out for room");
    }

    // the hole is dug (or the digging timed out, same thing: look at what we've got now). counts either way so a wall that
    // won't give can't keep us here
    void carveDone() {
        if (phase == Phase.CARVE) {
            carves++;
            enter(Phase.PICK);
        }
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
            giveUpOnThisPatch(TRIES_BEFORE_RELOCATE + " clicks lost on this patch");
        } else {
            enter(Phase.PICK);
        }
    }

    private void giveUpOnThisPatch(String why) {
        if (relocations < MAX_RELOCATIONS) {
            relocations++;
            tries = 0;
            enter(Phase.RELOCATE);
        } else {
            fallbackWhy = why + ", and already moved " + relocations + " times";
            enter(Phase.FALLBACK);
        }
    }

    // nowhere better to stand within reach of here. asking again would find the same nothing
    void noStandpoint() {
        fallbackWhy = "nowhere better to stand within " + StationSpots.MAX_MOVE + " blocks";
        enter(Phase.FALLBACK);
    }

    // we are at the new patch (or the walk timed out, same thing: look again from where we are)
    void arrived() {
        if (phase == Phase.RELOCATE) {
            tries = 0;
            // new walls, new holes to try
            carves = 0;
            enter(Phase.PICK);
        }
    }
}
