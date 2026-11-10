package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

// walking into an end portal (the stronghold one and the exit fountain), as small pure questions
public final class EndPortalWalk {
    // a lip is anywhere we stand next to (or half over) a portal block: the frame, its eye, the fountain's bedrock rim
    public static final double LIP_REACH = 1.5;
    // feet above the portal block. the eye tops out at +1.0, the frame at +0.81, a pillar or a ledge above the room is not a lip
    public static final double LIP_RISE = 1.5;
    // per tick, flat. baritone walking us in moves way more than this
    public static final double STILL = 0.02;
    // 3 s of standing still on a lip and the goal is clearly not getting us in
    public static final int STEER_AFTER = 60;
    // long enough to cross a rim block twice over, then the normal walk gets another go
    public static final int STEER_FOR = 40;

    private EndPortalWalk() {
    }

    // all four sides are portal: the middle of the stronghold 3x3, the four cells off the fountain pillar's corners. the
    // goal goes one above one of these, because a rim block's above cell is a spot we can stand in without falling in (the
    // eye holds us up until 0.05 blocks into the column, the fountain rim until 0.3) and "arrived" is by block coords.
    // reaching the stronghold middle means crossing a rim cell, so we drop in on the way. the fountain's four touch a bedrock ring
    // corner diagonally, LipSteer covers that one
    public static boolean interior(BlockPos p, Predicate<BlockPos> isPortal) {
        return isPortal.test(p.north()) && isPortal.test(p.south()) && isPortal.test(p.east()) && isPortal.test(p.west());
    }

    // an unloaded neighbour counts as portal, same as DoToClosestBlockTask assuming an unloaded block is still there. far
    // away a rim block can pass for a middle one, the pick gets redone once its chunk shows up
    public static boolean interiorIn(AltoClef mod, BlockPos p) {
        return interior(p, q -> !mod.getChunkTracker().isChunkLoaded(q) || mod.getWorld().getBlockState(q).is(Blocks.END_PORTAL));
    }

    // dx/dz from the portal block's centre, dy from its bottom to our feet
    public static boolean onTheLip(double dx, double dy, double dz) {
        return dx * dx + dz * dz <= LIP_REACH * LIP_REACH && dy >= 0 && dy <= LIP_RISE;
    }

    // ticks in a row standing still on a lip. anything else starts it over
    public static int stillTicks(int ticks, boolean onLip, double moved) {
        return onLip && moved < STILL ? ticks + 1 : 0;
    }

    public static boolean steer(int stillTicks) {
        return stillTicks >= STEER_AFTER;
    }
}
