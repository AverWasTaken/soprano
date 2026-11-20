package adris.altoclef.tasks.speedrun.gamer;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

// the bed by the overworld nether portal, the rules half (tasks/PortalBedStep does the walking). a nether death lands us there
// and not at the world spawn, which is the whole point. pure so the order and the retry are testable without a game
public final class PortalBedRules {
    // one try and one retry, then the nether gets entered anyway with a loud line. a bed is worth a minute, not the run
    public static final int MAX_TRIES = 2;
    // after a give up the next way in may look again this much later (game ticks): the food trips all come back through here
    public static final long RETRY_AFTER_TICKS = 20 * 60 * 5;
    // a bed this close to the portal (flat, either axis) counts as the one standing by it
    public static final int STANDING_NEAR = 12;

    // the ring of columns the "is there a way up out of here" look walks, this far out (flat, either axis) from the portal
    public static final int RIM = 12;
    // what the surface reader says for a column it can't see (unloaded chunk). skipped, an unloaded chunk reads as the floor of the world
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private PortalBedRules() {
    }

    // how far under the surface the portal is, the larger of two looks. the columns right around it catch a portal under rock:
    // the middle of the 3x3, not the lowest, because the way down to a pool is often a 1x1 shaft right next to the frame, and one
    // open column must not make a cave portal look like a field. a surface portal has six open columns around it, so its frame
    // still can't outvote the ground. the lowest column on the rim catches a pit: the sky is open over the portal but every way
    // out is more than a climb up. surfaceAt(x, z) is the first free y over the top solid block (SmeltSurface.depth), UNKNOWN when
    // it can't tell. nothing known = 0, the bed goes down
    public static int portalDepth(int x, int y, int z, IntBinaryOperator surfaceAt) {
        int[] around = new int[9];
        int known = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int h = surfaceAt.applyAsInt(x + dx, z + dz);
                if (h != UNKNOWN) {
                    around[known++] = h;
                }
            }
        }
        // the lower middle when it's even, a column we can't see doesn't push it towards "buried"
        Arrays.sort(around, 0, known);
        int over = known == 0 ? UNKNOWN : around[(known - 1) / 2];
        int rim = UNKNOWN;
        for (int i = -RIM; i <= RIM; i++) {
            rim = lowest(rim, surfaceAt.applyAsInt(x + i, z - RIM));
            rim = lowest(rim, surfaceAt.applyAsInt(x + i, z + RIM));
            rim = lowest(rim, surfaceAt.applyAsInt(x - RIM, z + i));
            rim = lowest(rim, surfaceAt.applyAsInt(x + RIM, z + i));
        }
        int depth = 0;
        if (over != UNKNOWN) {
            depth = Math.max(depth, SmeltSurface.depth(over, y));
        }
        if (rim != UNKNOWN) {
            depth = Math.max(depth, SmeltSurface.depth(rim, y));
        }
        return depth;
    }

    private static int lowest(int sofar, int h) {
        if (h == UNKNOWN) {
            return sofar;
        }
        return sofar == UNKNOWN ? h : Math.min(sofar, h);
    }

    // a respawn down here comes back with an empty bag, and a bare hand can't dig up out of a mine in any time that matters (it
    // can't pillar either, no blocks). same line as the smelt climb: deeper than this is a mine, not a ditch
    public static boolean tooDeep(int depth) {
        return depth > SmeltSurface.GO_UP_DEPTH;
    }

    public enum Source {
        STANDING, BAG, CRAFT, NONE
    }

    // a bed already standing by the portal costs nothing (the task clicks it before it ever looks at the bag), then one from the
    // bag (borrowed from the End's, END_PREP buys it back), then one crafted from the bag's wool. never a hunt for one
    public static Source source(boolean standing, int bagBeds, boolean canCraft) {
        if (standing) {
            return Source.STANDING;
        }
        if (bagBeds > 0) {
            return Source.BAG;
        }
        return canCraft ? Source.CRAFT : Source.NONE;
    }

    // a craft needs three wool of one colour and three planks after the table's cut (BedCraft.usableWood), nothing gets hunted for
    public static boolean canCraft(int bestWoolStack, int usableWood) {
        return bestWoolStack >= 3 && usableWood >= 3;
    }

    public enum After {
        DONE, RETRY, GIVE_UP
    }

    // what a finished try leads to. confirmed = the game says we respawn by the portal now, not "the task said done" (it says done
    // on its own 5 s guess and on nowhere to put a bed too)
    public static After after(boolean confirmed, int tries) {
        if (confirmed) {
            return After.DONE;
        }
        return tries < MAX_TRIES ? After.RETRY : After.GIVE_UP;
    }
}
