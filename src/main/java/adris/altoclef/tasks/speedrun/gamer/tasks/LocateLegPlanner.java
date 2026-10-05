package adris.altoclef.tasks.speedrun.gamer.tasks;

// the decisions of the locate loop that are not about the world: how long a walk leg may take, when a leg
// counts as stuck, how many bad throws we put up with. pure, the task feeds it clock and distances
public final class LocateLegPlanner {
    // no progress toward the leg target for this long = give up the leg and throw from here
    public static final double STALL_SECONDS = 60;
    // closer than this to the leg target is arrived
    public static final double ARRIVE_BLOCKS = 6;
    // getting this much closer counts as progress
    static final double PROGRESS_BLOCKS = 3;
    // after a failed re-estimate we step this far sideways before the first throw
    public static final double RELOCATE_BLOCKS = 150;

    // generous: baritone walks ~4.3 blocks/s, digging and detours eat the rest
    public static double legBudgetSeconds(double distance) {
        return 60 + distance / 2.5;
    }

    // a walk leg with its own clock. all times are seconds from whatever clock the caller likes
    public static final class Leg {
        private final double startTime;
        private final double budget;
        private double bestDistance;
        private double lastProgress;

        public Leg(double now, double distanceToTarget) {
            startTime = now;
            budget = legBudgetSeconds(distanceToTarget);
            bestDistance = distanceToTarget;
            lastProgress = now;
        }

        public void update(double now, double distanceToTarget) {
            if (distanceToTarget < bestDistance - PROGRESS_BLOCKS) {
                bestDistance = distanceToTarget;
                lastProgress = now;
            }
        }

        public boolean expired(double now) {
            return now - lastProgress >= STALL_SECONDS || now - startTime >= budget;
        }

        public String why(double now) {
            return now - lastProgress >= STALL_SECONDS ? "no progress for " + (int) STALL_SECONDS + " s" : "took too long";
        }
    }

    // counts throws and bad throws. a throw only counts once an eye entity showed up, a dud is free but capped in a row
    public static final class ThrowLedger {
        private final int maxThrows;
        private final int maxEmptyInRow;
        private int counted;
        private int emptyInRow;

        public ThrowLedger(int alreadyCounted, int maxThrows, int maxEmptyInRow) {
            this.counted = alreadyCounted;
            this.maxThrows = maxThrows;
            this.maxEmptyInRow = maxEmptyInRow;
        }

        public void eyeAppeared() {
            counted++;
            emptyInRow = 0;
        }

        public void empty() {
            emptyInRow++;
        }

        public int counted() {
            return counted;
        }

        public boolean tooManyEmpty() {
            return emptyInRow >= maxEmptyInRow;
        }

        public boolean outOfThrows() {
            return counted >= maxThrows;
        }
    }

    // where to stand for the first throw of a second attempt: along the ring tangent, not out and not in,
    // so the new rays still see the same ring of strongholds. at the origin there is no tangent, +x it is
    public static double[] relocateTarget(double fromX, double fromZ, double distance) {
        double len = Math.hypot(fromX, fromZ);
        if (len < 1) {
            return new double[]{fromX + distance, fromZ};
        }
        return new double[]{fromX - fromZ / len * distance, fromZ + fromX / len * distance};
    }

    private LocateLegPlanner() {
    }
}
