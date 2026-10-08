package adris.altoclef.control;

// when to jump and when to swing so a hit lands as a crit. no minecraft in here on purpose, PlayerExtraController gathers
// the facts every tick and this just says what to do about them, which is also why it has tests
//
// vanilla wants all of these at the moment of the hit: full cooldown, falling (fallDistance > 0 and off the ground), not on
// a ladder or vine, not in water, not blind, not riding, not sprinting. the adapter drops sprint on the jump tick and every
// tick after, and the machine never swings on a tick that still starts sprinting (the stop packet goes out after the interact
// packet of that tick, so the server would still see a sprint hit)
public class CritTiming {

    public enum Step {
        // no crit business, do whatever a swing would have done before this existed (hit if the cooldown is full)
        PLAIN,
        // press jump, and stop sprinting while at it
        JUMP,
        // in the air and waiting on the fall or the cooldown. hold the swing
        WAIT,
        // falling with a full cooldown. now
        SWING,
        // a hop is due soon but the mob is too far for it to still be in reach when we come down. walk up, do not jump
        APPROACH
    }

    // the jump goes up for 6 ticks (0.42 and then gravity eating it) and the first tick that is really falling is the 7th,
    // so a jump this many ticks before the cooldown fills lands the crit the very tick the cooldown is ready. any later
    // and we wait on the cooldown, any earlier and we wait on the fall. a sword (12.5 tick cooldown) would otherwise
    // lose about half its dps to the jump, which is the whole reason this is not just "jump at 90%"
    public static final int LEAD_TICKS = 7;

    // a jump that has not left the ground by now never will (baritone owns the keys, a trapdoor, whatever), swing normally
    private static final int TAKEOFF_TICKS = 3;
    // and one that is still going this long after is not a hop, something is off
    private static final int MAX_AIR_TICKS = 24;
    // nobody asked us for this long, whatever we were doing is stale (task switched, mob died, stopped being a target)
    private static final long STALE_TICKS = 3;

    // a crit is a bonus, not a reason to stand there. none of these is a good time to go hopping around:
    // three things on us at once. it used to also be "got hit in the last second", which vs a zombie (hits once a second, so
    // always) meant it never hopped at all. players crit while taking hits, that is most of pvp
    public static final int CROWD_NO_CRIT = 3;
    // and three hearts or fewer, where the hop's few ticks of not blocking start to cost real money
    public static final float LOW_HEALTH_NO_CRIT = 6;
    // however it goes, the swing is never held more than this many ticks past a full cooldown. the fall is not a promise
    public static final int MAX_READY_WAIT = 4;

    // the hop is 7+ ticks of nothing and the swing happens at the end of it, so the mob has to be well inside reach when we
    // leave the ground, not just barely in it. it used to jump the moment anything was in reach and then watch it stay out
    // there (the crit never connected, which was the whole bug). this much slack, measured eye to the mob's box like vanilla
    public static final double JUMP_MARGIN = 0.5;
    // and a mob walking away faster than this (blocks per tick along the line to us) will be gone by the time we come down.
    // 0.1 is a zombie's pace, so that is the line for "it is leaving"
    public static final double MAX_RETREAT = 0.1;

    // is the mob close enough, and staying put enough, that a hop now still ends with it in reach
    public static boolean closeEnoughToHop(double reachGap, double reach, double retreatSpeed) {
        return reachGap <= reach - JUMP_MARGIN && retreatSpeed <= MAX_RETREAT;
    }

    // the tooClose back-off in the kill tasks walks us away from the mob. mid hop (or right before one) that is exactly what
    // makes the swing whiff, so it sits those out
    public static boolean shouldBackOff(boolean tooClose, boolean hopWantsToStayClose) {
        return tooClose && !hopWantsToStayClose;
    }

    // the two reasons above as one question. PlayerExtraController asks it for wantsCritTick too, so the kill tasks and
    // this machine can't disagree about whether a hop is on
    public static boolean underPressure(int meleeNear, float health) {
        return meleeNear >= CROWD_NO_CRIT || health <= LOW_HEALTH_NO_CRIT;
    }

