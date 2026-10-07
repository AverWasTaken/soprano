package adris.altoclef.util.helpers;

// the pure half of "what do we do about the blazes". no world, just numbers, same deal as CombatRules.
//
// the bug this exists for: the rods task had exactly two moods, "chase the blaze it likes" and "stand at the spawner doing
// nothing". blazes over lava or too high were invisible to it, so a bot with five fireballs in the face was camping
public final class BlazeFightRules {

    // a blaze this close is a threat whatever it is doing, it will bite
    public static final double MELEE_RANGE = 3;
    // past this a blaze is background noise. blazes shoot from further, but nothing they throw from here lands
    public static final double SIGHT_RANGE = 20;
    // above the floor, in blocks, a blaze can still be hit by a bot standing under it
    public static final double MAX_REACHABLE_HEIGHT = 3.5;
    // blazes further than this are not worth the walk, the ones that matter come to us
    public static final double MAX_CHASE_DISTANCE = 32;

    // at or below this with a blaze looking at us, leave
    public static final float RETREAT_HEALTH = 12;
    // at or below this leave whether or not anything is looking, we are one volley from the respawn screen
    public static final float PANIC_HEALTH = 8;
    // once we have left we stay left until we are back up here, otherwise 12 hp flips the mode every heal tick
    public static final float RECOVERED_HEALTH = 14;

    private BlazeFightRules() {
    }

    public enum Mode {
        // nothing is shooting us and nothing is in reach, wait for the spawner
        CAMP,
        // something is in reach, go hit it
        KILL,
        // something is shooting us and we cannot hit it. get out of its sight so it has to come closer
        COVER,
        // losing. break line of sight, eat, come back
        RETREAT
    }

    // does a blaze count as hostile for the stance logic. charged is the flag the blaze itself syncs while it winds up a
    // volley (the client draws it as the blaze being on fire), so it is the one honest "it is attacking" signal we get
    public static boolean isThreat(double distance, boolean charged, boolean lineOfSight) {
        if (distance <= MELEE_RANGE) return true;
        return charged && lineOfSight && distance <= SIGHT_RANGE;
    }

    // can we walk under it and swing. over lava never (we would have to follow it in), and a blaze with no floor under it
    // is a blaze in the middle of the void
    public static boolean isReachable(double heightAboveFloor, boolean overLava, double distance) {
        if (overLava || distance > MAX_CHASE_DISTANCE) return false;
        return heightAboveFloor <= MAX_REACHABLE_HEIGHT;
    }

    // threats is blazes with a line of sight to us inside SIGHT_RANGE, reachable is blazes we could hit right now
    // (threatening or not, a quiet reachable blaze is still a rod)
    public static Mode decide(float health, boolean retreating, int threats, int reachable) {
        boolean leave = retreating
                ? health < RECOVERED_HEALTH
                : (health <= RETREAT_HEALTH && threats > 0) || health <= PANIC_HEALTH;
        if (leave) return Mode.RETREAT;
        if (reachable > 0) return Mode.KILL;
        if (threats > 0) return Mode.COVER;
        return Mode.CAMP;
    }
}
