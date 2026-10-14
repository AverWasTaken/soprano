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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// when to press space for a one block hop up, asked of the sim instead of guessed. a sprint hop spends its first two moves
// (about 0.77 of a block) still below the top of the step, so it clips the face if it starts too close, and it comes down
// against the face if it starts too far back: there is a window about two blocks wide, and the old rules ("jump the moment
// the ceiling is clear", "jump inside 1.2 blocks") are somewhere in or near it depending on where in the cell the last
// movement happened to hand over. a clip is a horizontal collision vanilla does not call minor, which costs the sprint and
// all the speed with it
public final class AscendHop {

    public enum Verdict {
        JUMP, // press it now
        WAIT, // keep going and ask again, a better tick is coming
        UNKNOWN // the sim cannot say (or no tick in reach is any good), do what the movement always did
    }

    // how many ticks ahead to look for a better moment. a window opening further off than this is not worth holding still for
    private static final int MAX_WAIT = 5;
    private static final int MAX_FLIGHT = 30;
    // the world is not exactly where the sim thinks (a tick of lag, a degree of yaw jitter), so a jump only counts as good
    // if it is also good a tenth of a block further back and a tenth further on. the exact one goes first, it is the one
    // that rules most ticks out and the other two are not worth simulating then
    private static final double[] SHIFTS = {0.0, -0.1, 0.1};

    private AscendHop() {}

    public static Verdict decide(IPlayerContext ctx, BlockStateInterface bsi, BetterBlockPos src, BetterBlockPos dest) {
        LocalPlayer player = ctx.player();
        int dx = Integer.signum(dest.x - src.x), dz = Integer.signum(dest.z - src.z);
        if (bsi == null || !player.onGround() || dest.y != src.y + 1 || (dx != 0) == (dz != 0)) {
            return Verdict.UNKNOWN; // only the straight single step up is a window we know the shape of
        }
        PlayerSim sim = LiveSim.of(player, new WorldCollision(bsi));
        if (sim == null) {
            return Verdict.UNKNOWN;
        }
        Vec3 target = new Vec3(dest.x + 0.5, dest.y, dest.z + 0.5);
        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
        return decide(sim, dx, dz, dest, yaw, player.getYRot(), player.isSprinting());
    }

    /**
     * @param sim the player right now, at the start of the tick the jump key would go down in
     * @param dx  which way the step is, one of these is 0 and the other 1 or -1
     */
    public static Verdict decide(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        int first = -1;
        for (int wait = 0; wait <= MAX_WAIT; wait++) {
            int quality = quality(sim, wait, dx, dz, dest, moveYaw, viewYaw, sprint);
            if (quality < 0) {
                return Verdict.UNKNOWN;
            }
            if (quality == 2) {
                return wait == 0 ? Verdict.JUMP : Verdict.WAIT;
            }
            if (quality == 1 && first < 0) {
                first = wait;
            }
        }
        if (first < 0) {
            return Verdict.UNKNOWN;
        }
        return first == 0 ? Verdict.JUMP : Verdict.WAIT;
    }

    // -1 the sim gave up, 0 clips or misses, 1 clean, 2 clean with a tenth of a block to spare either way
    private static int quality(PlayerSim start, int wait, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        int quality = 2;
        for (double shift : SHIFTS) {
            PlayerSim sim = start.copy();
            sim.x += shift * dx;
            sim.z += shift * dz;
            int result = hop(sim, wait, dx, dz, dest, moveYaw, viewYaw, sprint);
            if (result < 0 && shift == 0.0) {
                return -1;
            }
            if (result <= 0) {
                if (shift == 0.0) {
                    return 0;
                }
                quality = 1;
            }
        }
        return quality;
    }

    // hold forward for `wait` ticks, press space, hold forward all the way down. 1 if that lands on the step with no wall hit
    private static int hop(PlayerSim sim, int wait, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        PlayerSim.Controls walk = new PlayerSim.Controls(1, 0, false, sprint, moveYaw, viewYaw);
        for (int i = 0; i < wait; i++) {
            if (!sim.tick(walk)) {
                return -1;
            }
            if (!sim.onGround || wall(sim)) {
                return 0; // waiting walked us off a ledge or into the face
            }
        }
        if (!sim.tick(walk.withJump(true))) {
            return -1;
        }
        if (!sim.jumped) {
            return 0;
        }
        for (int i = 0; i < MAX_FLIGHT; i++) {
            if (wall(sim)) {
                return 0;
            }
            if (sim.onGround) {
                return landedOnTheStep(sim, dx, dz, dest) ? 1 : 0;
            }
            if (!sim.tick(walk)) {
                return -1;
            }
        }
        return 0;
    }

    // a horizontal collision that vanilla does not wave through as a graze (LocalPlayer.shouldStopRunSprinting)
    private static boolean wall(PlayerSim sim) {
        return sim.horizontalCollision && !sim.minorHorizontalCollision;
    }

    // on the step, in the lane, and not behind its near edge. anywhere further along is fine, the path goes on from there
    private static boolean landedOnTheStep(PlayerSim sim, int dx, int dz, BetterBlockPos dest) {
        double fromCenterX = sim.x - (dest.x + 0.5), fromCenterZ = sim.z - (dest.z + 0.5);
        double along = fromCenterX * dx + fromCenterZ * dz;
        double across = Math.abs(fromCenterX * dz) + Math.abs(fromCenterZ * dx);
        return Math.abs(sim.y - dest.y) < 1.0E-3 && along >= -0.5 && across <= 0.5;
    }
}