    // everything that can change the answer. the defaults are the easy case, every guard open, so a test sets only the
    // thing it is about
    public static class Sample {
        public boolean enabled = true;
        public boolean inReach = true;
        // where the mob is for the jump decision: eye to the nearest point of its box, the interaction range we have (3 for
        // a normal player), and how fast it is walking away from us. the defaults are a mob standing right in front of us
        public double reachGap = 1.0;
        public double reach = 3.0;
        public double retreatSpeed = 0;
        public boolean onGround = true;
        // dy < 0 and fallDistance > 0, the "is a crit possible this tick" half that cares about the fall
        public boolean falling = false;
        // ticks until the attack strength scale hits 1.0, 0 or less when it is ready
        public double ticksToFull = 0;
        // a plain hit kills it, so waiting for a crit is just waiting
        public boolean normalHitKills = false;
        // vanilla refuses the crit, or the jump would be a bad idea
        public boolean inFluid = false;
        public boolean onClimbable = false;
        public boolean blind = false;
        public boolean riding = false;
        // eating, or any other use of the item in hand
        public boolean usingItem = false;
        // shield up (KITE and STAND). jumping and blocking fight each other
        public boolean shielding = false;
        // something would bonk our head, or the first block of floor around us is not close and not lava free
        public boolean lowHeadroom = false;
        public boolean unsafeFloor = false;
        // baritone is walking a path. our jump press would be ignored at best and a parkour or pillar at worst
        public boolean pathing = false;
        // the client says we are sprinting, which is also what the server was last told (the stop packet goes out with the
        // player tick, after the interact packet of the tick we swing in). vanilla drops the crit for a sprinting hit
        public boolean sprinting = false;
        // how many melee mobs are within 3, how much health we have. the defaults are a quiet fight at full health
        public int meleeNear = 0;
        public float health = 20;

        boolean pressured() {
            return underPressure(meleeNear, health);
        }

        boolean retreating() {
            return retreatSpeed > MAX_RETREAT;
        }
    }

    private enum Phase {IDLE, AIRBORNE}

    private Phase phase = Phase.IDLE;
    private int airTicks = 0;
    // airborne ticks the cooldown has been full for, the MAX_READY_WAIT cap counts these
    private int readyTicks = 0;
    // we jumped and came down without a swing. the next hit is a normal one instead of another try
    private boolean plainNext = false;
    private long lastNow = Long.MIN_VALUE;
    private Step lastStep = Step.PLAIN;

    public Step step(long now, Sample s) {
        if (now == lastNow) {
            // the kill task and the force field both ask every tick. only the first one gets to act
            return lastStep == Step.JUMP || lastStep == Step.SWING ? Step.WAIT : lastStep;
        }
        if (lastNow != Long.MIN_VALUE && now - lastNow > STALE_TICKS) {
            reset();
            plainNext = false;
        }
        lastNow = now;
        lastStep = decide(s);
        return lastStep;
    }

    private Step decide(Sample s) {
        if (!s.enabled) {
            reset();
            plainNext = false;
            return Step.PLAIN;
        }
        if (phase == Phase.AIRBORNE) {
            return airborne(s);
        }
        if (plainNext) {
            plainNext = false;
            return Step.PLAIN;
        }
        // already coming down (a knockback, a ledge) with everything lined up: that is a free crit, take it. still sprinting
        // means the server would call it a sprint hit, so drop it this tick and swing on the next one (still falling)
        if (!s.onGround) {
            if (!(s.falling && s.inReach && vanillaAllows(s) && s.ticksToFull <= 0)) {
                return Step.PLAIN;
            }
            return s.sprinting ? Step.WAIT : Step.SWING;
        }
        if (canJump(s)) {
            phase = Phase.AIRBORNE;
            airTicks = 0;
            readyTicks = 0;
            return Step.JUMP;
        }
        // hop is due but the mob is a bit far. close the gap now instead of jumping at nothing (the plain swing still
        // fires the tick the cooldown fills, this only ever runs while it is charging)
        return wantsHop(s) && !s.retreating() ? Step.APPROACH : Step.PLAIN;
    }

