package baritone.utils;

import adris.altoclef.Debug;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// a path that stops steering while we hang off a ledge on sneak used to let go of sneak that same tick, and the leftover
// creep took the last hundredth of a block of overlap with it. this keeps sneak down and walks us back onto the block we
// are hanging off. it lives in the input handler and is applied where PlayerMovementInput reads the keys, after every
// clearAllKeys of the tick, so no cancel path can eat it. the path waits (PathingBehavior.tickPath), searches go on
public final class EdgeSettle {

    // the foot row plus three under it: a drop of three or less costs nothing
    static final int DROP_ROWS = 4;
    // how far inside the support the centre has to get before we let go
    static final double INSIDE = 0.2;
    // a walk that hasn't made it by now isn't going to, just stand still on sneak
    static final int WALK_TICKS = 40;
    static final int CAP_TICKS = 100;
    // under this the key quantizes to the other axis. sin 22.5, so we press the closest of the eight directions
    private static final double AXIS = Math.sin(Math.toRadians(22.5));

    public enum End { NONE, SETTLED, AIRBORNE, BLOCKED, HUMAN, CHANGED, STOPPED, CAP }

    public record Keys(boolean forward, boolean back, boolean left, boolean right) {
        public static final Keys NONE = new Keys(false, false, false, false);
    }

    public interface Probe {
        // something in block (x, y, z) collides somewhere in the column at (px, pz)
        boolean floorAt(int x, int y, int z, double px, double pz);

        // lava, fire or magma, a floor that isn't one
        boolean hurts(int x, int y, int z);
    }

    public interface Solid {
        // block (x, y, z) has a collision shape at all
        boolean at(int x, int y, int z);
    }

    // ---- pure half

    // the centre is out over nothing worth landing on: no floor in the foot row or the three below it, or something that
    // burns on the way down. y is the feet
    public static boolean hanging(double cx, double y, double cz, Probe probe) {
        int bx = (int) Math.floor(cx);
        int bz = (int) Math.floor(cz);
        int foot = (int) Math.floor(y - 0.01);
        for (int dy = 0; dy < DROP_ROWS; dy++) {
            if (probe.hurts(bx, foot - dy, bz)) {
                return true;
            }
            if (probe.floorAt(bx, foot - dy, bz, cx, cz)) {
                return false;
            }
        }
        return true;
    }

