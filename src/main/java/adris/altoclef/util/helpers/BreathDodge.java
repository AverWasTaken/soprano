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
    // the last water bucket in the End is the fall bucket for every fling, so that one is worth a short walk back
    public static final double END_LAST_PICKUP_REACH = 12;
    public static final double END_LAST_PICKUP_SECONDS = 10;

    public static final int STUCK_RUN_TICKS = 40;
    // obsidian is 50, bedrock is -1
    public static final float HARD_BLOCK_HARDNESS = 50;

    private BreathDodge() {
    }

    // a drop off the side spends the bucket if the scoop after the clutch misses, and the dragon flings us later with
    // nothing to land on. so a drop only with a second one in the bag
    public static boolean spareWater(int waterBuckets) {
        return waterBuckets >= 2;
    }

    // in the air the clutch owns the fall. a pillar top has nowhere to run to (the goal is never reachable), so it is
    // either off the side on purpose with a spare bucket waiting, or down through our own feet
    public static Move choose(boolean inCloud, boolean grounded, boolean floorAround, boolean spareWater) {
        if (!inCloud || !grounded) {
            return Move.NONE;
        }
        if (floorAround) {
            return Move.RUN;
        }
        return spareWater ? Move.DROP : Move.DIG;
    }

    // a run that is still in the cloud and has not moved a block by now has nowhere to go (a wide pillar counts its own top
    // as floor). off the edge needs water, digging needs something we can dig in a few seconds. obsidian takes ~9 s and a
    // drop without water off a spike kills, so there the run stays
    public static Move escalate(Move move, boolean stuck, boolean spareWater, boolean hardBelow) {
        if (move == Move.DIG && hardBelow) {
            return spareWater ? Move.DROP : Move.RUN;
        }
        if (move != Move.RUN || !stuck) {
            return move;
        }
        if (spareWater) {
            return Move.DROP;
        }
        return hardBelow ? Move.RUN : Move.DIG;
    }

    public static boolean pickupExpired(boolean inEnd, double seconds, double distance) {
        return pickupExpired(inEnd, false, seconds, distance);
    }

    // lastBucket: no water bucket left in the bag, this water is the only one we have in the End
    public static boolean pickupExpired(boolean inEnd, boolean lastBucket, double seconds, double distance) {
        if (inEnd && lastBucket) {
            return distance > END_LAST_PICKUP_REACH || seconds > END_LAST_PICKUP_SECONDS;
        }
        if (inEnd) {
            return distance > END_PICKUP_REACH || seconds > END_PICKUP_SECONDS;
        }
        return seconds > PICKUP_SECONDS;
    }
}