    private Step airborne(Sample s) {
        airTicks++;
        // (a crowd or a bad health number showing up while we are up is in here too. come down swinging, not waiting)
        if (!s.inReach || !vanillaAllows(s) || s.shielding || s.usingItem || s.pathing || s.pressured()) {
            return giveUp();
        }
        if (s.onGround) {
            // the tick after the jump press we can still be standing, the player has not ticked yet
            if (airTicks < TAKEOFF_TICKS) {
                return Step.WAIT;
            }
            // up and back down without a swing, or never up at all
            return giveUp();
        }
        if (airTicks > MAX_AIR_TICKS) {
            return giveUp();
        }
        readyTicks = s.ticksToFull <= 0 ? readyTicks + 1 : 0;
        // falling with a full cooldown is the crit. still going up with one past the cap is a plain hit from here, but it
        // is a hit
        if ((s.falling && s.ticksToFull <= 0) || readyTicks > MAX_READY_WAIT) {
            if (s.sprinting) {
                // the stop packet has not made it yet. the WAIT clears sprint and the swing is a tick late, the fall can afford it
                return Step.WAIT;
            }
            reset();
            return Step.SWING;
        }
        return Step.WAIT;
    }

    // swing normally from here on: this tick if there is a swing to give, and the next hit too so a cooldown that never
    // lines up with the fall can't turn into a bunny hop
    private Step giveUp() {
        reset();
        plainNext = true;
        return Step.PLAIN;
    }

    public void reset() {
        phase = Phase.IDLE;
        airTicks = 0;
        readyTicks = 0;
    }

    // mid jump and somebody is still asking. the kill task lets go of its grounded check for this
    public boolean inFlight(long now) {
        return phase == Phase.AIRBORNE && now - lastNow <= STALE_TICKS;
    }

    private static boolean vanillaAllows(Sample s) {
        return !s.inFluid && !s.onClimbable && !s.blind && !s.riding;
    }

    private static boolean canJump(Sample s) {
        return wantsHop(s) && closeEnoughToHop(s.reachGap, s.reach, s.retreatSpeed);
    }

    // every reason to hop except where the mob is: guards, and the cooldown being inside the lead
    public static boolean wantsHop(Sample s) {
        return hopVeto(s) == null;
    }

    // the first thing standing between us and a hop, null when there is none. same list wantsHop always had, as words so
    // the play log can say which one fired
    public static String hopVeto(Sample s) {
        if (!s.inReach) {
            return "out of reach";
        }
        if (!vanillaAllows(s)) {
            return "vanilla refuses (fluid, ladder, blind, riding)";
        }
        if (s.usingItem) {
            return "using item";
        }
        if (s.shielding) {
            return "shielding";
        }
        if (s.lowHeadroom) {
            return "low headroom";
        }
        if (s.unsafeFloor) {
            return "unsafe floor";
        }
        if (s.pathing) {
            return "pathing";
        }
        // a plain hit kills it, a jump would only make the kill later
        if (s.normalHitKills) {
            return "plain hit kills";
        }
        // crowded or hurting: swing like a person who would like to keep their hearts
        if (s.meleeNear >= CROWD_NO_CRIT) {
            return "crowd (" + s.meleeNear + ")";
        }
        if (s.health <= LOW_HEALTH_NO_CRIT) {
            return "low health";
        }
        // a full cooldown means the swing is owed right now. a hop is 7 ticks up before the first falling one, past the cap
        // on how long a crit may hold a swing, so the "free" hop at a full cooldown was just a delay with extra steps
        if (s.ticksToFull <= 0) {
            return "cooldown full, swing now";
        }
        if (s.ticksToFull > LEAD_TICKS) {
            return "cooldown not in the lead yet";
        }
        return null;
    }
}