    // the block in the foot row with the most of our footprint over it. null when nothing under the box collides
    public static BlockPos support(double minX, double maxX, double minZ, double maxZ, int row, Solid solid) {
        BlockPos best = null;
        double bestArea = 0;
        // the max edge sits exactly on a block line when the box is flush, that block isn't under us
        for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX - 1.0E-7); x++) {
            for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ - 1.0E-7); z++) {
                double area = (Math.min(maxX, x + 1) - Math.max(minX, x)) * (Math.min(maxZ, z + 1) - Math.max(minZ, z));
                if (area > bestArea && solid.at(x, row, z)) {
                    bestArea = area;
                    best = new BlockPos(x, row, z);
                }
            }
        }
        return best;
    }

    // which keys walk (dx, dz) with this move yaw. same rotation as Entity.getInputVector: forward is (-sin, cos), left is
    // (cos, sin)
    public static Keys keysToward(float yaw, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6) {
            return Keys.NONE;
        }
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double f = (-dx * sin + dz * cos) / len;
        double l = (dx * cos + dz * sin) / len;
        return new Keys(f >= AXIS, f <= -AXIS, l >= AXIS, l <= -AXIS);
    }

    public static boolean inside(double cx, double cz, BlockPos support) {
        return cx >= support.getX() + INSIDE && cx <= support.getX() + 1 - INSIDE
                && cz >= support.getZ() + INSIDE && cz <= support.getZ() + 1 - INSIDE;
    }

    // steering = a movement pressed a direction or jump this tick, or the same executor ran a tick that just had no keys
    // (a look wait mid back-place). latched = the last settle on this support gave up and we haven't moved off it since
    public static boolean mayEngage(boolean shiftLastTick, boolean sneakForced, boolean steering, boolean onGround,
                                    boolean blocked, boolean humanKey, boolean latched, boolean hanging) {
        return shiftLastTick && !sneakForced && !steering && onGround && !blocked && !humanKey && !latched && hanging;
    }

    // ticks counts from the engage tick (0). the walk stops at WALK_TICKS, after that we only wait for the creep to die
    // (vanilla zeroes anything under 0.003 a tick, so a real stop reads as exactly 0)
    public static End end(int ticks, boolean changed, boolean onGround, boolean blocked, boolean humanKey, boolean inside,
                          double horizontalSpeed) {
        if (changed) return End.CHANGED;
        if (!onGround) return End.AIRBORNE;
        if (blocked) return End.BLOCKED;
        if (humanKey) return End.HUMAN;
        if (inside) return End.SETTLED;
        if (ticks >= CAP_TICKS) return End.CAP;
        if (ticks >= WALK_TICKS && horizontalSpeed < 1.0E-6) return End.STOPPED;
        return End.NONE;
    }

    public static boolean walking(int ticks) {
        return ticks < WALK_TICKS;
    }

    // a settle that gave up leaves us hanging, and the next idle tick would start it again on the same block for ever.
    // so not again until we have been off the ground or stand on something else
    public static final class Latch {
        private BlockPos support;

        public void set(BlockPos support) {
            this.support = support;
        }

        public boolean blocks(boolean onGround, BlockPos now) {
            if (support == null) {
                return false;
            }
            if (!onGround || now == null || !now.equals(support)) {
                support = null;
                return false;
            }
            return true;
        }

        public boolean armed() {
            return support != null;
        }

        public void clear() {
            support = null;
        }
    }

    // ---- live half

    private final Latch latch = new Latch();
    private boolean active;
    private int ticks;
    private BlockPos support;
    private LocalPlayer player;
    private Level level;

    public boolean active() {
        return active;
    }

    public void reset() {
        active = false;
        latch.clear();
        player = null;
        level = null;
    }

    // once a tick from InputOverrideHandler.onTick, after the path and every process had their say
    public void tick(LocalPlayer p, Level world, Options options, boolean shiftLastTick, boolean sneakForced, boolean steering) {
        if (active) {
            ticks++;
            boolean changed = p != player || world != level;
            Vec3 vel = changed ? Vec3.ZERO : p.getDeltaMovement();
            End why = end(ticks, changed, !changed && p.onGround(), !changed && blocked(p), !changed && humanKey(options),
                    !changed && inside(p.getX(), p.getZ(), support), Math.hypot(vel.x, vel.z));
            if (why != End.NONE) {
                stop(why);
            }
            return;
        }
        if (p == null || world == null) {
            return;
        }
        // the latch watches where we stand every tick, a path walking us to another block and back clears it too
        BlockPos under = null;
        boolean latched = false;
        if (latch.armed()) {
            under = supportUnder(p, world);
            latched = latch.blocks(p.onGround(), under);
        }
        // cheap gates first, the world lookups only for a bot that just let go of sneak
        if (!shiftLastTick || sneakForced || steering || !p.onGround() || latched || blocked(p) || humanKey(options)) {
            return;
        }
        if (under == null) {
            under = supportUnder(p, world);
        }
        if (under == null || !mayEngage(true, false, false, true, false, false, false, hanging(p.getX(), p.getY(), p.getZ(), probe(world)))) {
            return;
        }
        active = true;
        ticks = 0;
        support = under;
        player = p;
        level = world;
        Debug.logInternal("edge settle: hanging at " + p.blockPosition().toShortString() + ", support " + under.toShortString());
    }

    // the keys for this tick, read by PlayerMovementInput. yaw is the one moveRelative is about to use
    public Keys keys(float yaw) {
        if (!active || !walking(ticks) || player == null) {
            return Keys.NONE;
        }
        return keysToward(yaw, support.getX() + 0.5 - player.getX(), support.getZ() + 0.5 - player.getZ());
    }

    private void stop(End why) {
        active = false;
        if (why == End.STOPPED || why == End.CAP) {
            latch.set(support);
        }
        Debug.logInternal("edge settle: " + why.name().toLowerCase() + " after " + ticks + " ticks");
        player = null;
        level = null;
    }

    private static BlockPos supportUnder(LocalPlayer p, Level world) {
        AABB box = p.getBoundingBox();
        int row = (int) Math.floor(p.getY() - 0.01);
        return support(box.minX, box.maxX, box.minZ, box.maxZ, row, (x, y, z) -> collides(world, new BlockPos(x, y, z)));
    }

    private static boolean blocked(LocalPlayer p) {
        return p.isInWater() || p.isInLava() || p.onClimbable() || p.isPassenger() || p.getAbilities().flying || p.isFallFlying();
    }

    // the keys a human (or altoclef's InputControls, which presses the same vanilla mappings) holds. sneak is not one of
    // them, holding it is what we are doing anyway
    private static boolean humanKey(Options options) {
        return options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown();
    }

    private static boolean collides(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static Probe probe(Level world) {
        return new Probe() {
            @Override
            public boolean floorAt(int x, int y, int z, double px, double pz) {
                BlockPos pos = new BlockPos(x, y, z);
                VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
                if (shape.isEmpty()) {
                    return false;
                }
                double lx = px - x;
                double lz = pz - z;
                for (AABB b : shape.toAabbs()) {
                    if (lx >= b.minX && lx <= b.maxX && lz >= b.minZ && lz <= b.maxZ) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean hurts(int x, int y, int z) {
                BlockState state = world.getBlockState(new BlockPos(x, y, z));
                return state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof BaseFireBlock || state.is(Blocks.MAGMA_BLOCK);
            }
        };
    }
}
