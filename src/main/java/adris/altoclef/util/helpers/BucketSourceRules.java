package adris.altoclef.util.helpers;

// which liquid sources are worth walking to with a bucket. nearest-wins used to pick a source with a block on top of it
// and no way to get at that block, over an open pool two steps further. this is the "can we get at it" half, in plain
// is-it-solid / is-it-open answers so it can be tested without a world
public final class BucketSourceRules {

    private BucketSourceRules() {
    }

    // OPEN: a bucket ray can reach it as it is. LID: only after the block over it is dug out, and that block can be reached
    // from the side. NONE: not a candidate
    public enum Kind { OPEN, LID, NONE }

    public interface Cells {
        // a full block, the kind that sits on a source and hides it (and the kind the lid digging is about)
        boolean solid(int x, int y, int z);

        // nothing in the way of a ray or a body: air, fluid, plants
        boolean open(int x, int y, int z);

        // open and dry, somewhere a body can stand or swing from. a lava or water cell is open to a ray, not to us
        default boolean free(int x, int y, int z) {
            return open(x, y, z);
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static Kind classify(Cells cells, int x, int y, int z) {
        if (!cells.solid(x, y + 1, z)) {
            return openToTheOutside(cells, x, y, z) ? Kind.OPEN : Kind.NONE;
        }
        // a lid. digging it from the side needs a side cell of the lid itself to stand in or swing from. a cell that only
        // touches the lid across a corner is how a source ends up "visible" and unreachable: the path search never has
        // anywhere to take us, so those don't count
        for (int[] side : SIDES) {
            if (cells.free(x + side[0], y + 1, z + side[1])) {
                return Kind.LID;
            }
        }
        return Kind.NONE;
    }

    // some face of the source has air, fluid or a plant on it, so there's a spot to scoop it from. (glass over a pool is not
    // solid for the lid logic but it isn't open either)
    private static boolean openToTheOutside(Cells cells, int x, int y, int z) {
        if (cells.open(x, y + 1, z)) {
            return true;
        }
        for (int[] side : SIDES) {
            if (cells.open(x + side[0], y, z + side[1])) {
                return true;
            }
        }
        return false;
    }

    // a lid source only counts while no open one is known: digging for lava is the slow way, and a pool we can just scoop
    // from the edge beats a nearer lidded source every time
    public static boolean worthGoingTo(Kind kind, boolean openSourceKnown) {
        return kind == Kind.OPEN || kind == Kind.LID && !openSourceKnown;
    }
}
