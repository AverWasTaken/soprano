/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.sim;

import net.minecraft.util.Mth;

import java.util.Arrays;

// one player on foot, one game tick at a time, the way 26.3 does it. the method names in the comments are the vanilla ones this
// was ported from (LocalPlayer.aiStep, LivingEntity.aiStep / travelInAir / jumpFromGround, Entity.move / collide), floats where
// vanilla has floats since the last digit of a sprint jump decides whether a step is cleared.
// not modelled and bailed on: lava (water is swum through, travelInWater), climbables, ice / slime / honey / soul sand / webs / powder snow, potions that touch
// jump or gravity, sneaking, passengers, elytra. blocks only: other entities (boats, minecarts, shulkers) and the world border
// are walked straight through. a tick() that returns false because it ended somewhere odd has still moved the player
public final class PlayerSim {

    // LivingEntity.SPEED_MODIFIER_SPRINTING, 0.3F as an ADD_MULTIPLIED_TOTAL. the amount is a double so the float widens
    public static final double SPRINT_MODIFIER = 0.3F;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    // Direction.axisStepOrder, as indexes into x y z
    private static final int[] Y_X_Z = {1, 0, 2}, Y_Z_X = {1, 2, 0};

    // what the player is made of: attribute values, minus the sprint modifier, which the sim applies itself the way setSprinting does
    public record Params(float width, float height, float stepHeight, double gravity, double jumpStrength, double walkSpeed, boolean sprintAllowed) {
        public static Params vanilla() {
            return new Params(0.6F, 1.8F, 0.6F, 0.08, 0.42F, 0.1F, true);
        }
    }

    // one tick of keys. forward and left are -1, 0 or 1 (left is A). moveYaw is the yaw moveRelative and jumpFromGround see, which
    // on Baritone is the spoofed one (MixinEntity, MixinLivingEntity). viewYaw is the real getYRot() that the minor collision
    // test reads, because PlayerUpdate PRE only turns the head after aiStep
    public record Controls(int forward, int left, boolean jump, boolean sprint, float moveYaw, float viewYaw) {
        public Controls(int forward, int left, boolean jump, boolean sprint, float yaw) {
            this(forward, left, jump, sprint, yaw, yaw);
        }

        public Controls withJump(boolean jump) {
            return new Controls(forward, left, jump, sprint, moveYaw, viewYaw);
        }
    }

    private final Params params;
    private final CollisionProvider world;
    private final float halfWidth;

    // every tick() ever run, so whoever asks the sim a question can say what it cost (AscendHop's telemetry). the client thread only
    public static long ticksRun;
    // the same count, but only this thread's: a budget measured in sim ticks has to be measured on the thread that spends it
    // (the background neo searches run on their own threads, and would eat everyone else's budget otherwise)
    private static final ThreadLocal<long[]> THREAD_TICKS = ThreadLocal.withInitial(() -> new long[1]);

    public static long ticks() {
        return THREAD_TICKS.get()[0];
    }

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, horizontalCollision, minorHorizontalCollision, verticalCollision;
    // LivingEntity.noJumpDelay, the ticks left before holding space jumps again
    public int noJumpDelay;
    // Entity.mainSupportingBlockPos: the block we're standing on (the nearest one under the box), which is where friction,
    // bounce and the rest are looked up rather than straight under the middle of the box. only while hasSupport
    public boolean hasSupport;
    public int supportX, supportY, supportZ;
    // set by the last tick(): we came down on something bouncy and went back up
    public boolean bounced;
    // standing in a ladder or vine at the end of the last tick
    public boolean climbing;
    // in water at the start of the last tick (Entity.wasTouchingWater), and how deep: the water's surface over the feet
    // (Entity.getFluidHeight). the next tick works these out again from where we are
    public boolean inWater;
    public double waterDepth;
    // set by the last tick(): did it leave the ground, and why it gave up if it did
    public boolean jumped;
    private String failure;

