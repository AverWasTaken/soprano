package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.CombatRules;
import java.util.List;

// sleeping through the night instead of fighting through it. every decision lives here and nothing here knows there is a game:
// when the trip applies, where the bed has to be to be out of the monsters' reach, the budget of a night, when mob defense
// stands down. NightSleepTask does the walking and the placing, GamerTask runs the trip between the phases
public final class NightSleep {
    // when a bed works is the game's own Level.isDay(), which is false from dusk to dawn and all through a thunderstorm: ticks
    // 12542 to 23459 in clear weather, 12010 to 23991 in full rain (so the old 23992 end of WorldHelper.canSleep was the rainy
    // one). nothing here keeps a copy of that line, the branch asks the level
    public static final long DAY_LENGTH = 24000;

    // the box the game looks in around the bed for monsters, and how far the bed may be from the one that clicks it
    public static final double BOX_HORIZONTAL = 8;
    public static final double BOX_VERTICAL = 5;
    // a monster this far (flat) from the bed counts as one that is coming. the tracker's hostile list stops at 16 anyway
    public static final double COMING_RANGE = 16;

    private NightSleep() {
    }

    // a night is one number, so the budget knows when a new one starts. dusk and dawn are the same day count
    public static long nightOf(long dayTime) {
        return Math.floorDiv(dayTime, DAY_LENGTH);
    }

    // ---- whether the trip applies

    public enum BedSource {
        NONE, BAG, CRAFT, FOUND
    }

    public enum Why {
        GO, OFF, WRONG_PLACE, NOT_NIGHT, BUSY, FIGHTING, LOW_HEALTH, QUIET, NO_BED, LEFTOVER
    }

    // everything the branch can see before it starts. busy is the engine's side (a nether trip, a recovery, a station being
    // picked up, a phase that must not be interrupted), fighting is a commitment the combat machine already holds, spent is
    // "this night is used up" (tries or seconds) and cooling is "a try just failed, wait a bit"
    public record Inputs(boolean enabled, boolean overworld, boolean windowOpen, boolean busy, boolean fighting, boolean lowHealth,
                         boolean spent, boolean cooling, BedSource bed, boolean leftoverBed) {
    }

    // a bed of ours still standing from an earlier trip is picked up whatever the clock says, as long as nothing else has the
    // wheel (and not in a tight loop: it waits out the cooldown). the rest only starts at night, with a bed to use, and quietly
    // otherwise
    public static Why decide(Inputs in) {
        if (!in.enabled()) {
            return Why.OFF;
        }
        if (!in.overworld()) {
            return Why.WRONG_PLACE;
        }
        if (in.busy()) {
            return Why.BUSY;
        }
        if (in.fighting()) {
            return Why.FIGHTING;
        }
        // (a trip would give up on its first tick, and starting one every tick keeps the phase from ever running)
        if (in.lowHealth()) {
            return Why.LOW_HEALTH;
        }
        if (in.leftoverBed()) {
            return in.cooling() ? Why.QUIET : Why.LEFTOVER;
        }
        if (!in.windowOpen()) {
            return Why.NOT_NIGHT;
        }
        if (in.spent() || in.cooling()) {
            return Why.QUIET;
        }
        return in.bed() == BedSource.NONE ? Why.NO_BED : Why.GO;
    }

    // the phases a night may cut into. the portal has lava being cast, the nether has no beds that work, the end prep sets the
    // spawn bed by the portal and a night bed would move it, and the dragon is in the end
    public static boolean phaseAllowed(GamerPhase phase) {
        return switch (phase) {
            case GATHER, IRON, EYES, RETURN, LOCATE, ROOM, OPEN -> true;
            default -> false;
        };
    }

    // woken by the morning. a hit that wakes us at night is not, however long we lay there first (others awake on a server)
    public static boolean sleptThrough(long asleepTicks, boolean day) {
        return asleepTicks > 0 && day;
    }

    // where the bed comes from. a bed already standing close beats carrying one (nothing to place, nothing to pick up), then the
    // bag, then crafting. a craft needs three wool of one colour and three planks, no hunting for either
    public static BedSource bedSource(boolean foundUsable, int bedsInBag, int bestWoolStack, int planks) {
        if (foundUsable) {
            return BedSource.FOUND;
        }
        if (bedsInBag > 0) {
            return BedSource.BAG;
        }
        return bestWoolStack >= 3 && planks >= 3 ? BedSource.CRAFT : BedSource.NONE;
    }

