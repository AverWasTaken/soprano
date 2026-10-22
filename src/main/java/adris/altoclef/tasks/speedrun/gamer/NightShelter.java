package adris.altoclef.tasks.speedrun.gamer;

// the narrow night shelter: a respawn with no armor and no bed at night is a skeleton snack on the surface, and a sealed hole in
// the dirt for the rest of the night is the cheap answer. narrow on purpose (a recent death is part of the trigger): hiding on
// every first night would make the run a scaredy cat. every decision is here, ShelterTask does the digging
public final class NightShelter {
    // worn armor points under this and a night on the surface is worth hiding from (a full iron kit is 15, a chestplate alone 6)
    public static final int ARMOR_LINE = 6;
    // ten minutes of game time: a death at dusk still covers the whole night
    public static final long RECENT_DEATH_TICKS = 12000;
    // a surface is this close to the open sky or less (a ditch counts)
    public static final int SURFACE_DEPTH = 1;
    // the game ticks past the morning we are willing to sit in the hole before giving up on the clock
    public static final long SLACK_TICKS = 1200;
    // how many cells the hole goes down, the cap goes in the top one (flush with the ground, it has walls to be clicked against)
    public static final int DEPTH = 3;
    // the cap plus the pillar out of a 3 deep hole (breaking the cap hands its block back, so this is the cap and two more)
    public static final int BLOCKS_NEEDED = 3;

    private NightShelter() {
    }

    // everything the branch can see. note what is not here: low health and a spent bed trip. a sealed hole is the safest place
    // to regen, and the bed trip's tries have nothing to do with this
    public record Trigger(boolean windowOpen, boolean overworld, int armorPoints, boolean noBed, int depthBelowSky, boolean recoveryActive,
                          boolean fighting, boolean busy, boolean usedThisNight, long ticksSinceDeath) {
    }

    public static boolean wanted(Trigger t) {
        return t.windowOpen() && t.overworld() && t.armorPoints() < ARMOR_LINE && t.noBed() && t.depthBelowSky() <= SURFACE_DEPTH
                && !t.recoveryActive() && !t.fighting() && !t.busy() && !t.usedThisNight() && recentDeath(t.ticksSinceDeath());
    }

    // -1 = no death on record (or the clock went backwards, a relog)
    public static boolean recentDeath(long ticksSinceDeath) {
        return ticksSinceDeath >= 0 && ticksSinceDeath <= RECENT_DEATH_TICKS;
    }

    // ---- the once per night flag, keyed on the day count (NightSleep.nightOf). the bed trip's budget is not touched

    public static final class Once {
        private long night = Long.MIN_VALUE;

        public boolean used(long nightOf) {
            return night == nightOf;
        }

        public void mark(long nightOf) {
            night = nightOf;
        }
    }

    // ---- the hole

    // what the world says about one candidate column. top/mid/low are the three cells the hole is made of
    public record Column(boolean topDiggable, boolean midDiggable, boolean lowDiggable, boolean floorSolid, boolean floorFluid,
                         boolean fluidNear, boolean wallsSolid, boolean openAbove, int capSupply) {
    }

    public static boolean columnOk(Column c) {
        return c.topDiggable() && c.midDiggable() && c.lowDiggable() && c.floorSolid() && !c.floorFluid() && !c.fluidNear()
                && c.wallsSolid() && c.openAbove() && c.capSupply() >= BLOCKS_NEEDED;
    }

    // first good column in the order given (ours first, then the nearest ones), -1 = none
    public static int pickColumn(java.util.List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columnOk(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // what a bare hand (or the shovel we may not have) takes down fast. a pick opens up the harder stuff, but never the
    // unbreakable (hardness -1) or obsidian
    public static boolean diggable(boolean shovelMineable, boolean pickMineable, float hardness, boolean havePick) {
        if (hardness < 0 || hardness > 3) {
            return false;
        }
        return shovelMineable || (pickMineable && havePick);
    }

    // the first thing in the bag we may put over our head. a falling block (sand, gravel) would land on us and suffocate, so
    // those are never picked even when they are all we have
    public static int pickCap(boolean[] falling, boolean[] have) {
        for (int i = 0; i < falling.length; i++) {
            if (have[i] && !falling[i]) {
                return i;
            }
        }
        return -1;
    }

    // ---- the wait

    // the morning: the day count moved on from the one we sealed in. day time 0 is the sunrise
    public static boolean morning(long nightWeSealedIn, long dayTime) {
        return NightSleep.nightOf(dayTime) > nightWeSealedIn;
    }

    // game ticks to wait at the most: what is left of the day count plus some slack. game time, not wall time, a lagging
    // client has fewer ticks per second and the night is made of ticks
    public static long waitCap(long dayTime) {
        return NightSleep.DAY_LENGTH - Math.floorMod(dayTime, NightSleep.DAY_LENGTH) + SLACK_TICKS;
    }

    // cap = waitCap taken once when the cap went on: it shrinks as the night goes, asking again each tick would end the wait early
    public static boolean waitOver(long nightWeSealedIn, long dayTime, long ticksSealed, long cap) {
        return morning(nightWeSealedIn, dayTime) || ticksSealed > cap;
    }

    // hit while sealed: whoever it is got in (or an arrow found a gap), mob defense gets its hands back
    public static boolean hurtWhileSealed(float before, float now) {
        return now < before - 0.01f;
    }
}
