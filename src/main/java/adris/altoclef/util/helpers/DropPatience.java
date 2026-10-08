package adris.altoclef.util.helpers;

import java.util.HashSet;
import java.util.Set;

// a drop we have started walking to stays the answer until it is in the bag, gone, or we ran out of patience. before this
// every tick re-asked "what is nearest" and a block one step from the drop could take it away halfway (raw_iron picked up,
// interrupted by the ore next to it, picked up again, three times in three seconds). the patience is for the drop that
// sits in leaves or a hole and never comes: after 5 seconds it is somebody else's problem. pure, the caller says when a tick went by
public final class DropPatience {
    public static final int TICKS = 100;

    private final Set<Integer> gaveUp = new HashSet<>();
    private int lockedId = -1;
    private int waited;

    // we are going for this drop (entity id). asking again for the same one does not restart the clock
    public void lock(int id) {
        if (lockedId != id) {
            lockedId = id;
            waited = 0;
        }
    }

    // one tick spent on the locked drop. a counter and not a start time so a pause (eating, a fight) doesn't eat the patience
    public void tick() {
        waited++;
    }

    public boolean isLocked() {
        return lockedId != -1;
    }

    public int lockedId() {
        return lockedId;
    }

    public boolean expired() {
        return isLocked() && waited > TICKS;
    }

    // gone, picked up, or no longer ours to chase. not a failure
    public void unlock() {
        lockedId = -1;
    }

    // the locked drop never came. never lock on it again
    public void giveUp() {
        if (isLocked()) {
            gaveUp.add(lockedId);
        }
        unlock();
    }

    public boolean gaveUp(int id) {
        return gaveUp.contains(id);
    }

    public void clear() {
        gaveUp.clear();
        unlock();
    }

    // vanilla picks an item up when it is inside the player box grown by 1 sideways and half a block up and down, so a drop
    // this close is in the bag in a few ticks without us walking at it, and walking at it lets go of the mouse button.
    // dx, dy, dz are drop minus us (feet)
    public static boolean alreadyInReach(double dx, double dy, double dz) {
        return Math.abs(dx) <= 1.0 && Math.abs(dz) <= 1.0 && dy >= -0.5 && dy <= 1.8;
    }
}