    // ---- monsters

    // a monster's box, the way the game sees it
    public record Mob(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    // the game's own question when we click the bed (ServerPlayer.startSleepInBed): any monster whose box touches 8 either side
    // flat and 5 up and down of the bottom centre of the bed's HEAD block. headX/headZ are the block's corner coordinates, like a
    // BlockPos. it asks once, at the click: a monster that shows up later does not wake anybody
    public static boolean blocked(int headX, int headY, int headZ, List<Mob> mobs) {
        return blocked(headX, headY, headZ, mobs, 0);
    }

    // the same box, wider flat by `slack` on every side. before the bed is down we do not know which of the cells around us its
    // head will be in (up to two off), so the plan asks with the slack and the click asks without
    public static boolean blocked(int headX, int headY, int headZ, List<Mob> mobs, double slack) {
        double cx = headX + 0.5;
        double cz = headZ + 0.5;
        double reach = BOX_HORIZONTAL + slack;
        for (Mob m : mobs) {
            if (m.maxX() > cx - reach && m.minX() < cx + reach
                    && m.maxZ() > cz - reach && m.minZ() < cz + reach
                    && m.maxY() > headY - BOX_VERTICAL && m.minY() < headY + BOX_VERTICAL) {
                return true;
            }
        }
        return false;
    }

    // how many blocks higher the bed has to be so the box misses every monster that is coming: 0 when the spot is clear as it
    // stands, -1 when that is more than the cap or when going up would only drag a monster that is above us into the box.
    // `mobs` is everything within COMING_RANGE. the highest one below the top of the box decides, they all walk to the foot of
    // the pillar. a monster on a slope or a spider on the wall gets the margin. y is the cell we stand in, the bed goes in at
    // that height
    public static int blocksToRaise(int x, int y, int z, List<Mob> mobs, double margin, int cap) {
        if (!blocked(x, y, z, mobs, HEAD_REACH)) {
            return 0;
        }
        double highest = Double.NEGATIVE_INFINITY;
        for (Mob m : mobs) {
            // one already above the box is not one to climb over, it is one to not get closer to (checked below)
            if (m.minY() < y + BOX_VERTICAL) {
                highest = Math.max(highest, m.maxY());
            }
        }
        int n = (int) Math.ceil(highest + BOX_VERTICAL + margin - y);
        n = Math.max(n, 1);
        if (n > cap || blocked(x, y + n, z, mobs, HEAD_REACH)) {
            return -1;
        }
        return n;
    }

    // the bed's head is at most this many cells from the one we stand in (the foot is next to us, the head one more)
    public static final int HEAD_REACH = 2;

    // the column the pillar goes up in has to be open all the way: the cells over our head up to where we will stand, our own
    // two, and one more to jump into when the last block goes down. true when a roof is in the way
    public static boolean roofAt(int blocksToRaise, int openAbove) {
        return openAbove < blocksToRaise + 2;
    }

    // blocks the whole job eats: the pillar, a block under the foot (to click the bed onto) and one under the head, and a spare
    // for the one that lands wrong. the game has no support rule for a bed, but the item of a broken one drops at the head, and
    // without a block there it falls off the pillar
    public static int blocksNeeded(int blocksToRaise) {
        return Math.max(blocksToRaise, 0) + 3;
    }

    // ---- the budget of a night

    public static final class Budget {
        private long night = Long.MIN_VALUE;
        private int attempts;
        private double seconds;
        private double coolUntil = Double.NEGATIVE_INFINITY;
        private boolean slept;

        // a new night is a clean slate. the same night keeps what it spent
        public void roll(long nightId) {
            if (nightId != night) {
                night = nightId;
                attempts = 0;
                seconds = 0;
                coolUntil = Double.NEGATIVE_INFINITY;
                slept = false;
            }
        }

        public void began() {
            attempts++;
        }

        public void spend(double dt) {
            seconds += dt;
        }

        public void failed(double now, double cooldownSeconds) {
            coolUntil = now + cooldownSeconds;
        }

        public void sleptOk() {
            slept = true;
        }

        public int attempts() {
            return attempts;
        }

        public double seconds() {
            return seconds;
        }

        // done for tonight: it worked, or it ran out of tries or time
        public boolean spent(int maxAttempts, double maxSeconds) {
            return slept || attempts >= maxAttempts || seconds >= maxSeconds;
        }

        public boolean cooling(double now) {
            return now < coolUntil;
        }

        // the branch stays out of the way: spent for the night, or waiting out a cooldown
        public boolean quiet(double now, int maxAttempts, double maxSeconds) {
            return spent(maxAttempts, maxSeconds) || cooling(now);
        }
    }

    // ---- the stages and what mob defense does in each

    // RETURN is the walk back to an old bed of ours before the pick up
    public enum Stage {
        CRAFT, WALK, LIFT, BUILD, SLEEP, PICKUP, RETURN, DESCEND
    }

    // from the first block of the pillar to the bed being back in the bag, mobs are not ours to fight: a zombie that hits the
    // pillar must not turn into a fight or a 24 block run off the top of it. the walks to a bed and the way down are ordinary
    // travel, and low health always hands the wheel back (same line as the combat machine's bail)
    public static boolean standsDown(Stage stage, float health) {
        boolean up = stage == Stage.LIFT || stage == Stage.BUILD || stage == Stage.SLEEP || stage == Stage.PICKUP;
        return up && !shouldAbandon(health);
    }

    public static boolean shouldAbandon(float health) {
        return health <= CombatRules.FLEE_HEALTH;
    }

    // ---- what a refusal means

    public enum Refusal {
        NONE, MONSTERS, NOT_NOW, TOO_FAR, OBSTRUCTED, OCCUPIED, OTHER
    }

    // the overlay line the game puts up when a bed says no (block.minecraft.bed.*: "You may not rest now; there are monsters
    // nearby", "You can sleep only at night or during thunderstorms", "...the bed is too far away", "This bed is obstructed",
    // "This bed is occupied"). anything else bed-ish is OTHER, which is a failed try and not a reason to click again. a line
    // about something else is NONE: the overlay carries every action bar message there is, the server's "1/2 players sleeping"
    // included. English only, the overlay hands us the translated text (a client in another language falls back on the
    // monster check before the click and the click patience)
    public static Refusal refusal(String message) {
        if (message == null) {
            return Refusal.NONE;
        }
        String m = message.toLowerCase();
        if (m.contains("monsters nearby")) {
            return Refusal.MONSTERS;
        }
        if (m.contains("sleep only at night")) {
            return Refusal.NOT_NOW;
        }
        if (m.contains("too far away")) {
            return Refusal.TOO_FAR;
        }
        if (m.contains("obstructed")) {
            return Refusal.OBSTRUCTED;
        }
        if (m.contains("occupied")) {
            return Refusal.OCCUPIED;
        }
        // ("no amount of rest can pass this night" is the other players' percentage, also not a reason to click again)
        if (m.contains("you may not rest") || m.contains("this bed") || m.contains("the bed")) {
            return Refusal.OTHER;
        }
        return Refusal.NONE;
    }

    public enum Next {
        // pick the bed up and go higher
        HIGHER, GIVE_UP, CLOSER
    }

    // a refusal after we thought the spot was clear is a failed try: one more go from higher up for monsters, nothing else is
    // worth a second click. too far is a step closer, once
    public static Next afterRefusal(Refusal refusal, int lifts, int closers) {
        return switch (refusal) {
            case MONSTERS -> lifts < 1 ? Next.HIGHER : Next.GIVE_UP;
            case TOO_FAR -> closers < 1 ? Next.CLOSER : Next.GIVE_UP;
            default -> Next.GIVE_UP;
        };
    }

    // ---- clocks

    // seconds a stage may take before the try is written off. the pillar scales with its height
    public static double stageSeconds(Stage stage, int blocks) {
        return switch (stage) {
            case CRAFT -> 60;
            case WALK -> 45;
            case LIFT -> 8 + 4.0 * blocks;
            case BUILD -> 30;
            case SLEEP -> 0;
            case PICKUP -> 25;
            case RETURN -> 45;
            case DESCEND -> 10 + 3.0 * blocks;
        };
    }

    public static boolean timedOut(Stage stage, int blocks, double secondsInStage) {
        double limit = stageSeconds(stage, blocks);
        return limit > 0 && secondsInStage > limit;
    }

}
