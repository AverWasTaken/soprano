package adris.altoclef.tasks.speedrun.gamer.end;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// the small rules around a bed perch, pure. the pillar top bed (placed on the way down, clicked when a part touched it) is
// gone: the body lands right over the pillar, so that cell was never clear. the bed goes on the floor under the head now,
// once the yaw is frozen (see BedPairSearch)
public final class BedPlaceGate {

    private BedPlaceGate() {
    }

    // why a perch ended with no click, for the log. first match wins
    public static String lostReason(boolean bedEverDown, boolean pathed, boolean gap, boolean breathNear, boolean endermanNear) {
        if (!bedEverDown) return "no bed";
        if (pathed) return "pathing";
        if (breathNear) return "breath";
        if (endermanNear) return "mobdef";
        return gap ? "clutch" : "no click window";
    }

    // the cell the head half of the bed lands in: the foot plus the way we face once the look has landed (bed rotation follows
    // the placer's yaw, which is why the facing before the lookAt is the wrong one to ask)
    public static BlockPos headCell(BlockPos foot, Direction facing) {
        return foot.relative(facing);
    }

    // is this tick a perch: any of the three sitting phases, flaming included. the bed only goes down once the yaw is frozen
    // (attacking and flaming), scanning still turns to face us, but it is the same sit and the same perch to wait out
    public static boolean perch(boolean scanning, boolean attacking, boolean flaming) {
        return scanning || attacking || flaming;
    }

    // the yaw stops turning from the roar on
    public static boolean yawFrozen(boolean attacking, boolean flaming) {
        return attacking || flaming;
    }
}
