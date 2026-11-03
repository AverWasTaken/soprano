package adris.altoclef.tasks.speedrun.gamer.end;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// where the pillar fight stands and clicks, and when. the bed goes on top of the bedrock pillar with us tucked against it one
// below the top, so the pillar eats the blast and all we get is the flat bit. pure so the task and the tests agree
public final class PillarBed {
    public enum Step {
        // sat down: the head is out of range from the stand and the wings shove anything by the body
        RING,
        STAND,
        // up in the air and we're there already. a walk that starts with the landing loses to the clouds
        WAIT,
        PLACE,
        CLICK
    }

    // the head is only ever this close while it comes in over the pillar
    public static final double HEAD_RANGE = 5.3;
    // LivingEntity.hurtServer: for the first 10 ticks after a hit (invulnerableTime > 10) a new hit only counts for what it adds over
    // the last one, and one bed adds nothing over another. one more tick so a blast that lands late isn't still inside it
    public static final int CLICK_SPACING = 11;
    public static final int PLACE_EVERY = 2;
    private static final double STAND_REACH = 0.5;
    // the dragon's feet this close to the pillar top is sat down, whatever the phase still says
    private static final int SAT_ABOVE_TOP = 2;

    private PillarBed() {
    }

    public static BlockPos stand(BlockPos portalTop) {
        return portalTop.offset(-1, -1, 0);
    }

    public static BlockPos bed(BlockPos portalTop) {
        return portalTop.above();
    }

    public static boolean sat(boolean sitting, double dragonY, int portalTopY) {
        return sitting || dragonY < portalTopY + SAT_ABOVE_TOP;
    }

    // feet too: the well floor right under the stand passes the flat check and the bed is out of reach from down there
    public static boolean atStand(BlockPos stand, double x, double z, int feetY) {
        double dx = x - (stand.getX() + 0.5);
        double dz = z - (stand.getZ() + 0.5);
        return dx * dx + dz * dz < STAND_REACH * STAND_REACH && feetY >= stand.getY();
    }

    public static Step step(boolean sat, boolean landing, boolean atStand, boolean bedDown) {
        if (sat) return Step.RING;
        if (!atStand) return Step.STAND;
        if (!landing) return Step.WAIT;
        return bedDown ? Step.CLICK : Step.PLACE;
    }

    // the bed's head half goes the way we face and it has to land past the pillar, that's where it explodes
    public static boolean placeNow(Direction facing, long tick) {
        return facing == Direction.EAST && tick % PLACE_EVERY == 0;
    }

    // the blast starts at the head half, so the foot sits on the pillar and the head hangs off the far side from us. anything
    // else has nothing between us and it
    public static boolean rightWay(Direction facing, boolean foot) {
        return foot && facing == Direction.EAST;
    }

    // on the ground: in the air the pillar stops covering us
    public static boolean clickNow(boolean grounded, double headDistance, long tick, long lastClick) {
        return grounded && headDistance < HEAD_RANGE && tick - lastClick >= CLICK_SPACING;
    }
}
