package adris.altoclef.util.helpers;

// may a bite start right now. chewing multiplies the move input by 0.2 and drops the sprint, and no movement in baritone
// knows that (a parkour jump, a sprint ascend or a bridge that places on the move comes up short), so outside a fight a
// bite only begins while we are standing still. pure: plain booleans in, a verdict out, the food chain gathers the inputs
public final class BiteRules {
    // blocks per tick of horizontal speed that still counts as standing. a sprint is 0.28 and the ground eats 45% of it per
    // tick, so this is a handful of ticks after the pause is granted
    public static final double STILL_SPEED = 0.03;
    // ticks of wanting a stop and not getting one before the log says so
    public static final int WAIT_LOG_TICKS = 60;
    // ticks of holding the use key with nothing happening before we let go of it and press it again
    public static final int USE_START_TICKS = 10;
    // presses again before we give up for a while, and how long a while is
    public static final int RETRIES = 3;
    public static final int BACKOFF_TICKS = 40;

    private BiteRules() {
    }

    public enum Verdict {
        // standing safe (or the pause is granted and we stopped): start chewing, and hold the pause until the meal is done
        EAT_NOW,
        // a path is live and we can stop it: ask pathing to pause, touch nothing else (a slot swap would fight its tool)
        ASK_STOP,
        // we cannot stop here (air, water, ladder, boat, a movement that is mid jump): do nothing, ask again next tick
        WAIT,
        // the combat machine has the wheel, chewing on the move is how it works there and nothing changes
        CHEW_ON_THE_MOVE
    }

    // combat: a committed run or fight, or a stance that is not calm (the gapple and the quick bite live there).
    // meal: a bite is already going, it is ours to finish. pathLive: pathing has a path. pathPaused: it has one and it
    // did not tick this tick because we asked
    public record Scene(boolean combat, boolean meal, boolean screen, boolean onGround, boolean inWater, boolean onLadder,
                        boolean riding, double speed, boolean pathLive, boolean pathPaused, boolean safeToCancel) {
        // everything calm and still, no path: the starving-in-a-field case
        public static Scene standing() {
            return new Scene(false, false, false, true, false, false, false, 0, false, false, true);
        }

        public Scene withCombat(boolean v) {
            return new Scene(v, meal, screen, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withMeal(boolean v) {
            return new Scene(combat, v, screen, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withScreen(boolean v) {
            return new Scene(combat, meal, v, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withOnGround(boolean v) {
            return new Scene(combat, meal, screen, v, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withInWater(boolean v) {
            return new Scene(combat, meal, screen, onGround, v, onLadder, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withOnLadder(boolean v) {
            return new Scene(combat, meal, screen, onGround, inWater, v, riding, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withRiding(boolean v) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, v, speed, pathLive, pathPaused, safeToCancel);
        }

        public Scene withSpeed(double v) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, riding, v, pathLive, pathPaused, safeToCancel);
        }

        public Scene withPath(boolean live, boolean paused, boolean safe) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, riding, speed, live, paused, safe);
        }
    }

    public static Verdict verdict(Scene s) {
        // the fight code decides how it eats, we are not in that business
        if (s.combat()) return Verdict.CHEW_ON_THE_MOVE;
        // already chewing: dropping a bite halfway is worse than any of the below
        if (s.meal()) return Verdict.EAT_NOW;
        // a bite cannot start with a screen open (the game does not look at the use key then), so do not fight it
        if (s.screen()) return Verdict.WAIT;
        // falls end by themselves, waiting is the whole plan
        if (!s.onGround()) return Verdict.WAIT;
        if (!s.pathLive()) {
            // nothing is steering us. water and ladders only matter to a movement that wants full speed
            return s.speed() > STILL_SPEED ? Verdict.WAIT : Verdict.EAT_NOW;
        }
        // a live path in water, on a ladder or in a boat is mid movement by definition, and nobody pauses a swim to chew
        if (s.inWater() || s.onLadder() || s.riding()) return Verdict.WAIT;
        if (s.pathPaused()) {
            return s.speed() > STILL_SPEED ? Verdict.ASK_STOP : Verdict.EAT_NOW;
        }
        // the movement says no (parkour past its run up, a jump in the air). asking anyway would leave a latch behind
        // that gets granted at some random later point
        return s.safeToCancel() ? Verdict.ASK_STOP : Verdict.WAIT;
    }

    // why we are not eating yet, for the log line. only built when the line is going out
    public static String why(Scene s) {
        if (s.screen()) return "screen";
        if (!s.onGround()) return "mid-air";
        if (!s.pathLive()) return "moving";
        if (s.inWater()) return "water";
        if (s.onLadder()) return "ladder";
        if (s.riding()) return "boat";
        if (s.pathPaused()) return "sliding to a stop";
        return s.safeToCancel() ? "pause not granted" : "movement not safe";
    }

    // counts the ticks we have wanted a stop and not had one. a long swim or a fall chain can take a while, and eating on
    // the move is not the answer to that, so all this does is say so once
    public static final class Waiting {
        private int ticks;
        private boolean logged;

        // the line to log, or null
        public String tick(Verdict verdict, String why) {
            if (verdict != Verdict.ASK_STOP && verdict != Verdict.WAIT) {
                reset();
                return null;
            }
            ticks++;
            if (ticks >= WAIT_LOG_TICKS && !logged) {
                logged = true;
                return "food: waiting for a safe stop, " + ticks + "t (" + why + ")";
            }
            return null;
        }

        public int ticks() {
            return ticks;
        }

        public void reset() {
            ticks = 0;
            logged = false;
        }
    }

    public enum Press {
        HOLD,
        // let go of the key for this tick, the next hold is a clean rising edge
        PRESS_AGAIN,
        // it will not start, stop trying for BACKOFF_TICKS
        BACK_OFF
    }

    // the bite that never starts: the key is down and the game never begins to use the item. a stale key (down with no
    // rising edge) is the lead suspect, so the cure is letting go and pressing again. fed once per tick that we hold
    public static final class UseWatch {
        private int idle;
        private int retries;
        private int backoff;
        private int steps;
        private int startedAt;
        private boolean wasUsing;
        private boolean started;

        public Press step(boolean using) {
            started = using && !wasUsing;
            wasUsing = using;
            if (started) {
                startedAt = steps;
            }
            steps++;
            if (using) {
                idle = 0;
                retries = 0;
                return Press.HOLD;
            }
            idle++;
            if (idle < USE_START_TICKS) return Press.HOLD;
            idle = 0;
            if (retries >= RETRIES) {
                backoff = BACKOFF_TICKS;
                retries = 0;
                return Press.BACK_OFF;
            }
            retries++;
            return Press.PRESS_AGAIN;
        }

        // ticks between the first step of this meal and the bite really starting, a bite that dragged its feet shows up here
        public int ticksToStart() {
            return startedAt;
        }

        public boolean startedThisTick() {
            return started;
        }

        public int retries() {
            return retries;
        }

        public boolean backingOff() {
            return backoff > 0;
        }

        // the clock runs whether or not anyone is eating, that is the point of a back off
        public void tickBackoff() {
            if (backoff > 0) backoff--;
        }

        // a meal ended. a back off in progress is not part of the meal and keeps counting
        public void reset() {
            idle = 0;
            retries = 0;
            steps = 0;
            startedAt = 0;
            wasUsing = false;
            started = false;
        }

        public void clear() {
            reset();
            backoff = 0;
        }
    }
}
