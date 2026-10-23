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

import org.junit.Test;

import static org.junit.Assert.*;

// ice, slime and soul sand against the numbers the 26.3 code works out to (LivingEntity.travel, getFrictionInfluencedSpeed,
// Entity.restituteMovementAfterCollisions, SlimeBlock.stepOn, getBlockSpeedFactor)
public class MaterialsTest {

    private static final PlayerSim.Controls SPRINT = new PlayerSim.Controls(1, 0, false, true, 0F);
    private static final PlayerSim.Controls WALK = new PlayerSim.Controls(1, 0, false, false, 0F);
    private static final PlayerSim.Controls NOTHING = new PlayerSim.Controls(0, 0, false, false, 0F);

    private static ArrayWorld floor(byte type) {
        ArrayWorld world = new ArrayWorld(16, 80, 200);
        world.fill(0, 60, 0, 15, 62, 199, ArrayWorld.STONE);
        world.fill(0, 63, 0, 15, 63, 199, type);
        return world;
    }

    private static PlayerSim at(ArrayWorld world, double y) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = 8.5;
        sim.y = y;
        sim.z = 2.5;
        sim.vy = -0.0784;
        sim.onGround = true;
        return sim;
    }

    private static double topSpeed(PlayerSim sim, PlayerSim.Controls keys) {
        double last = 0;
        for (int i = 0; i < 150; i++) {
            double before = sim.z;
            assertTrue(sim.failure(), sim.tick(keys));
            last = sim.z - before;
        }
        return last;
    }

    // accel 0.13 * 0.98 * 0.216 / 0.98^3, drag 0.98 * 0.91: a / (1 - drag). a touch slower than stone on the ground, ice
    // only pays off in the air, where nothing takes the speed away
    @Test
    public void sprintingOnPackedIce() {
        float a = 0.13F * 0.98F * (0.21600002F / (0.98F * 0.98F * 0.98F));
        double expected = a / (1 - 0.98F * 0.91F);
        assertEquals(expected, topSpeed(at(floor(ArrayWorld.PACKED_ICE), 64), SPRINT), 1e-4);
    }

    @Test
    public void iceKeepsSlidingLongAfterLettingGo() {
        PlayerSim stone = at(floor(ArrayWorld.STONE), 64), ice = at(floor(ArrayWorld.PACKED_ICE), 64);
        topSpeed(stone, SPRINT);
        topSpeed(ice, SPRINT);
        double s0 = stone.z, i0 = ice.z;
        for (int i = 0; i < 40; i++) {
            assertTrue(stone.tick(NOTHING));
            assertTrue(ice.tick(NOTHING));
        }
        assertTrue(stone.z - s0 < 0.7);
        assertTrue(ice.z - i0 > 2.0);
    }

    // walking: the 0.4 speed factor on top of the ground drag, a / (1 - 0.546 * 0.4). soul sand is 7/8 of a block tall
    @Test
    public void walkingOnSoulSand() {
        double expected = 0.1F * 0.98F / (1 - 0.546F * 0.4F);
        PlayerSim sim = at(floor(ArrayWorld.SOUL_SAND), 63.875);
        assertEquals(expected, topSpeed(sim, WALK), 1e-4);
        assertEquals(63.875, sim.y, 1e-9);
    }

    @Test
    public void walkingOnSlimeIsSlow() {
        double slime = topSpeed(at(floor(ArrayWorld.SLIME), 64), WALK);
        double stone = topSpeed(at(floor(ArrayWorld.STONE), 64), WALK);
        assertTrue(slime + " vs " + stone, slime < stone * 0.6);
    }

    // dropped from 4 up onto slime: thrown back up, a little lower each time (the drag), and still a bounce after a few
    @Test
    public void slimeBouncesYouBackUp() {
        PlayerSim sim = at(floor(ArrayWorld.SLIME), 68);
        sim.onGround = false;
        sim.vy = 0;
        double peak = 0;
        int bounces = 0;
        boolean wasUp = false;
        for (int i = 0; i < 200 && bounces < 3; i++) {
            assertTrue(sim.failure(), sim.tick(NOTHING));
            if (sim.bounced) {
                bounces++;
                assertTrue(sim.vy > 0.3);
                assertTrue(sim.onGround); // the tick it lands it's on the ground, and on its way up already
                wasUp = false;
            }
            if (bounces == 1 && !sim.bounced) {
                if (sim.vy > 0) {
                    wasUp = true;
                }
                peak = Math.max(peak, sim.y);
            }
        }
        assertEquals(3, bounces);
        assertTrue("peak " + peak, peak > 66.5 && peak < 68);
    }

    // standing still on slime doesn't bounce: gravity's 0.08 a tick isn't a fall
    @Test
    public void standingOnSlimeDoesntBounce() {
        PlayerSim sim = at(floor(ArrayWorld.SLIME), 64);
        for (int i = 0; i < 40; i++) {
            assertTrue(sim.tick(NOTHING));
            assertFalse(sim.bounced);
            assertTrue(sim.onGround);
        }
        assertEquals(64, sim.y, 1e-9);
    }

    // up a ladder: holding jump (or walking into it) climbs at 0.2 a tick before gravity and drag, 0.1176 a tick after. off it,
    // the fall is held to 0.15 a tick
    @Test
    public void ladders() {
        ArrayWorld world = floor(ArrayWorld.STONE);
        world.fill(7, 64, 2, 7, 70, 2, ArrayWorld.STONE);
        world.fill(8, 64, 2, 8, 69, 2, ArrayWorld.LADDER_ON_WEST);
        PlayerSim sim = at(world, 64);
        PlayerSim.Controls up = new PlayerSim.Controls(0, 0, true, false, 0F);
        double last = sim.y;
        for (int i = 0; i < 20; i++) {
            assertTrue(sim.failure(), sim.tick(up));
            assertTrue(sim.climbing);
            if (i > 3) {
                assertEquals(0.2 * 0.98 - 0.08 * 0.98, sim.y - last, 1e-6);
            }
            last = sim.y;
        }
        // let go: slides down no faster than 0.15
        for (int i = 0; i < 20; i++) {
            double before = sim.y;
            assertTrue(sim.tick(NOTHING));
            assertTrue(i + ": " + before + " -> " + sim.y + " " + sim.climbing, before - sim.y <= 0.15F + 1e-9); // 0.15F, a float, like vanilla
        }
    }

    // a vine has no box, so walking into it doesn't climb (there's nothing to collide with), only jump does
    @Test
    public void vines() {
        ArrayWorld world = floor(ArrayWorld.STONE);
        world.fill(8, 64, 2, 8, 69, 2, ArrayWorld.VINE);
        PlayerSim sim = at(world, 64);
        sim.tick(new PlayerSim.Controls(0, 0, true, false, 0F));
        for (int i = 0; i < 5; i++) {
            assertTrue(sim.tick(new PlayerSim.Controls(0, 0, true, false, 0F)));
        }
        assertTrue(sim.y > 64.4);
        assertTrue(sim.climbing);
    }
}
