package adris.altoclef.util.helpers;

// the pure half of "what do we do about the blazes". no world, just numbers, same deal as CombatRules.
//
// three moods and that is all of them: kill the one blaze we picked, camp by the spawner until one is in reach, or run when hp
// says so. it used to have cover spots and hunts and holds on top, and flipped "fight, no hide, no this guy" about once a second
public final class BlazeFightRules {

    // a blaze this close is a threat whatever it is doing, it will bite
    public static final double MELEE_RANGE = 3;
    // past this a blaze is background noise. blazes shoot from further, but nothing they throw from here lands
    public static final double SIGHT_RANGE = 20;
    // above the floor, in blocks, a blaze we are not after yet can still be hit by a bot standing under it. the one we are after
    // is kept up to STAY_REACHABLE_HEIGHT, blazes bob and one hovering right on the line would get dropped for breathing
    public static final double MAX_REACHABLE_HEIGHT = 3.5;
    public static final double STAY_REACHABLE_HEIGHT = 4.5;
    // blazes further than this are not worth the walk, the ones that matter come to us
    public static final double PICK_RANGE = 24;
    // the disengage only cares about blazes this close, the ones further out are not following us anywhere
    public static final double DISENGAGE_RANGE = 16;
    // a target that stays out of reach this long (it floated off over lava, the roof) is not the one, take the next. shorter and
    // a blaze bobbing at the height line gets dropped for breathing
    public static final long TARGET_GIVE_UP_TICKS = 100;

    // rods done and a blaze still has a line on us: keep fighting before the next task takes the wheel. the calm spell is how
    // long nothing has been close, the cap is how long we try (a blaze that follows us out is not going to give up)
    public static final int DISENGAGE_CALM_TICKS = 60;
    public static final int DISENGAGE_CAP_TICKS = 900;

    // run at or below this with a blaze looking at us. 10 was too late: a blaze in melee plus the fire under us took 15 hp to 9
    // in three seconds, and from there the run never got out
    public static final float RUN_HEALTH = 14;
    // run at or below this whether or not anything is looking, we are one volley from the respawn screen
    public static final float PANIC_HEALTH = 6;
    // once we are running we run until we are back up here. the 4 hp between this and RUN_HEALTH is what keeps the mode from
    // flipping every heal tick
    public static final float RECOVERED_HEALTH = 18;

    // sitting out of sight with no blaze looking and hp not going up for this long means it is not going to go up (no
    // food, hunger too low to regen). waiting for 18 hp then is waiting for ever
    public static final long RUN_GIVE_UP_TICKS = 600;

    // this many wither skeletons together is a pack, and a pack ends a rods run. 3 can still be fought, 5 cannot
    public static final int PACK_SIZE = 3;
    // a blaze with a pack this close is not worth going for, walking up to it is walking into them
    public static final double PACK_BLAZE_RADIUS = 10;
    // a pack this close to the spawner means the camp spot is theirs, we wait for it to leave or the watch gives the spawner up
    public static final double PACK_SPAWNER_RADIUS = 12;
    // a run from two of them is already enough to keep the spawner walk out of their closet. two still get fought when they
    // come at us, but walking back past them for a blaze was the fortress death
    public static final int ZONE_PACK_SIZE = 2;
    // a pack this close to us (3D, off the tracker, aware of us or not) is one we do not walk into: the blaze target is dropped and
    // the walk stops. fortress floors behind a wall count too, a fat radius is the point
    public static final double PACK_PLAYER_RADIUS = 12;
    // and this close it is already on top of us, so we back away first (the hold alone would stand in their reach)
    public static final double PACK_BACKOFF_RADIUS = 8;
    // how far the back off runs, it only has to get us out of the backoff radius with room to spare
    public static final double PACK_BACKOFF_DISTANCE = 16;
    // a blaze this close is swinging range: running from it is giving it free shots, it flies faster than we walk
    public static final double LATCH_SKIP_BLAZE_RANGE = 4.5;

    private BlazeFightRules() {
    }

    // enough wither skeletons around one spot to call it a pack
    public static boolean packNear(int witherSkeletons) {
        return witherSkeletons >= PACK_SIZE;
    }

    // the middle of a pack ({x, z}), null when the skeletons in the list are not enough to call it one
    public static double[] packCenter(java.util.List<double[]> spots) {
        return packCenter(spots, PACK_SIZE);
    }

    public static double[] packCenter(java.util.List<double[]> spots, int min) {
        if (spots.size() < min) return null;
        double x = 0;
        double z = 0;
        for (double[] spot : spots) {
            x += spot[0];
            z += spot[1];
        }
        return new double[]{x / spots.size(), z / spots.size()};
    }

    public enum Mode {
        // nothing is in reach, wait by the spawner
        CAMP,
        // hit the one blaze we picked
        KILL,
        // losing. get away from the shooters, eat, come back
        RUN
    }

