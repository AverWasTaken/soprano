package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// the block we are in the middle of breaking is not up for re-picking. the pick used to be redone every tick and a drop
// one or two blocks away won it as often as the block did, so the bot flipped between Destroy block and Pickup 12 times
// in 8 seconds, and every flip let go of the mouse button, which makes vanilla throw the crack away. the controller says
// which block is being broken, but only for 500 ms after the last tick of it (PlayerExtraController), and a pause for a
// tool swap or a step eats that, so this keeps its own memory of it for a while longer. pure, the caller feeds it ticks
public final class MineStick {
    // 1.5 s. longer than any pause we cause ourselves, shorter than anything worth staying for
    public static final int GRACE_TICKS = 30;

    private BlockPos pos;
    private int seenTick;

    // the controller says this block is being broken right now (and it is one of ours: the caller checks that)
    public void breaking(int now, BlockPos at) {
        if (at != null) {
            pos = at;
            seenTick = now;
        }
    }

    // the block to stay on, or null. `stillGood` is the caller's idea of "still ours, still there, still in reach": a
    // block that broke fails it, which is the moment the drops get their turn
    public BlockPos holdOn(int now, Predicate<BlockPos> stillGood) {
        if (pos == null) {
            return null;
        }
        if (now - seenTick > GRACE_TICKS || !stillGood.test(pos)) {
            pos = null;
            return null;
        }
        return pos;
    }

    // is this the block holdOn last handed out (the pursuit logic asks, to let it through the new-target rule)
    public boolean isHolding(BlockPos at) {
        return pos != null && pos.equals(at);
    }

    public void clear() {
        pos = null;
    }

    // a target we have never tried has to be at least twice as close (distance, so a quarter of it squared) to take over
    // from the one we are on. same idea as the divide by 4 in AbstractDoToClosestObjectTask for the ones it remembers
    public static boolean clearlyCloser(double newDistSq, double currentDistSq) {
        return newDistSq * 4 <= currentDistSq;
    }

    // don't swap the tool in hand while a crack is on the block: a different item resets the progress, and the slower
    // break with what we hold finishes sooner than a faster one started over
    public static boolean toolSwapWouldReset(boolean breakingNow, BlockPos breakingPos, double progress, BlockPos target) {
        return breakingNow && progress > 0 && breakingPos != null && breakingPos.equals(target);
    }
}