    // scratch, so a hundred ticks of sim is not a hundred thousand allocations. it only lives through one tick(), so it's one
    // set per thread, not per sim: the planners copy sims by the hundred thousand (every rollout, every flight), and each one
    // carrying 3 KB of its own scratch was most of the garbage the background searches made (and the GC pauses that froze the
    // game when one ran, round fences and walls)
    private static final class Scratch {
        final BoxList boxes = new BoxList(), stepBoxes = new BoxList(), cell = new BoxList();
        final double[] box = new double[6], groundedBox = new double[6], swept = new double[6], query = new double[6];
        final double[] step = new double[3], stepped = new double[3], want = new double[3], out = new double[3];
        final int[] cellA = new int[3], cellB = new int[3];
        float[] candidates = new float[8];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private Scratch scr;
    private BoxList boxes, stepBoxes, cell;
    private double[] box, groundedBox, swept, query, step, stepped, want, out;
    private int[] cellA, cellB;
    private float xxa, zza;

    private void bindScratch() {
        Scratch s = SCRATCH.get();
        if (scr == s) {
            return;
        }
        scr = s;
        boxes = s.boxes;
        stepBoxes = s.stepBoxes;
        cell = s.cell;
        box = s.box;
        groundedBox = s.groundedBox;
        swept = s.swept;
        query = s.query;
        step = s.step;
        stepped = s.stepped;
        want = s.want;
        out = s.out;
        cellA = s.cellA;
        cellB = s.cellB;
    }

    public CollisionProvider world() {
        return world;
    }

    public PlayerSim(Params params, CollisionProvider world) {
        this.params = params;
        this.world = world;
        this.halfWidth = params.width() / 2.0F;
    }

    public PlayerSim copy() {
        PlayerSim c = new PlayerSim(params, world);
        c.x = x;
        c.y = y;
        c.z = z;
        c.vx = vx;
        c.vy = vy;
        c.vz = vz;
        c.onGround = onGround;
        c.sprinting = sprinting;
        c.horizontalCollision = horizontalCollision;
        c.minorHorizontalCollision = minorHorizontalCollision;
        c.verticalCollision = verticalCollision;
        c.noJumpDelay = noJumpDelay;
        c.hasSupport = hasSupport;
        c.climbing = climbing;
        c.inWater = inWater;
        c.waterDepth = waterDepth;
        c.supportX = supportX;
        c.supportY = supportY;
        c.supportZ = supportZ;
        return c;
    }

    public Params params() {
        return params;
    }

    // why the last tick() returned false
    public String failure() {
        return failure;
    }

    // the speed attribute as setSprinting leaves it
    private double walkSpeedAttribute(boolean sprinting) {
        return sprinting ? params.walkSpeed() * (1.0 + SPRINT_MODIFIER) : params.walkSpeed();
    }

