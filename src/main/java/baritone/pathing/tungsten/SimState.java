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

// everything about a PlayerSim that changes from one tick to the next. a search keeps thousands of states and one sim to tick
// them on (a sim drags its scratch buffers around, a state is a dozen fields): load, tick, read back
public final class SimState {

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, horizontalCollision, minorHorizontalCollision, verticalCollision;
    public int noJumpDelay;
    public boolean hasSupport, climbing, inWater;
    public double waterDepth;
    public int supportX, supportY, supportZ;

    public static SimState of(PlayerSim sim) {
        return new SimState().read(sim);
    }

    public SimState read(PlayerSim sim) {
        x = sim.x;
        y = sim.y;
        z = sim.z;
        vx = sim.vx;
        vy = sim.vy;
        vz = sim.vz;
        onGround = sim.onGround;
        sprinting = sim.sprinting;
        horizontalCollision = sim.horizontalCollision;
        minorHorizontalCollision = sim.minorHorizontalCollision;
        verticalCollision = sim.verticalCollision;
        noJumpDelay = sim.noJumpDelay;
        hasSupport = sim.hasSupport;
        climbing = sim.climbing;
        inWater = sim.inWater;
        waterDepth = sim.waterDepth;
        supportX = sim.supportX;
        supportY = sim.supportY;
        supportZ = sim.supportZ;
        return this;
    }

    public void write(PlayerSim sim) {
        sim.x = x;
        sim.y = y;
        sim.z = z;
        sim.vx = vx;
        sim.vy = vy;
        sim.vz = vz;
        sim.onGround = onGround;
        sim.sprinting = sprinting;
        sim.horizontalCollision = horizontalCollision;
        sim.minorHorizontalCollision = minorHorizontalCollision;
        sim.verticalCollision = verticalCollision;
        sim.noJumpDelay = noJumpDelay;
        sim.hasSupport = hasSupport;
        sim.climbing = climbing;
        sim.inWater = inWater;
        sim.waterDepth = waterDepth;
        sim.supportX = supportX;
        sim.supportY = supportY;
        sim.supportZ = supportZ;
    }

    public SimState copy() {
        SimState c = new SimState();
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

    public double horizontalSpeed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    // how far the real player is from where a plan said it would be, position and velocity rolled into one number. velocity
    // counts five times over, a twentieth of a block a tick is a whole block in twenty ticks
    public double distance(SimState o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        double dvx = vx - o.vx, dvy = vy - o.vy, dvz = vz - o.vz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + 5 * Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
    }

    @Override
    public String toString() {
        return String.format("(%.3f %.3f %.3f) v(%.3f %.3f %.3f)%s%s", x, y, z, vx, vy, vz, onGround ? " ground" : "", sprinting ? " sprint" : "");
    }
}
