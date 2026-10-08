package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;

// beds on the perch or sword and pearl. pure, with hysteresis so the phase can ask every tick without flip flopping
public enum DragonStrat {
    BEDS,
    SWORD;

    // going back to beds after falling back needs a real stack, one stray bed picked up is not a plan
    public static final int RESUME_BEDS = 3;
    // armor wobbles for a tick while pieces swap, leaving beds needs to be clearly under the line
    private static final int ARMOR_SLACK = 2;
    // what a full health bar plus nothing looks like, for callers that do not care about health
    private static final double FULL_POOL = 20;

    public static DragonStrat choose(DragonStrat current, int beds, int armorPoints, boolean perched, int endDeaths, EndConfig cfg) {
        return choose(current, beds, armorPoints, perched, endDeaths, cfg, FULL_POOL);
    }

    // current = null on the first pick. endDeaths = how many times the dragon fight already killed us, every one of them
    // raises the armor line (bed self damage is the likeliest killer). healthPool = health + absorption right now:
    // beds are only (re)started when it covers the bed's own explosion (BedSafety), and a bed fight only gives up on health
    // when it is critical. a dent from the dragon is not a reason to swap, the bed task waits and the food chain heals us
    public static DragonStrat choose(DragonStrat current, int beds, int armorPoints, boolean perched, int endDeaths, EndConfig cfg,
                                     double healthPool) {
        return choose(current, beds, armorPoints, perched, endDeaths, cfg, healthPool, false);
    }

    // healStalled = the bed task stood too hurt to click and nothing healed us (HealStall). that beats the perched latch below: waiting
    // between CRITICAL_POOL and the click line with no food would be forever, and the sword needs no safe pool
    public static DragonStrat choose(DragonStrat current, int beds, int armorPoints, boolean perched, int endDeaths, EndConfig cfg,
                                     double healthPool, boolean healStalled) {
        int line = cfg.bedMinArmor + cfg.bedArmorPerAttempt * Math.max(0, endDeaths);
        boolean healthy = healthPool >= BedSafety.safePool(armorPoints);
        if (current == null) {
            return beds > 0 && armorPoints >= line && healthy ? BEDS : SWORD;
        }
        if (current == BEDS && healStalled) {
            return SWORD;
        }
        // a perched dragon with a bed already placed: the bed is not in the inventory any more, do not walk away from it
        if (perched) {
            return current;
        }
        if (current == BEDS) {
            return beds == 0 || armorPoints < line - ARMOR_SLACK || healthPool < BedSafety.CRITICAL_POOL ? SWORD : BEDS;
        }
        return beds >= RESUME_BEDS && armorPoints >= line && healthy ? BEDS : SWORD;
    }
}
