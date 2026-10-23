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

package baritone.pathing.tungsten;

import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;

// the bits of key and yaw bookkeeping the policy, the search and the executors all have to agree on
public final class Keys {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private Keys() {}

    // yaw that faces along (wx, wz). same convention as everywhere else in Baritone: yaw 0 faces +z, 90 faces -x
    public static float yaw(double wx, double wz) {
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(wz, wx)) - 90F);
    }

    public static PlayerSim.Controls run(float yaw, boolean jump) {
        return new PlayerSim.Controls(1, 0, jump, true, yaw);
    }

    // W held with the yaw turned to face the way we want to slow down. on the ground it's a walk (no sprint, the executor lets
    // go of the sprint flag the moment nobody asks for it)
    public static PlayerSim.Controls push(float yaw, boolean sprint) {
        return new PlayerSim.Controls(1, 0, false, sprint, yaw);
    }

    // sprinting the way `move` points with W and A (side 1) or W and D (side -1) held, facing 45 degrees off it to make up
    public static PlayerSim.Controls strafe(float move, int side, boolean jump) {
        float face = move + 45F * side;
        return new PlayerSim.Controls(1, side, jump, true, face, face);
    }

    public static PlayerSim.Controls release(float yaw) {
        return new PlayerSim.Controls(0, 0, false, false, yaw);
    }

    // one tick, the way the executor will really play it: a tick that doesn't ask for sprint has the flag dropped before the
    // player moves (PathExecutor calls setSprinting(false) whenever the movement didn't request it)
    public static boolean step(PlayerSim sim, PlayerSim.Controls keys) {
        if (!keys.sprint()) {
            sim.sprinting = false;
        }
        return sim.tick(keys);
    }

    // PlayerSim.tick in open air with nothing to hit, written into out (x y z vx vy vz). for scoring air control, where a
    // whole collision pass per candidate is too much and a tick in open air is all it would ever say anyway
    public static void airStep(SimState s, int forward, float yaw, boolean sprintKey, double gravity, double[] out) {
        boolean hasForward = forward > 0;
        boolean sprinting = sprintKey && s.sprinting;
        if (!sprinting && hasForward && sprintKey) {
            sprinting = true;
        }
        if (sprinting && !hasForward) {
            sprinting = false;
        }
        double dx = s.vx, dy = s.vy, dz = s.vz;
        if (dx * dx + dz * dz < 9.0E-6) {
            dx = 0.0;
            dz = 0.0;
        }
        if (Math.abs(dy) < 0.003) {
            dy = 0.0;
        }
        if (forward != 0) {
            // forward alone: modifyInput leaves 0.98 in the forward slot, which is under 1 so moveRelative doesn't normalise it
            float zza = forward * 0.98F;
            float accel = sprinting ? 0.025999999F : 0.02F;
            double iz = (double) zza * accel;
            float rad = yaw * DEG_TO_RAD;
            dx += -iz * Mth.sin(rad);
            dz += iz * Mth.cos(rad);
        }
        out[0] = s.x + dx;
        out[1] = s.y + dy;
        out[2] = s.z + dz;
        out[3] = dx * 0.91F;
        out[4] = (dy - gravity) * 0.98F;
        out[5] = dz * 0.91F;
    }

    // keep coasting (no keys) from x y z with velocity v until the feet come down through floorY, and say where across that
    // happens (into out[0], out[1]). false if it never comes down through it (falls short below it, or still climbing in 60 ticks)
    public static boolean coastTo(double x, double y, double z, double vx, double vy, double vz, double floorY, double gravity, double[] out) {
        boolean above = y >= floorY;
        for (int i = 0; i < 60; i++) {
            if (Math.abs(vy) < 0.003) {
                vy = 0.0;
            }
            double ny = y + vy;
            if (above && vy < 0 && ny <= floorY) {
                double t = (y - floorY) / (y - ny);
                out[0] = x + vx * t;
                out[1] = z + vz * t;
                return true;
            }
            x += vx;
            y = ny;
            z += vz;
            if (y >= floorY) {
                above = true;
            } else if (above || vy < 0) {
                return false; // came down through it on a step we didn't catch, or never going to get up there
            }
            vx *= 0.91F;
            vz *= 0.91F;
            vy = (vy - gravity) * 0.98F;
        }
        return false;
    }
}
