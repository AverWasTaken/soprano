package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.world.FrameGeometry;
import net.minecraft.world.item.Items;

// the eye arithmetic from the route notes: one eye = one blaze powder + one pearl, one rod = two powder.
// "needed" numbers are totals to HOLD (rods in the bag, not rods still to find), because that is how the
// collect tasks count too
public final class EyeMath {
    public static int rodsNeeded(int targetEyes, int eyes, int powder) {
        int powderWanted = targetEyes - eyes - powder;
        return powderWanted <= 0 ? 0 : (powderWanted + 1) / 2;
    }

    public static int pearlsNeeded(int targetEyes, int eyes) {
        return Math.max(0, targetEyes - eyes);
    }

    // how many more eyes the bag can turn into right now
    public static int craftable(int pearls, int powder, int rods) {
        return Math.max(0, Math.min(pearls, powder + 2 * rods));
    }

    public static int eyes(GamerFacts f) {
        return f.count(Items.ENDER_EYE);
    }

    public static boolean enoughFor(int goalEyes, GamerFacts f) {
        int eyes = eyes(f);
        int powder = f.count(Items.BLAZE_POWDER);
        return f.count(Items.BLAZE_ROD) >= rodsNeeded(goalEyes, eyes, powder)
                && f.count(Items.ENDER_PEARL) >= pearlsNeeded(goalEyes, eyes);
    }

    // the bag only has to cover the frames that are still empty. an OPEN that filled 5 and ran dry sends us back for 7
    // more, not for another 14 it would sit out the whole budget hunting. the spare (target - floor) stays on top
    public static int targetGoal(GamerConfig cfg, int framesFilled) {
        int spare = Math.max(0, cfg.targetEyes - cfg.floorEyes);
        return Math.min(cfg.targetEyes, emptyFrames(framesFilled) + spare);
    }

    public static int floorGoal(GamerConfig cfg, int framesFilled) {
        return Math.min(cfg.floorEyes, emptyFrames(framesFilled));
    }

    private static int emptyFrames(int framesFilled) {
        return Math.max(0, FrameGeometry.FRAME_COUNT - framesFilled);
    }

    // the nether is done when we hold what the full target needs, or when the budget ran out and what we hold
    // still makes the floor (12: the portal needs 12 minus the pre filled ones, so 12 is "probably fine")
    public static boolean canLeaveNether(GamerFacts f, GamerConfig cfg, int framesFilled, boolean budgetOver) {
        if (enoughFor(targetGoal(cfg, framesFilled), f)) {
            return true;
        }
        return budgetOver && enoughFor(floorGoal(cfg, framesFilled), f);
    }

    // eyes phase exit: the target, or the floor with nothing left to craft from
    public static boolean eyesDone(GamerFacts f, GamerConfig cfg, int framesFilled) {
        int eyes = eyes(f);
        if (eyes >= targetGoal(cfg, framesFilled)) {
            return true;
        }
        return eyes >= floorGoal(cfg, framesFilled) && craftableNow(f) == 0;
    }

    public static int craftableNow(GamerFacts f) {
        return craftable(f.count(Items.ENDER_PEARL), f.count(Items.BLAZE_POWDER), f.count(Items.BLAZE_ROD));
    }

    // eyes + what we could still craft, the number the eyes phase regress rule cares about
    public static int potentialEyes(GamerFacts f) {
        return eyes(f) + craftableNow(f);
    }

    private EyeMath() {
    }
}