    // does a blaze count as hostile for the stance logic. charged is the flag the blaze itself syncs while it winds up a
    // volley (the client draws it as the blaze being on fire), so it is the one honest "it is attacking" signal we get
    public static boolean isThreat(double distance, boolean charged, boolean lineOfSight) {
        if (distance <= MELEE_RANGE) return true;
        return charged && lineOfSight && distance <= SIGHT_RANGE;
    }

    // a blaze lined up on us from where it hovers, hit or no hit. the heal-up hold re-runs on one of these before the first
    // fireball lands and not after the second
    public static boolean watching(double distance, boolean lineOfSight) {
        return lineOfSight && distance <= SIGHT_RANGE;
    }

    // start disengaging: the rods are in, a blaze sees us, and we have not done this already for this batch of rods
    public static boolean disengageWanted(boolean rodsDone, boolean blazeSeesUs, boolean alreadyDone) {
        return rodsDone && blazeSeesUs && !alreadyDone;
    }

    // stop disengaging: nothing has been close for the calm spell, or the cap ran out and we go on anyway
    public static boolean disengageOver(long ticksSinceSeen, long ticksRunning) {
        return ticksSinceSeen >= DISENGAGE_CALM_TICKS || ticksRunning >= DISENGAGE_CAP_TICKS;
    }

    // can we walk under it and swing. over lava never (we would have to follow it in), and a blaze with no floor under it
    // is a blaze in the middle of the void (height infinite)
    public static boolean isReachable(double heightAboveFloor, boolean overLava, double distance, double range, boolean committed) {
        double ceiling = committed ? STAY_REACHABLE_HEIGHT : MAX_REACHABLE_HEIGHT;
        return !overLava && distance <= range && heightAboveFloor <= ceiling;
    }

    // keep swinging at the same one: it is alive, the tracker still has it, and it has not been out of reach for too long
    public static boolean keepTarget(boolean alive, boolean tracked, long ticksOutOfReach) {
        return alive && tracked && ticksOutOfReach < TARGET_GIVE_UP_TICKS;
    }

    // a combat run (the mob defense one) ended lately and we are not back up yet: the rods task heals first instead of
    // walking straight back at whatever we ran from. the same give up as the idle run covers it from there
    public static boolean healAfterRun(long now, long runEnd, float health) {
        return now >= runEnd && now - runEnd <= RUN_GIVE_UP_TICKS && health < RECOVERED_HEALTH;
    }

    // same, but never with a blaze already in swinging range: the first hit after the run tipped us under 18 and the latch
    // turned a kill into a run with the blaze a block away, three more hits for nothing
    public static boolean healAfterRun(long now, long runEnd, float health, double blazeDistance) {
        return blazeDistance > LATCH_SKIP_BLAZE_RANGE && healAfterRun(now, runEnd, health);
    }

    public static boolean runStalled(long ticksWithoutHealing) {
        return ticksWithoutHealing >= RUN_GIVE_UP_TICKS;
    }

    // the give up is only for getting us out of the run we are stuck in. once out it has done its job: left on, the next 10 hp
    // run skips the wait-for-16 and walks back out the moment the blaze loses sight, every time. a panic run (6 hp) stays in
    // RUN with it on, so the rods task still knows it is dead time
    public static boolean runGaveUpNext(boolean gaveUp, boolean inRun, boolean healthRose, boolean stalled) {
        if (healthRose || !inRun) return false;
        return gaveUp || stalled;
    }

    // threat is a blaze with a line of sight to us inside SIGHT_RANGE, haveTarget is the blaze we are committed to.
    // runGaveUp: the run stopped healing us (runStalled). then we are not running any more, only the fresh hp rules can send
    // us back, so 8 hp with nothing looking goes back to work instead of waiting for a 16 that never comes
    public static Mode decide(float health, boolean running, boolean runGaveUp, boolean threat, boolean haveTarget) {
        return decide(health, running, runGaveUp, threat, haveTarget, false);
    }

    // burning only holds a run we are already in: 18 hp and still on fire is not recovered, walking back in alight just
    // burns the rest off. it never starts a run on its own, and the give up still ends it
    public static Mode decide(float health, boolean running, boolean runGaveUp, boolean threat, boolean haveTarget, boolean burning) {
        boolean leave = running && !runGaveUp
                ? health < RECOVERED_HEALTH || burning
                : (health <= RUN_HEALTH && threat) || health <= PANIC_HEALTH;
        if (leave) return Mode.RUN;
        return haveTarget ? Mode.KILL : Mode.CAMP;
    }

    // the camp spot gets one bounded step inland off a lava rim or ledge, same budget idea as the heal hold's step
    public static final int CAMP_STEP_TICKS = 100;

    // step while there is a cell to go to, we are not in it yet, and the budget is not spent
    public static boolean wantsCampStep(boolean haveCell, boolean arrived, int ticksUsed) {
        return haveCell && !arrived && ticksUsed < CAMP_STEP_TICKS;
    }
}