    // run one tick. false if the player is somewhere the sim does not do the maths for, with nothing changed when that is where
    // the tick started, and the tick done anyway when it ended there
    public boolean tick(Controls c) {
        bindScratch();
        ticksRun++;
        THREAD_TICKS.get()[0]++;
        jumped = false;
        String bad = unsupported();
        if (bad != null) {
            failure = bad;
            return false;
        }
        failure = null;

        // KeyboardInput / PlayerMovementInput: moveVector = new Vec2(left, forward).normalized()
        float lx = c.left(), lz = c.forward();
        float len = Mth.sqrt(lx * lx + lz * lz);
        float mvx = 0.0F, mvz = 0.0F;
        if (!(len < 1.0E-4F)) {
            mvx = lx / len;
            mvz = lz / len;
        }
        boolean hasForwardImpulse = mvz > 1.0E-5F;

        // Entity.updateInWaterStateAndDoFluidPushing (baseTick, before any of the rest)
        updateWater();
        // LocalPlayer.aiStep. canStartSprinting, then shouldStopRunSprinting off last tick's collision flags. in water a
        // sprint is a swim, which the sim doesn't do: no sprinting in water (a jump out of it goes without)
        if (!sprinting && hasForwardImpulse && params.sprintAllowed() && c.sprint() && !inWater) {
            sprinting = true;
        }
        if (inWater) {
            sprinting = false;
        }
        if (sprinting && (!params.sprintAllowed() || !hasForwardImpulse || horizontalCollision && !minorHorizontalCollision)) {
            sprinting = false;
        }

        // LivingEntity.aiStep
        if (noJumpDelay > 0) {
            noJumpDelay--;
        }
        double dx = vx, dy = vy, dz = vz;
        if (vx * vx + vz * vz < 9.0E-6) {
            dx = 0.0;
            dz = 0.0;
        }
        if (Math.abs(vy) < 0.003) {
            dy = 0.0;
        }

        // LocalPlayer.applyInput / modifyInput: 0.98 and then squared off so a diagonal is not slower than it should be
        xxa = mvx;
        zza = mvz;
        if (mvx * mvx + mvz * mvz != 0.0F) {
            float nx = mvx * 0.98F, nz = mvz * 0.98F;
            float length = Mth.sqrt(nx * nx + nz * nz);
            if (length > 0.0F) {
                float inv = 1.0F / length;
                float ux = nx * inv, uz = nz * inv;
                float tan = Math.abs(uz) > Math.abs(ux) ? Math.abs(ux) / Math.abs(uz) : Math.abs(uz) / Math.abs(ux);
                float modified = Math.min(length * Mth.sqrt(1.0F + tan * tan), 1.0F);
                xxa = ux * modified;
                zza = uz * modified;
            } else {
                xxa = nx;
                zza = nz;
            }
        }

        // the jump block: onGround and no delay, or the delay resets the moment the key is up.
        // LivingEntity.aiStep: in water at all, space swims up (jumpInLiquid), and only standing on the bottom in water
        // shallower than getFluidJumpThreshold is it a jump. so out of the top of a water block hanging in the air there's
        // no jump, just the swim carrying on up a little way past the surface
        boolean swimUp = inWater && waterDepth > 0 && !(onGround && waterDepth <= FLUID_JUMP_THRESHOLD);
        if (c.jump() && swimUp) {
            dy += 0.04F;
        } else if (c.jump()) {
            if (onGround && noJumpDelay == 0) {
                float power = (float) params.jumpStrength() * 1.0F * jumpFactor() + 0.0F; // LivingEntity.getJumpPower, no potion
                if (!(power <= 1.0E-5F)) {
                    dy = Math.max((double) power, dy);
                    if (sprinting) { // jumpFromGround, and MixinLivingEntity hands it the same yaw moveRelative gets
                        float angle = c.moveYaw() * DEG_TO_RAD;
                        dx += -Mth.sin(angle) * 0.2;
                        dz += Mth.cos(angle) * 0.2;
                    }
                    jumped = true;
                }
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }

        if (inWater) {
            return travelInWater(c, dx, dy, dz);
        }
        // LivingEntity.travelInAir
        float blockFriction = onGround ? modifiedFriction(frictionBelow()) : 1.0F;
        float accel;
        if (onGround) { // getFrictionInfluencedSpeed. Player.getSpeed() is the attribute, so the sprint flag counts the tick it flips
            float speed = (float) walkSpeedAttribute(sprinting);
            accel = blockFriction > 0.6 ? speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)) : speed;
        } else { // Player.getFlyingSpeed
            accel = sprinting ? 0.025999999F : 0.02F;
        }
        // Entity.moveRelative / getInputVector
        double inLen = (double) xxa * xxa + (double) zza * zza;
        if (!(inLen < 1.0E-7)) {
            double ix = xxa, iz = zza;
            if (inLen > 1.0) {
                double norm = Math.sqrt(inLen);
                ix /= norm;
                iz /= norm;
            }
            ix *= accel;
            iz *= accel;
            float rad = c.moveYaw() * DEG_TO_RAD;
            float sin = Mth.sin(rad), cos = Mth.cos(rad);
            dx += ix * cos - iz * sin;
            dz += iz * cos + ix * sin;
        }
        // LivingEntity.handleOnClimbable: on a ladder or vine sideways speed is capped and the fall is held to 0.15 a tick
        // (sneaking would hold it at 0, the sim never sneaks)
        if (onClimbable()) {
            dx = Mth.clamp(dx, -0.15F, 0.15F);
            dz = Mth.clamp(dz, -0.15F, 0.15F);
            dy = Math.max(dy, -0.15F);
        }

        vx = dx;
        vy = dy;
        vz = dz;
        move(c.viewYaw());
        // handleRelativeFrictionAndCalculateMovement: pushing into something, or holding jump, on a climbable climbs
        climbing = onClimbable();
        if (climbing && (horizontalCollision || c.jump())) {
            vy = 0.2;
        }
        // what is left of the velocity after move (collisions zero the axis they hit) is what travelInAir reads back
        double movementY = vy - params.gravity();
        float airDrag = modifiedFriction(0.91F);
        float friction = blockFriction * airDrag;
        float verticalFriction = modifiedFriction(0.98F);
        vx = vx * friction;
        vy = movementY * verticalFriction;
        vz = vz * friction;
        // LivingEntity.aiStep's applyEffectsFromBlocks after travel: Block.stepOn of what we're standing on. slime's is the one
        // that moves us, it soaks up sideways speed unless we're bouncing
        if (onGround) {
            onPos(0.2F, cellA);
            if (world.slimeStep(cellA[0], cellA[1], cellA[2])) {
                double absVy = Math.abs(vy);
                if (absVy < 0.1) {
                    double scale = 0.4 + absVy * 0.2;
                    vx *= scale;
                    vz *= scale;
                }
            }
        }

