package adris.altoclef.util.helpers;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

// the pack zones as a wall for a run (see RunZones.blocksAt). a run's goal is "anywhere far enough", so there is no goal to
// exempt: a run that has to cross a pack to get its 24 blocks is a run that should stop short and fight where it is. one pack
// at full hp beats running into a second one. the predicate is asked from baritone's thread, the client thread copies in
// where we stand every tick
public final class ZoneWall implements Predicate<BlockPos> {

    private final RunZones zones;
    private volatile boolean on;
    private volatile boolean nether;
    private volatile long now;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    // client thread only: where the run last got anywhere, for gaveUp
    private long stillSince = Long.MIN_VALUE;
    private double stillX;
    private double stillZ;

    public ZoneWall() {
        this(RunZones.INSTANCE);
    }

    public ZoneWall(RunZones zones) {
        this.zones = zones;
    }

    public void note(boolean nether, long now, double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.now = now;
        this.nether = nether;
        this.on = true;
    }

    // the run is over. its level gets popped too, this is for the copies: a level pushed on top of ours took our lists with it
    public void off() {
        on = false;
    }

    // a run that hasn't gained STILL_BLOCKS in patience ticks with the wall up is walled in. for a run nothing else ends (the
    // blaze fight's, the commitment has its own corner rules) that's being shot at a wall for as long as a blaze can see us,
    // so the wall goes and it's the old run again. a clock going backwards is a new world, start over
    public static final double STILL_BLOCKS = 1.5;

    public boolean gaveUp(long now, double px, double pz, long patience) {
        if (stillSince == Long.MIN_VALUE || now < stillSince || Math.hypot(px - stillX, pz - stillZ) >= STILL_BLOCKS) {
            stillSince = now;
            stillX = px;
            stillZ = pz;
            return false;
        }
        return now - stillSince >= patience;
    }

    // a new run, a new clock
    public void resetClock() {
        stillSince = Long.MIN_VALUE;
    }

    public boolean blocks(int bx, int by, int bz) {
        return on && nether && zones.blocksAt(bx, by, bz, now, x, y, z, Double.NaN, Double.NaN);
    }

    @Override
    public boolean test(BlockPos pos) {
        return blocks(pos.getX(), pos.getY(), pos.getZ());
    }
}
