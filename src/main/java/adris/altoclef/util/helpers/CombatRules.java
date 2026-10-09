package adris.altoclef.util.helpers;

// the pure half of "are we in a fight, and what do we do about our health bar". no world, just numbers, so the eat
// vs fight vs flee call can be tested without minecraft. MobDefenseChain feeds it real data and FoodChain listens.
//
// the bug this exists for: health <= 10 made the food chain eat, eating made the defense chain switch itself off, and
// vanilla cannot swing a sword while chewing. a piglin was very happy about this
public final class CombatRules {

    // creepers ruin your day from further than most things
    public static final double CREEPER_RANGE = 10;
    // a lit fuse this close is not scenery any more, the chain steps away from it
    public static final double CREEPER_NO_IGNORE = 7;

    // an angry melee mob this close is hitting us whatever the history says, and "is it on top of us" generally
    public static final double CONTACT_RANGE = 3;
    // angry mobs this close count towards a crowd
    public static final double SWARM_RANGE = 6;
    // at or below FLEE_HEALTH anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = 8;

    // at or below this (half hearts) in a fight we leave instead of trading blows. the one hp line: the commitment, the
    // chain's checks and the kill aura's shield gate all read this and nothing else
    public static final float FLEE_HEALTH = 8;
    // the one exception, and it is a named one: a heavy hitter (wither skeleton, hoglin, brute, vindicator...) lands 8 to 13
    // in one swing, so at 10 hp it is already "one hit from the respawn screen" and not a fight to start or to stay in.
    // everything else in the world hits for less than 8, which is why the flee line is where it is
    public static final float HEAVY_FLEE_HEALTH = 10;
    // at or below this a bite is better than a fight we are losing, as long as nothing is on top of us
    public static final float CRITICAL_HEALTH = 4;
    public static final double QUICK_BITE_CLEARANCE = 3;

    private CombatRules() {
    }

    // the chain asked for the wheel at this priority. a priority is a promise that something is running, so with no task
    // (or one that was born finished, a run we are already outside of) it is a zero. the live bug was eleven seconds of
    // "Mob Defense holds the wheel at 70.0 with nothing running"
    public static float wheelPriority(float priority, boolean hasTask, boolean taskFinished) {
        if (priority <= 0) return priority;
        return hasTask && !taskFinished ? priority : 0;
    }

    public enum Stance {
        // not fighting, eat whenever the food chain wants
        CALM,
        // fighting and healthy enough to keep at it. no eating, you cannot swing and chew
        FIGHT,
        // fighting and losing. run, eat once we are out of it
        FLEE,
        // fighting but about to die with nothing next to us, one bite is worth it
        EAT,
        // fighting and holding a gapple, which is what gapples are for
        EAT_GAPPLE;

        // the food chain may chew right now
        public boolean mayEat() {
            return this == CALM || this == EAT || this == EAT_GAPPLE;
        }
    }

    // above the flee line: healthy enough for the aura to put a shield up and for a fight to carry on
    public static boolean aboveFleeLine(float health) {
        return health > FLEE_HEALTH;
    }

    // nearestThreat is the distance to the closest mob that can actually hurt us (infinity for none)
    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple) {
        if (!inCombat) return Stance.CALM;
        if (health <= FLEE_HEALTH && hasGapple) return Stance.EAT_GAPPLE;
        if (health <= CRITICAL_HEALTH && nearestThreat > QUICK_BITE_CLEARANCE) return Stance.EAT;
        if (health <= FLEE_HEALTH) return Stance.FLEE;
        return Stance.FIGHT;
    }
}