        // a tick that ends in or on something the provider doesn't do the maths for (see CollisionProvider.plain) is not ours
        // to answer for
        String ended = endedSomewhereOdd();
        if (ended != null) {
            failure = ended;
            return false;
        }
        return true;
    }

    // in water right where we are now (and how deep, into waterDepth): what the next tick will go by
    public boolean waterNow() {
        bindScratch();
        updateWater();
        return inWater;
    }

    // Entity.getFluidJumpThreshold: the eyes under 0.4 up, or 0.4
    public static final double FLUID_JUMP_THRESHOLD = 0.4;

    // Entity.updateFluidHeightAndDoFluidPushing for water: the box (a hair smaller) against the water's surface in every cell it
    // touches. still water doesn't push
    private void updateWater() {
        double minX = x - halfWidth + 0.001, maxX = x + halfWidth - 0.001, minY = y + 0.001, maxY = y + params.height() - 0.001;
        double minZ = z - halfWidth + 0.001, maxZ = z + halfWidth - 0.001;
        inWater = false;
        waterDepth = 0.0;
        for (int bx = Mth.floor(minX); bx <= Mth.floor(maxX); bx++) {
            for (int by = Mth.floor(minY); by <= Mth.floor(maxY); by++) {
                for (int bz = Mth.floor(minZ); bz <= Mth.floor(maxZ); bz++) {
                    float h = world.water(bx, by, bz);
                    if (h < 0) {
                        continue;
                    }
                    double surface = by + h;
                    if (surface >= minY) {
                        inWater = true;
                        waterDepth = Math.max(waterDepth, surface - minY);
                    }
                }
            }
        }
    }

    // LivingEntity.travelInWater, for a player (water slowdown 0.8, no depth strider, no dolphin's grace)
    private boolean travelInWater(Controls c, double dx, double dy, double dz) {
        boolean falling = dy <= 0.0;
        double oldY = y;
        float slowDown = 0.8F, accel = 0.02F;
        double inLen = (double) xxa * xxa + (double) zza * zza;
        if (!(inLen < 1.0E-7)) {
            double ix = xxa, iz = zza;
            if (inLen > 1.0) {
                double norm = Math.sqrt(inLen);
                ix /= norm;
                iz /= norm;
            }
            ix *= accel;
            iz *= accel;
            float rad = c.moveYaw() * DEG_TO_RAD;
            float sin = Mth.sin(rad), cos = Mth.cos(rad);
            dx += ix * cos - iz * sin;
            dz += iz * cos + ix * sin;
        }
        vx = dx;
        vy = dy;
        vz = dz;
        move(c.viewYaw());
        climbing = onClimbable();
        if (horizontalCollision && climbing) {
            vy = 0.2;
        }
        vx *= slowDown;
        vy *= 0.8F;
        vz *= slowDown;
        // getFluidFallingAdjustedMovement: gravity at a sixteenth (the "falling" special case can't happen at 0.08)
        vy -= params.gravity() / 16.0;
        // jumpOutOfFluid: swimming into a wall with the top of it in reach, out we go
        if (horizontalCollision && free(vx, vy + 0.6 - y + oldY, vz)) {
            vy = 0.3F;
        }
        String ended = endedSomewhereOdd();
        if (ended != null) {
            failure = ended;
            return false;
        }
        return true;
    }

