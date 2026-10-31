package adris.altoclef.tasks.speedrun.gamer.end;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

// when a bed on the pillar top is allowed to go down, and whether the dragon is in the mood to perch around it. pure, the task
// hands in the boxes. the server runs checkWalls on the head, neck and body every tick, sitting or not, and deletes any block
// touching them without a drop, so a bed set down where those boxes are lasts one tick and the click never gets a turn
public final class BedPlaceGate {
    // no bed until the head is this close to the pillar top. beds put down early in the descent were eaten before it arrived
    public static final double PLACE_RANGE = 8;
    // the parts move 0.3 to 0.6 blocks a tick on the way down, so the box we look at is a few ticks stale by the time the
    // bed lands. a sitting dragon does not move, a half block is just a margin
    public static final double MOVING_INFLATE = 1.0;
    public static final double SITTING_INFLATE = 0.5;

    private BedPlaceGate() {
    }

    // the descent is where the bed goes down: set it before the head gets here so the jump and the fall are done before the
    // click window opens (placing at 8 blocks left ten ticks of jumping while the head ate the bed first)
    public static final double APPROACH_RANGE = 24;
    // closer is more damage, so inside this we click no matter what the boxes say
    public static final double CLICK_NEAR = 4.5;
    // past CLICK_NEAR we only click when a part is about to touch the bed, and not from further out than this
    public static final double CLICK_TOUCH_RANGE = 6;
    // client lag plus two ticks of dragon travel
    public static final double CLICK_TOUCH_INFLATE = 1.5;

    public static boolean headNear(double headDistance) {
        return headDistance <= PLACE_RANGE;
    }

    // may the bed go down this tick. a landing always, an approach once the head is within APPROACH_RANGE, a sit (the old
    // rule) once it is within PLACE_RANGE
    public static boolean placeWindow(boolean landing, boolean approach, boolean sitting, double headDistance) {
        if (landing) return true;
        if (approach) return headDistance <= APPROACH_RANGE;
        return sitting && headNear(headDistance);
    }

    // click the bed now. head close enough and it is a hit whatever the boxes say, a bit further out only when the head, neck or
    // body (grown for lag) already touch a bed cell, because the next tick may be the one that eats it
    public static boolean clickNow(double headDistance, List<AABB> parts, BlockPos foot, BlockPos head) {
        if (headDistance <= CLICK_NEAR) return true;
        if (headDistance > CLICK_TOUCH_RANGE) return false;
        AABB footBox = new AABB(foot);
        AABB headBox = new AABB(head);
        for (AABB part : parts) {
            AABB fat = part.inflate(CLICK_TOUCH_INFLATE);
            if (fat.intersects(footBox) || fat.intersects(headBox)) return true;
        }
        return false;
    }

    // why a landing ended with no click, for the log. first match wins
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

    // neither bed cell touches any of the dragon's boxes (head, neck, body), grown by the inflate for how fast they move
    public static boolean clear(List<AABB> parts, BlockPos foot, BlockPos head, boolean sitting) {
        double grow = sitting ? SITTING_INFLATE : MOVING_INFLATE;
        AABB footBox = new AABB(foot);
        AABB headBox = new AABB(head);
        for (AABB part : parts) {
            AABB fat = part.inflate(grow);
            if (fat.intersects(footBox) || fat.intersects(headBox)) return false;
        }
        return true;
    }

    // is this tick a perch. the descent only counts above the pillar top unless a bed is already down (the old rule: the bed is for the way down), a
    // sitting dragon counts whatever its height. but a flaming one is breath on the pillar, and a sitting one whose body is
    // over the pillar top with no bed down yet leaves nowhere to put one: both go back to waiting
    public static boolean perch(boolean landingPhase, boolean sitting, boolean flaming, boolean dragonLow, boolean bedPlaced,
                                boolean cellsClear) {
        if (sitting) {
            if (flaming) return false;
            return bedPlaced || cellsClear;
        }
        // a bed that is down keeps the landing ours when the dragon drops under the pillar top, or the wait task walks us off
        // the stand right when the head comes in range
        return landingPhase && (!dragonLow || bedPlaced);
    }

    // the food chain only steps aside once a bed is down or going down, not for the whole sit
    public static boolean holdFood(boolean bedPlaced, boolean placing) {
        return bedPlaced || placing;
    }
}
