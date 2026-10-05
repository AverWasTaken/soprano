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

    // current = null on the first pick. endDeaths = how many times the dragon fight already killed us, every one of them
    // raises the armor line (bed self damage is the likeliest killer)
    public static DragonStrat choose(DragonStrat current, int beds, int armorPoints, boolean perched, int endDeaths, EndConfig cfg) {
        int line = cfg.bedMinArmor + cfg.bedArmorPerAttempt * Math.max(0, endDeaths);
        if (current == null) {
            return beds > 0 && armorPoints >= line ? BEDS : SWORD;
        }
        // a perched dragon with a bed already placed: the bed is not in the inventory any more, do not walk away from it
        if (perched) {
            return current;
        }
        if (current == BEDS) {
            return beds == 0 || armorPoints < line - ARMOR_SLACK ? SWORD : BEDS;
        }
        return beds >= RESUME_BEDS && armorPoints >= line ? BEDS : SWORD;
    }
}
