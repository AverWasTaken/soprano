package adris.altoclef.util.helpers;

// when DestroyBlockTask swaps a tool in, and when it gives up on swapping. pure, the caller does the clicking.
// a carrot "has a correct tool" in every slot (nothing needs one), every tool is as fast as the next, and the first one in
// the list won. the swap parks the old tool in the slot that is listed first, so the next tick it was the new first,
// and the bot swapped between two swords at 20 hz for 26 s while the carrot sat there
public final class ToolSwap {
    // misses at one item before we stop trying and swing with what we hold
    public static final int GIVE_UP = 3;

    private Object wanted;
    private int misses;

    // swapping is only worth it for a tool that is strictly faster. anything we hold that can do the job as fast stays,
    // so equal speeds never trade places. a hand that can't do the job (no tool, wrong tool) swaps to any best
    public static boolean worthSwapping(boolean instant, Object held, boolean heldCorrect, double heldSpeed, Object best, double bestSpeed) {
        if (instant || best == null || best.equals(held)) {
            return false;
        }
        return !heldCorrect || bestSpeed > heldSpeed;
    }

    // false once this item has missed GIVE_UP times in a row. a different item is a fresh start
    public boolean mayTry(Object best) {
        if (!best.equals(wanted)) {
            wanted = best;
            misses = 0;
        }
        return misses < GIVE_UP;
    }

    // the hand holds it after the click
    public void landed() {
        wanted = null;
        misses = 0;
    }

    // the click went out and the hand still doesn't hold it
    public void missed(Object best) {
        if (best.equals(wanted)) {
            misses++;
        }
    }
}
