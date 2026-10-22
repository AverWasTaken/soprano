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
    // once a blaze is in reach it stays in reach up to here. blazes bob, and one hovering right at 3.5 was flipping KILL and
    // COVER about once a second, forever
    public static final double STAY_REACHABLE_HEIGHT = 4.5;
    // one blocked ray is a fence post sliding past, two in a row is a fence
    public static final int BLOCKED_CHECKS_TO_DROP = 2;
    // blazes further than this are not worth the walk, the ones that matter come to us
    public static final double MAX_CHASE_DISTANCE = 32;

    // KILL and COVER stay put at least this long before flipping to each other. one tick of "reachable" or "not reachable" (a
    // blaze bobbing over a fence line, a cached raycast landing) was swapping the whole task every couple of seconds
    public static final int MIN_DWELL_TICKS = 20;

    // after the last blaze that could see us, wait this long before going out to find one. COVER hides us so they come
    // round the corner, walking off the moment we are hidden throws that away
    public static final int HIDE_HOLD_TICKS = 100;
    // a spawner only spawns with a player within 16 and cover spots stay within 10 of it, so chasing a blaze further out
    // than this leaves us in the open with nowhere to hide
    public static final double HUNT_SPAWNER_RADIUS = 12;
    // a hunt walk only gets a new goal once the blaze's floor has wandered this far from it. blazes drift all the time
    public static final double HUNT_REGOAL_DRIFT = 4;
    // walking at one blaze for this long without it seeing us means its floor is somewhere we cannot get to (the roof,
    // the far side of a wall). try another one
    public static final long HUNT_GIVE_UP_TICKS = 600;

    // sitting out of sight with no blaze looking and hp not going up for this long means it is not going to go up (no
    // food, hunger too low to regen). waiting for 14 hp then is waiting for ever
    public static final long RETREAT_GIVE_UP_TICKS = 600;

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
        RETREAT,
        // blazes are around but none can see us and none is in reach, go let one see us
        HUNT
    }

    // does a blaze count as hostile for the stance logic. charged is the flag the blaze itself syncs while it winds up a
    // volley (the client draws it as the blaze being on fire), so it is the one honest "it is attacking" signal we get
    public static boolean isThreat(double distance, boolean charged, boolean lineOfSight) {
        if (distance <= MELEE_RANGE) return true;
        return charged && lineOfSight && distance <= SIGHT_RANGE;
    }

    // can we walk under it and swing. over lava never (we would have to follow it in), and a blaze with no floor under it
    // is a blaze in the middle of the void. meleeLineClear is a collision-shape raycast from our eye to the blaze: a blaze
    // behind a nether brick fence is low and dry and still a blaze we cannot hit, the fence eats the swing
    public static boolean isReachable(double heightAboveFloor, boolean overLava, double distance, boolean meleeLineClear) {
        if (!meleeLineClear || overLava || distance > MAX_CHASE_DISTANCE) return false;
        return heightAboveFloor <= MAX_REACHABLE_HEIGHT;
    }

    // isReachable with a memory. getting in needs the strict rule, staying in only needs to stay under STAY_REACHABLE_HEIGHT
    // with the swing line not blocked on BLOCKED_CHECKS_TO_DROP checks in a row. lava and distance are never sticky, we are
    // not following anything into lava because it used to be hittable
    public static boolean reachableNow(boolean wasReachable, double heightAboveFloor, boolean overLava, double distance,
                                       boolean meleeLineClear, int blockedChecks) {
        if (overLava || distance > MAX_CHASE_DISTANCE) return false;
        if (wasReachable && heightAboveFloor <= STAY_REACHABLE_HEIGHT && blockedChecks < BLOCKED_CHECKS_TO_DROP) return true;
        return isReachable(heightAboveFloor, overLava, distance, meleeLineClear);
    }

    // spawnerDistance is from the floor under the blaze, NaN with no spawner known (then there is no cap)
    public static boolean nearSpawner(double spawnerDistance) {
        return Double.isNaN(spawnerDistance) || spawnerDistance <= HUNT_SPAWNER_RADIUS;
    }

    // what KILL goes after and what counts toward reachable, the same answer for both. they used to disagree (the count
    // ignored bans), so KILL would start with nothing it was willing to hit and wander off. banned is the kill picker's
    // own ban check, and out past the spawner radius there is no cover to fall back on when it goes wrong
    public static boolean isFightable(boolean reachable, boolean banned, double spawnerDistance) {
        return reachable && !banned && nearSpawner(spawnerDistance);
    }

    // worth walking over to: a floor to stand on under it, not over lava, not too far, not something the tracker gave up
    // on, and close enough to the spawner that cover is still around. spawnerDistance is NaN with no spawner known
    public static boolean isHuntable(boolean hasFloor, boolean overLava, double distance, boolean banned, double spawnerDistance) {
        if (!hasFloor || overLava || banned || distance > MAX_CHASE_DISTANCE) return false;
        return nearSpawner(spawnerDistance);
    }

    // rebuild the hunt goal only for a new target or one that moved off, otherwise the path restarts every tick.
    // goalDrift is how far the target's floor is from the goal we are walking to
    public static boolean needsNewHuntGoal(boolean targetChanged, double goalDrift) {
        return targetChanged || goalDrift > HUNT_REGOAL_DRIFT;
    }

    public static boolean huntTakingTooLong(long ticksOnTarget) {
        return ticksOnTarget >= HUNT_GIVE_UP_TICKS;
    }

    // a gap between hunt ticks this long starts the hunt over. it used to be HIDE_HOLD_TICKS, which is exactly how long a
    // cover break lasts, so HUNT -> it sees us -> COVER -> HUNT reset the give up clock every lap and an unreachable blaze
    // got walked at for ever. cover time counts toward giving up now, only a proper quiet spell is a fresh start
    public static boolean huntBreakIsFreshStart(long ticksSinceLastHunt) {
        return ticksSinceLastHunt > HUNT_GIVE_UP_TICKS;
    }

    public static boolean retreatStalled(long ticksWithoutHealing) {
        return ticksWithoutHealing >= RETREAT_GIVE_UP_TICKS;
    }

    // the give up is only for getting us out of the retreat we are stuck in. once out it has done its job: left on, the
    // next 11 hp retreat skips the wait-for-14 and walks back out the moment the blaze loses sight, every time. a panic
    // retreat (8 hp) stays in RETREAT with it on, so the rods task still knows it is dead time
    public static boolean retreatGaveUpNext(boolean gaveUp, boolean inRetreat, boolean healthRose, boolean stalled) {
        if (healthRose || !inRetreat) return false;
        return gaveUp || stalled;
    }

    // what COVER does when no spot within reach is near the spawner (cover spots have to be both). standing on our own
    // block is not cover, it is a corridor with the shield up, and whatever walks down it gets free hits
    public enum NoCover {
        // the spots are by the spawner, so go there and the next pick finds one
        BACK_TO_SPAWNER,
        // already by the spawner (every spot there marked bad for a bit) or no spawner known: the old shield where we
        // stand. a walk that is already done would stand here with no shield at all, and a run is not what the rods want
        SHIELD_HERE,
        // out past the spawner and too hurt for a walk under fire: the retreat's run from the shooters
        RETREAT
    }

    // same hp line decide leaves on, so this only says RETREAT at a health decide would not have covered at anyway
    public static NoCover noCoverSpot(boolean spawnerKnown, boolean nearSpawner, float health) {
        if (!spawnerKnown || nearSpawner) return NoCover.SHIELD_HERE;
        return health > RETREAT_HEALTH ? NoCover.BACK_TO_SPAWNER : NoCover.RETREAT;
    }

    // threats is blazes with a line of sight to us inside SIGHT_RANGE, reachable is blazes we could hit right now
    // (threatening or not, a quiet reachable blaze is still a rod)
    public static Mode decide(float health, boolean retreating, int threats, int reachable) {
        return decide(health, retreating, threats, reachable, 0, Long.MAX_VALUE);
    }

    // huntable is blazes worth walking to (isHuntable), ticksSinceThreat is how long since one last could see us
    public static Mode decide(float health, boolean retreating, int threats, int reachable, int huntable, long ticksSinceThreat) {
        return decide(health, retreating, false, threats, reachable, huntable, ticksSinceThreat);
    }

    // retreatGaveUp: the retreat stopped healing us (retreatStalled). then we are not retreating any more, only the fresh
    // hp rules can send us back, so 12 hp with nothing looking goes back to work instead of waiting for a 14 that never comes
    public static Mode decide(float health, boolean retreating, boolean retreatGaveUp, int threats, int reachable, int huntable,
                              long ticksSinceThreat) {
        boolean leave = retreating && !retreatGaveUp
                ? health < RECOVERED_HEALTH
                : (health <= RETREAT_HEALTH && threats > 0) || health <= PANIC_HEALTH;
        if (leave) return Mode.RETREAT;
        if (reachable > 0) return Mode.KILL;
        if (threats > 0) return Mode.COVER;
        if (huntable > 0 && ticksSinceThreat >= HIDE_HOLD_TICKS) return Mode.HUNT;
        return Mode.CAMP;
    }

    // decide, but KILL and COVER hold for MIN_DWELL_TICKS before swapping with each other. retreating (in or out), camping
    // and hunting are never held back: low hp should not wait on a timer, and nothing to fight is nothing to dwell on
    public static Mode decide(float health, boolean retreating, int threats, int reachable, Mode current, long ticksInMode) {
        return decide(health, retreating, threats, reachable, 0, Long.MAX_VALUE, current, ticksInMode);
    }

    public static Mode decide(float health, boolean retreating, int threats, int reachable, int huntable, long ticksSinceThreat,
                              Mode current, long ticksInMode) {
        return decide(health, retreating, false, threats, reachable, huntable, ticksSinceThreat, current, ticksInMode);
    }

    public static Mode decide(float health, boolean retreating, boolean retreatGaveUp, int threats, int reachable, int huntable,
                              long ticksSinceThreat, Mode current, long ticksInMode) {
        Mode next = decide(health, retreating, retreatGaveUp, threats, reachable, huntable, ticksSinceThreat);
        boolean flip = (current == Mode.KILL && next == Mode.COVER) || (current == Mode.COVER && next == Mode.KILL);
        if (flip && ticksInMode < MIN_DWELL_TICKS) return current;
        return next;
    }
}
