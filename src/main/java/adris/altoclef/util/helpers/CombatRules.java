package adris.altoclef.util.helpers;

import java.util.List;

// the pure half of "are we in a fight, and what do we do about our health bar". no world, just numbers, so the eat
// vs fight vs flee call can be tested without minecraft. MobDefenseChain feeds it real data and FoodChain listens.
//
// the bug this exists for: health <= 10 made the food chain eat, eating made the defense chain switch itself off, and
// vanilla cannot swing a sword while chewing. a piglin was very happy about this
public final class CombatRules {

    // a lit fuse this close and we do not sit down to eat. we do not run from it either, walking past is faster
    public static final double CREEPER_RANGE = 10;

    // "is it on top of us": the only range a fight starts from (it also has to have hit us), and the corner rule's
    public static final double CONTACT_RANGE = 3;
    // melee mobs this close make a crowd for the weapon pick and the crits. not a reason to run any more, just to swing
    // for damage per second
    public static final double SWARM_RANGE = 6;
    // at or below FLEE_HEALTH anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = 8;

    // at or below this (half hearts) in a fight we leave instead of trading blows. the one hp line: the commitment, the
    // chain's checks and the kill aura's shield gate all read this and nothing else
    public static final float FLEE_HEALTH = 8;
    // the one exception, and it is a named one: a heavy hitter (hoglin, brute, vindicator...) lands 8 to 13
    // in one swing, so at 10 hp it is already "one hit from the respawn screen" and not a fight to start or to stay in.
    // nearly everything else hits for less than 8 (the wither skeleton is a plain fight with its own bail, see
    // CombatCommit.lowHit), which is why the flee line is where it is
    public static final float HEAVY_FLEE_HEALTH = 10;
    // at or below this a bite is better than a fight we are losing, as long as nothing is on top of us
    public static final float CRITICAL_HEALTH = 4;
    public static final double QUICK_BITE_CLEARANCE = 3;
    // a run may sit down to a bite when the nearest thing angry at us is at least RUN_BITE_START away (and nothing that shoots
    // has a line on us), and once it has started it is only dropped when somebody gets inside RUN_BITE_ABORT. one line for
    // both made a follower at 9 cancel every bite, and a bite that never lands is just a slower run.
    // the arithmetic: a bite is 32 ticks (1.6 s) and a chewing player walks at a fifth of the speed, about 0.9 blocks a second
    // (and cannot sprint). a zombie goes about 2.3 (the speed attribute counts twice for a mob, once as the speed and once as
    // the forward input), so it gains about 1.5 blocks a second, 2.4 over a bite: a bite started at 10 ends with it at 7.6,
    // 2.6 blocks clear of the abort line. a spider (0.3) goes about 4 and gains 5.1 over a bite, a baby zombie (0.345) about
    // 5.2 and gains 6.9: no pair of lines gives those margin at 10, and widening the abort line would drop every bite, so
    // they are kept out of "may start" instead: nothing that fast within RUN_BITE_START_FAST, which is the abort line plus
    // the worst gain (6.9) plus a block of slack, after which they end a bite at 6 or more
    public static final double RUN_BITE_START = 10;
    public static final double RUN_BITE_ABORT = 5;
    public static final double RUN_BITE_START_FAST = 13;
    // a mob with at least this movement speed attribute is "fast" for the bite (spiders, baby zombies, vindicators; a plain
    // zombie is 0.23, a skeleton 0.25)
    public static final double FAST_MOB_SPEED = 0.3;
    // a shooter this far off with a line to us counts as "being shot at": no sitting down to a meal in its sights
    public static final double SHOOTER_RANGE = 15;
    // a pile shield (see pileShield) that is up holds while they are this much past contact. our own swing knocks one of
    // them back a block or so, and every re-raise is 5 ticks before the shield blocks again
    public static final double PILE_SHIELD_SLACK = 1.5;

    // the meal rules, all in one place so the food chain and the tests read the same numbers.
    // natural regeneration only runs with the hunger bar at 18 or more, so hurt and under it means eat until it is there.
    // the older rules (hp under BRUISED_HEALTH, or hunger at HUNGRY_AT or less) leave a band where a bot neither regenerates
    // nor eats, and this closes it
    public static final int REGEN_HUNGER = 18;
    // any missing hp counts. at 18.6 with the bar on 17 nothing regenerates and nothing eats, and the next fight starts
    // from there. a steak into a bar of 17 wastes a few points, the bag carries plenty
    public static final float HURT_HEALTH = 20;
    // at or below this health any hunger under 20 is a meal
    public static final float DESPERATE_HEALTH = 10;
    // the older rules (the food chain config defaults): hungry at or below HUNGRY_AT, or below BRUISED_HEALTH with a
    // bar that is above it
    public static final int HUNGRY_AT = 10;
    public static final int BRUISED_HEALTH = 14;

    private CombatRules() {
    }

    // hurt, and the hunger bar is under the line where we start healing by ourselves
    public static boolean healingMeal(float health, int hunger) {
        return health < HURT_HEALTH && hunger < REGEN_HUNGER;
    }

    public static boolean desperateMeal(float health, int hunger) {
        return health <= DESPERATE_HEALTH && hunger < 20;
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

    // the shield in a normal (not cornered) fight: the target is melee and in contact, and another melee mob that has hit us
    // is in contact too. one zombie never gets it, 1v1 is crits first and a raised shield vetoes the jump. only mobs that hit
    // us count, never bystanders: two zombies milling about while we walk to a target five blocks off must not stop the walk
    // for a shield. up: it is up already, and then the hold band (contact plus PILE_SHIELD_SLACK) is the one asked
    public static boolean pileShield(boolean up, int targetId, List<CombatCommit.Foe> foes) {
        double reach = up ? CONTACT_RANGE + PILE_SHIELD_SLACK : CONTACT_RANGE;
        boolean target = false;
        boolean other = false;
        for (CombatCommit.Foe foe : foes) {
            if (!foe.melee() || foe.distance() > reach) continue;
            if (foe.id() == targetId) {
                target = true;
            } else if (foe.hitUs()) {
                other = true;
            }
        }
        return target && other;
    }

    // nearestThreat is the distance to the closest mob that can actually hurt us (infinity for none)
    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple) {
        return stance(inCombat, health, nearestThreat, hasGapple, true);
    }

    // hasFood: the food chain has something it would eat. without it the EAT stance is a promise nobody can keep (it still
    // sets the in-combat flag in the food chain and the bucket chain), so it is the flee stance instead
    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple, boolean hasFood) {
        if (!inCombat) return Stance.CALM;
        if (health <= FLEE_HEALTH && hasGapple) return Stance.EAT_GAPPLE;
        if (health <= CRITICAL_HEALTH && nearestThreat > QUICK_BITE_CLEARANCE && hasFood) return Stance.EAT;
        if (health <= FLEE_HEALTH) return Stance.FLEE;
        return Stance.FIGHT;
    }
}
