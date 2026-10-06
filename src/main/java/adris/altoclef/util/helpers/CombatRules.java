package adris.altoclef.util.helpers;

// the pure half of "are we in a fight, and what do we do about our health bar". no world, just numbers, so the eat
// vs fight vs flee call can be tested without minecraft. MobDefenseChain feeds it real data and FoodChain listens.
//
// the bug this exists for: health <= 10 made the food chain eat, eating made the defense chain switch itself off, and
// vanilla cannot swing a sword while chewing. a piglin was very happy about this
public final class CombatRules {

    // a harmful mob this close is a fight, whatever it is doing right now
    public static final double COMBAT_RANGE = 6;
    // hit by something with a hostile still around, this long counts as still fighting
    public static final long HURT_TICKS = 40;
    // creepers ruin your day from further than most things
    public static final double CREEPER_RANGE = 10;

    // at or below this (half hearts) in a fight we leave instead of trading blows
    public static final float FLEE_HEALTH = 8;
    // at or below this a bite is better than a fight we are losing, as long as nothing is on top of us
    public static final float CRITICAL_HEALTH = 4;
    public static final double QUICK_BITE_CLEARANCE = 3;

    private CombatRules() {
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

    // nearestThreat is the distance to the closest mob that can actually hurt us (infinity for none), ticksSinceHurt is
    // how long ago we took a hit. a hit only counts while something hostile is still around: once the thing that did it
    // is dead there is nothing left to fight and the bot may as well eat
    public static boolean inCombat(double nearestThreat, long ticksSinceHurt, boolean fusingCreeperNear) {
        if (fusingCreeperNear) return true;
        if (Double.isInfinite(nearestThreat)) return false;
        return nearestThreat <= COMBAT_RANGE || ticksSinceHurt <= HURT_TICKS;
    }

    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple) {
        if (!inCombat) return Stance.CALM;
        if (health <= FLEE_HEALTH && hasGapple) return Stance.EAT_GAPPLE;
        if (health <= CRITICAL_HEALTH && nearestThreat > QUICK_BITE_CLEARANCE) return Stance.EAT;
        if (health <= FLEE_HEALTH) return Stance.FLEE;
        return Stance.FIGHT;
    }
}