    // Entity.isFree(dx, dy, dz): the box moved by that hits nothing and touches no fluid
    private boolean free(double ox, double oy, double oz) {
        double minX = x - halfWidth + ox, maxX = x + halfWidth + ox, minY = y + oy, maxY = y + params.height() + oy;
        double minZ = z - halfWidth + oz, maxZ = z + halfWidth + oz;
        for (int bx = Mth.floor(minX); bx <= Mth.floor(maxX); bx++) {
            for (int by = Mth.floor(minY) - 1; by <= Mth.floor(maxY); by++) {
                for (int bz = Mth.floor(minZ); bz <= Mth.floor(maxZ); bz++) {
                    if (by >= Mth.floor(minY) && world.water(bx, by, bz) >= 0) {
                        return false;
                    }
                    cell.clear();
                    world.boxes(bx, by, bz, cell);
                    for (int i = 0; i < cell.size(); i++) {
                        if (cell.get(i, 0) < maxX && cell.get(i, 3) > minX && cell.get(i, 1) < maxY && cell.get(i, 4) > minY
                                && cell.get(i, 2) < maxZ && cell.get(i, 5) > minZ) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // LivingEntity.onClimbable: the block the feet are in (blockPosition, the cell of the box's bottom middle)
    private boolean onClimbable() {
        return world.climbable(Mth.floor(x), Mth.floor(y), Mth.floor(z));
    }

    // Entity.getOnPos(offset): the supporting block's column at feet minus offset, or the supporting block itself for walls and
    // gates (and fences, at offsets of half a block or less). no support (in the air) is straight under the middle of the box
    private void onPos(float offset, int[] out) {
        if (hasSupport) {
            int kind = offset > 1.0E-5F ? world.supportKind(supportX, supportY, supportZ) : 3;
            if (kind == 3 || kind == 2 || kind == 1 && offset <= 0.5F) {
                out[0] = supportX;
                out[1] = supportY;
                out[2] = supportZ;
            } else {
                out[0] = supportX;
                out[1] = Mth.floor(y - offset);
                out[2] = supportZ;
            }
            return;
        }
        out[0] = Mth.floor(x);
        out[1] = Mth.floor(y - offset);
        out[2] = Mth.floor(z);
    }


    // getBlockPosBelowThatAffectsMyMovement is getOnPos(0.500001F)
    private float frictionBelow() {
        onPos(0.500001F, cellA);
        return world.friction(cellA[0], cellA[1], cellA[2]);
    }

    // Entity.getBlockJumpFactor / getBlockSpeedFactor: the cell we're in, unless that's 1, then the one under us
    private float jumpFactor() {
        float here = world.jumpFactor(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (here != 1.0F) {
            return here;
        }
        onPos(0.500001F, cellA);
        return world.jumpFactor(cellA[0], cellA[1], cellA[2]);
    }

    private float speedFactor() {
        float here = world.speedFactor(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (here != 1.0F) {
            return here;
        }
        onPos(0.500001F, cellA);
        return world.speedFactor(cellA[0], cellA[1], cellA[2]);
    }

    // Entity.checkSupportingBlock / CollisionGetter.findSupportingBlock: of the blocks that collide with a sliver under the
    // feet, the one whose middle is nearest ours (ties to the larger BlockPos). nothing there, try again where we came from
    private void updateSupport(double mx, double mz) {
        if (!onGround) {
            hasSupport = false;
            return;
        }
        if (findSupport(0.0, 0.0) || findSupport(-mx, -mz)) {
            return;
        }
        hasSupport = false;
    }

    private boolean findSupport(double ox, double oz) {
        double minX = x - halfWidth + ox, maxX = x + halfWidth + ox, minZ = z - halfWidth + oz, maxZ = z + halfWidth + oz;
        double minY = y - 1.0E-6, maxY = y;
        boolean found = false;
        double best = Double.MAX_VALUE;
        int bx0 = 0, by0 = 0, bz0 = 0;
        for (int bx = Mth.floor(minX - 1.0E-7) - 1; bx <= Mth.floor(maxX + 1.0E-7) + 1; bx++) {
            for (int by = Mth.floor(minY - 1.0E-7) - 1; by <= Mth.floor(maxY + 1.0E-7); by++) {
                for (int bz = Mth.floor(minZ - 1.0E-7) - 1; bz <= Mth.floor(maxZ + 1.0E-7) + 1; bz++) {
                    cell.clear();
                    world.boxes(bx, by, bz, cell);
                    boolean hits = false;
                    for (int i = 0; i < cell.size() && !hits; i++) {
                        hits = cell.get(i, 0) < maxX && cell.get(i, 3) > minX && cell.get(i, 1) < maxY && cell.get(i, 4) > minY
                                && cell.get(i, 2) < maxZ && cell.get(i, 5) > minZ;
                    }
                    if (!hits) {
                        continue;
                    }
                    double ddx = bx + 0.5 - x, ddy = by + 0.5 - y, ddz = bz + 0.5 - z;
                    double dist = ddx * ddx + ddy * ddy + ddz * ddz;
                    // Vec3i.compareTo: y, then z, then x
                    boolean larger = by != by0 ? by > by0 : bz != bz0 ? bz > bz0 : bx > bx0;
                    if (!found || dist < best || dist == best && larger) {
                        found = true;
                        best = dist;
                        bx0 = bx;
                        by0 = by;
                        bz0 = bz;
                    }
                }
            }
        }
        if (found) {
            hasSupport = true;
            supportX = bx0;
            supportY = by0;
            supportZ = bz0;
        }
        return found;
    }

    // LivingEntity.computeModifiedFriction with the default modifier of 1
    private static float modifiedFriction(float friction) {
        return Mth.clamp(1.0F - (1.0F - friction) * 1.0F, 0.0F, 1.0F);
    }

    // anything about where we are standing that the maths above does not cover
    private String unsupported() {
        float w = halfWidth;
        int x0 = Mth.floor(x - w - 1.0E-3), x1 = Mth.floor(x + w + 1.0E-3);
        int z0 = Mth.floor(z - w - 1.0E-3), z1 = Mth.floor(z + w + 1.0E-3);
        // the block friction comes from getOnPos(0.500001F), speed and jump factors from the cell we are in, and
        // Entity.checkInsideBlocks from every cell the box touches
        // (in the air nothing's read off the block under us, only what the box is in: a flight half a block over lava is fine)
        int y0 = onGround ? Math.min(Mth.floor(y - 0.500001), Mth.floor(y)) : Mth.floor(y - 1.0E-3);
        int y1 = Mth.floor(y + params.height() + 1.0E-3);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (!world.plain(bx, by, bz)) {
                        return "not plain at " + bx + " " + by + " " + bz;
                    }
                }
            }
        }
        return null;
    }

    // getBlockSpeedFactor looks at the cell we are in and the one getOnPos(0.500001F) gives. that one is the supporting block
    // when grounded, which can be under a corner of the box instead of the middle, so this only checks the middle and a tick
    // that lands on the edge of soul sand is caught by the next tick's unsupported() instead
    private String endedSomewhereOdd() {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!world.plain(bx, Mth.floor(y), bz) || onGround && !world.plain(bx, Mth.floor(y - 0.500001), bz)) {
            return "ended on something not plain";
        }
        return null;
    }

    // Entity.move, off the velocity that tick() just set
    private void move(float viewYaw) {
        double dx = vx, dy = vy, dz = vz;
        collide(dx, dy, dz);
        double cx = out[0], cy = out[1], cz = out[2];
        double movementLength = cx * cx + cy * cy + cz * cz;
        if (movementLength > 1.0E-7 || dx * dx + dy * dy + dz * dz - movementLength < 1.0E-7) {
            x += cx;
            y += cy;
            z += cz;
        }
        boolean xCollision = !(Math.abs(cx - dx) < 1.0E-5F);
        boolean zCollision = !(Math.abs(cz - dz) < 1.0E-5F);
        horizontalCollision = xCollision || zCollision;
        verticalCollision = dy != cy;
        // setOnGroundWithMovement(verticalCollisionBelow, ...). the local player always takes this branch
        onGround = verticalCollision && dy < 0.0;
        updateSupport(cx, cz);
        minorHorizontalCollision = horizontalCollision && horizontalCollisionMinor(cx, cz, viewYaw);
        // restituteMovementAfterCollisions. a player's own bounciness is 0, so whatever was hit sideways stops dead. coming
        // down onto something with a bounce (slime) faster than gravity turns the fall round: what's left of the tick's
        // movement after the hit, with the gravity and drag for that part of the tick taken back out
        bounced = false;
        if (xCollision) {
            vx = 0.0;
        }
        if (zCollision) {
            vz = 0.0;
        }
        if (verticalCollision) {
            double restitution = 0.0;
            double gravity = params.gravity();
            if (dy < 0.0 && !(-dy <= gravity)) {
                onPos(0.2F, cellB);
                restitution = Math.max(0.0, world.bounce(cellB[0], cellB[1], cellB[2]));
            }
            if (restitution > 0.0) {
                double portion = cy / dy;
                double gravityCompensation = portion * gravity;
                double drag = Mth.lerp(portion, 1.0, (double) 0.98F);
                vy = (gravityCompensation - dy) * drag * restitution;
                bounced = true;
            } else {
                vy = 0.0;
            }
        }
        // and the speed factor of what we're in or on (soul sand, honey)
        float speedFactor = speedFactor();
        vx *= speedFactor;
        vz *= speedFactor;
    }

    // LocalPlayer.isHorizontalCollisionMinor: close to where the keys wanted to go (under 8 degrees) is a graze, not a wall
    private boolean horizontalCollisionMinor(double mx, double mz, float viewYaw) {
        float rad = viewYaw * DEG_TO_RAD;
        double sin = Mth.sin(rad), cos = Mth.cos(rad);
        double globalXA = xxa * cos - zza * sin;
        double globalZA = zza * cos + xxa * sin;
        double aLengthSquared = globalXA * globalXA + globalZA * globalZA;
        double movementLengthSquared = mx * mx + mz * mz;
        if (!(aLengthSquared < 1.0E-5F) && !(movementLengthSquared < 1.0E-5F)) {
            double dot = globalXA * mx + globalZA * mz;
            double angle = Math.acos(dot / Math.sqrt(aLengthSquared * movementLengthSquared));
            return angle < 0.13962634F;
        }
        return false;
    }

    // Entity.collide: y first, then the longer of x and z, and a step up if the first try ran into something while on the ground
    private void collide(double dx, double dy, double dz) {
        out[0] = dx;
        out[1] = dy;
        out[2] = dz;
        if (dx * dx + dy * dy + dz * dz == 0.0) {
            return;
        }
        float stepHeight = params.stepHeight();
        box[0] = x - halfWidth;
        box[1] = y;
        box[2] = z - halfWidth;
        box[3] = x + halfWidth;
        box[4] = y + params.height();
        box[5] = z + halfWidth;
        expand(box, dx, dy, dz, query);
        gather(query, boxes);
        collideWithShapes(dx, dy, dz, box, boxes, step);
        boolean xCollision = dx != step[0], yCollision = dy != step[1], zCollision = dz != step[2];
        boolean onGroundAfterCollision = yCollision && dy < 0.0;
        if (stepHeight > 0.0F && (onGroundAfterCollision || onGround) && (xCollision || zCollision)) {
            System.arraycopy(box, 0, groundedBox, 0, 6);
            if (onGroundAfterCollision) {
                groundedBox[1] += step[1];
                groundedBox[4] += step[1];
            }
            expand(groundedBox, dx, stepHeight, dz, query);
            if (!onGroundAfterCollision) {
                query[1] += -1.0E-5F;
            }
            gather(query, stepBoxes);
            int n = candidateHeights(groundedBox[1], stepBoxes, stepHeight, (float) step[1]);
            double distanceToGround = box[1] - groundedBox[1];
            for (int i = 0; i < n; i++) {
                collideWithShapes(dx, scr.candidates[i], dz, groundedBox, stepBoxes, stepped);
                if (stepped[0] * stepped[0] + stepped[2] * stepped[2] > step[0] * step[0] + step[2] * step[2]) {
                    out[0] = stepped[0];
                    out[1] = stepped[1] - distanceToGround;
                    out[2] = stepped[2];
                    return;
                }
            }
        }
        out[0] = step[0];
        out[1] = step[1];
        out[2] = step[2];
    }

    // Entity.collectCandidateStepUpHeights: every y coordinate of every box that is within step height above our feet, sorted
    private int candidateHeights(double minY, BoxList colliders, float maxStepHeight, float stepHeightToSkip) {
        int n = 0;
        for (int i = 0; i < colliders.size(); i++) {
            for (int k = 1; k < 6; k += 3) { // the min y then the max y, which is the order VoxelShape.getCoords gives them
                float relative = (float) (colliders.get(i, k) - minY);
                if (!(relative < 0.0F) && relative != stepHeightToSkip) {
                    if (relative > maxStepHeight) {
                        break;
                    }
                    boolean known = false;
                    for (int j = 0; j < n && !known; j++) {
                        known = scr.candidates[j] == relative;
                    }
                    if (!known) {
                        if (n == scr.candidates.length) {
                            scr.candidates = Arrays.copyOf(scr.candidates, n * 2);
                        }
                        scr.candidates[n++] = relative;
                    }
                }
            }
        }
        Arrays.sort(scr.candidates, 0, n);
        return n;
    }

    // Entity.collideWithShapes: y first, then the longer of x and z, each one swept from where the last one left the box
    private void collideWithShapes(double mx, double my, double mz, double[] from, BoxList shapes, double[] result) {
        if (shapes.size() == 0) {
            result[0] = mx;
            result[1] = my;
            result[2] = mz;
            return;
        }
        result[0] = 0.0;
        result[1] = 0.0;
        result[2] = 0.0;
        want[0] = mx;
        want[1] = my;
        want[2] = mz;
        int[] order = Math.abs(mx) < Math.abs(mz) ? Y_Z_X : Y_X_Z;
        for (int axis : order) {
            if (want[axis] != 0.0) {
                for (int i = 0; i < 3; i++) {
                    swept[i] = from[i] + result[i];
                    swept[i + 3] = from[i + 3] + result[i];
                }
                result[axis] = clip(axis, swept, shapes, want[axis]);
            }
        }
    }

    // Shapes.collide / VoxelShape.collideX for a one box shape. the 1e-7s are vanilla's, a sweep stops at the first face it meets
    private static double clip(int a, double[] mv, BoxList shapes, double distance) {
        int b = (a + 1) % 3, c = (a + 2) % 3;
        double[] d = shapes.data;
        for (int i = 0; i < shapes.size(); i++) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }
            int o = i * 6;
            if (!(mv[b] + 1.0E-7 < d[o + 3 + b] && mv[b + 3] - 1.0E-7 >= d[o + b])
                    || !(mv[c] + 1.0E-7 < d[o + 3 + c] && mv[c + 3] - 1.0E-7 >= d[o + c])) {
                continue;
            }
            if (distance > 0.0) {
                if (mv[a + 3] - 1.0E-7 < d[o + a]) {
                    distance = Math.min(distance, d[o + a] - mv[a + 3]);
                }
            } else if (mv[a] + 1.0E-7 >= d[o + 3 + a]) {
                distance = Math.max(distance, d[o + 3 + a] - mv[a]);
            }
        }
        return distance;
    }

    // AABB.expandTowards
    private static void expand(double[] src, double dx, double dy, double dz, double[] dst) {
        System.arraycopy(src, 0, dst, 0, 6);
        if (dx < 0.0) {
            dst[0] += dx;
        } else {
            dst[3] += dx;
        }
        if (dy < 0.0) {
            dst[1] += dy;
        } else {
            dst[4] += dy;
        }
        if (dz < 0.0) {
            dst[2] += dz;
        } else {
            dst[5] += dz;
        }
    }

    // Level.getBlockCollisions: every cell the box touches plus a ring of one (fences stand 1.5 tall), and only the boxes that
    // really overlap it
    private void gather(double[] q, BoxList into) {
        into.clear();
        int x0 = Mth.floor(q[0] - 1.0E-7) - 1, x1 = Mth.floor(q[3] + 1.0E-7) + 1;
        int y0 = Mth.floor(q[1] - 1.0E-7) - 1, y1 = Mth.floor(q[4] + 1.0E-7) + 1;
        int z0 = Mth.floor(q[2] - 1.0E-7) - 1, z1 = Mth.floor(q[5] + 1.0E-7) + 1;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    double[] known = world.boxArray(bx, by, bz);
                    if (known != null) {
                        for (int o = 0; o < known.length; o += 6) {
                            if (known[o] < q[3] && known[o + 3] > q[0] && known[o + 1] < q[4] && known[o + 4] > q[1]
                                    && known[o + 2] < q[5] && known[o + 5] > q[2]) {
                                into.add(known[o], known[o + 1], known[o + 2], known[o + 3], known[o + 4], known[o + 5]);
                            }
                        }
                        continue;
                    }
                    cell.clear();
                    world.boxes(bx, by, bz, cell);
                    for (int i = 0; i < cell.size(); i++) {
                        if (cell.get(i, 0) < q[3] && cell.get(i, 3) > q[0] && cell.get(i, 1) < q[4] && cell.get(i, 4) > q[1]
                                && cell.get(i, 2) < q[5] && cell.get(i, 5) > q[2]) {
                            into.add(cell.get(i, 0), cell.get(i, 1), cell.get(i, 2), cell.get(i, 3), cell.get(i, 4), cell.get(i, 5));
                        }
                    }
                }
            }
        }
    }
}
