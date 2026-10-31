package adris.altoclef.util.helpers;

// stepping out of dragon breath from the survival chain, and when to quit fetching the water we dunked in. pure
public final class BreathDodge {

    public enum Move {
        NONE, RUN, DROP, DIG
    }

    // the End has no water to refill from, so a bucket pickup only happens while it is a free action
    public static final double END_PICKUP_REACH = 4.5;
    public static final double END_PICKUP_SECONDS = 3;
    public static final double PICKUP_SECONDS = 10;

    public static final int STUCK_RUN_TICKS = 40;
    // obsidian is 50, bedrock is -1
    public static final float HARD_BLOCK_HARDNESS = 50;

    private BreathDodge() {
    }

    // in the air the clutch owns the fall. a pillar top has nowhere to run to (the goal is never reachable), so it is
    // either off the side on purpose with a bucket waiting, or down through our own feet
    public static Move choose(boolean inCloud, boolean grounded, boolean floorAround, boolean hasWaterBucket) {
        if (!inCloud || !grounded) {
            return Move.NONE;
        }
        if (floorAround) {
            return Move.RUN;
        }
        return hasWaterBucket ? Move.DROP : Move.DIG;
    }

    // a run that is still in the cloud and has not moved a block by now has nowhere to go (a wide pillar counts its own top
    // as floor). off the edge needs water, digging needs something we can dig in a few seconds. obsidian takes ~9 s and a
    // drop without water off a spike kills, so there the run stays
    public static Move escalate(Move move, boolean stuck, boolean hasWaterBucket, boolean hardBelow) {
        if (move == Move.DIG && hardBelow) {
            return hasWaterBucket ? Move.DROP : Move.RUN;
        }
        if (move != Move.RUN || !stuck) {
            return move;
        }
        if (hasWaterBucket) {
            return Move.DROP;
        }
        return hardBelow ? Move.RUN : Move.DIG;
    }

    public static boolean pickupExpired(boolean inEnd, double seconds, double distance) {
        if (inEnd) {
            return distance > END_PICKUP_REACH || seconds > END_PICKUP_SECONDS;
        }
        return seconds > PICKUP_SECONDS;
    }
}
