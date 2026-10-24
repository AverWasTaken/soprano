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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.Helper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

// a parkour server puts you back on the course when you fall: touch the floor (or go below some height) and you're
// teleported up to the start or the last checkpoint. when that happens, how low the fall went is somewhere the server won't
// let you be, so the planner stays above it from then on in this world (see Settings.learnFallFloor, avoidBelowY)
public final class FallFloor {

    // what's been learned is about the course round where it happened (one world can hold a dozen courses, each with its
    // floor at its own height): this far round it, across
    static final int AROUND = 64;
    // a teleport this soon after another one is the server settling us in (a level's start, a world loading round us), not a
    // put back after a fall
    static final int SETTLE_TICKS = 60;
    // a put back on stepping onto something is to a checkpoint of the same course: not further than this
    static final int PUT_BACK_RANGE = 96;

    private record Learned(int x, int z, int floor) {}

    private static final java.util.List<Learned> FLOORS = new java.util.concurrent.CopyOnWriteArrayList<>();
    // and blocks that put you back when you stand on them, at this height or lower (a desert's sand, a candy level's pink
    // floor): the same block higher up can be part of the course. x z: where (Integer.MIN_VALUE: everywhere, for tests)
    private record Deadly(net.minecraft.world.level.block.Block block, int x, int z, int maxFeetY) {}

    private static final java.util.List<Deadly> DEADLY = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Level world;
    // since the last time on the ground at the top of a fall
    private static double lastX, lastY, lastZ = Double.NaN;
    private static double topY = Double.NaN, lowY = Double.NaN;
    private static int groundTicks, sinceTeleport = SETTLE_TICKS;
    // what we last came down on below the top of the fall, if we did
    private static net.minecraft.world.level.block.Block lowBlock;
    // what we stood on last tick, if we were on the ground: a server that puts you back the moment you step onto its floor (a
    // desert's sand) does it with no fall at all
    private static net.minecraft.world.level.block.Block standBlock;
    private static int standFeet;
    // the cells we've stood in lately: being put back is going somewhere we've already been (the start, the last checkpoint),
    // where finishing a course takes you somewhere new
    private static final long[] SEEN = new long[4096];
    private static int seenNext, seenCount;
    private static long seenLast = Long.MIN_VALUE;
    // goes up every time something new is learned: a path planned before it may go right back down there
    private static volatile int generation;

    private FallFloor() {}

    public static int generation() {
        return generation;
    }

    // the highest floor learned round x z (Integer.MIN_VALUE for none)
    public static int learned(double x, double z) {
        if (!Baritone.settings().learnFallFloor.value) {
            return Integer.MIN_VALUE;
        }
        int best = Integer.MIN_VALUE;
        for (Learned l : FLOORS) {
            if (Math.abs(l.x() - x) <= AROUND && Math.abs(l.z() - z) <= AROUND) {
                best = Math.max(best, l.floor());
            }
        }
        return best;
    }

    public static void forget() {
        FLOORS.clear();
        DEADLY.clear();
        seenCount = seenNext = 0;
        seenLast = Long.MIN_VALUE;
    }

    // stood within r (across, a block up or down) of the cell at x y z lately, in this world
    public static boolean beenWithin(int bx, int by, int bz, int r) {
        for (int i = 0; i < seenCount; i++) {
            long c = SEEN[i];
            if (Math.abs(net.minecraft.core.BlockPos.getX(c) - bx) <= r && Math.abs(net.minecraft.core.BlockPos.getY(c) - by) <= 1
                    && Math.abs(net.minecraft.core.BlockPos.getZ(c) - bz) <= r) {
                return true;
            }
        }
        return false;
    }

    // stood in (or right next to) the cell at x y z lately, in this world
    public static boolean beenNear(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        for (int i = 0; i < seenCount; i++) {
            long c = SEEN[i];
            if (Math.abs(net.minecraft.core.BlockPos.getX(c) - bx) <= 1 && Math.abs(net.minecraft.core.BlockPos.getY(c) - by) <= 1
                    && Math.abs(net.minecraft.core.BlockPos.getZ(c) - bz) <= 1) {
                return true;
            }
        }
        return false;
    }

    // standing on block (in cell y - 1, feet at y) at x z is somewhere the server puts us back from
    public static boolean deadly(net.minecraft.world.level.block.Block block, int x, int feetY, int z) {
        if (DEADLY.isEmpty() || !Baritone.settings().learnFallFloor.value) {
            return false;
        }
        for (Deadly d : DEADLY) {
            if (d.block() == block && feetY <= d.maxFeetY()
                    && (d.x() == Integer.MIN_VALUE || Math.abs(d.x() - x) <= AROUND && Math.abs(d.z() - z) <= AROUND)) {
                return true;
            }
        }
        return false;
    }

    // for the tests, and a level whose rules are known: standing on block at feet height maxFeetY or lower resets you, anywhere
    public static void forbid(net.minecraft.world.level.block.Block block, int maxFeetY) {
        DEADLY.add(new Deadly(block, Integer.MIN_VALUE, 0, maxFeetY));
    }

    // every tick, with the player
    public static void tick(LocalPlayer p) {
        if (p == null) {
            return;
        }
        if (p.level() != world) {
            world = p.level();
            forget();
            lowBlock = null;
            lastZ = Double.NaN;
            topY = lowY = Double.NaN;
            sinceTeleport = 0;
        }
        sinceTeleport++;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!Double.isNaN(lastZ)) {
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            boolean across = dx * dx + dz * dz > 16;
            boolean teleported = across || dy > 2.5;
            // put back: up from where the fall got to, or anywhere well away (a server that catches you a block under the
            // course puts you back on a checkpoint at the same height, somewhere else)
            boolean settling = sinceTeleport < SETTLE_TICKS;
            if (teleported) {
                sinceTeleport = 0;
            }
            boolean fell = !Double.isNaN(topY) && topY - lowY >= 0.5 && (y > lowY + 2.5 || across);
            // (and not far: going from one course to another, a level's start that we've stood on before, is a teleport too)
            if (teleported && !settling && !fell && standBlock != null && across && dx * dx + dz * dz < PUT_BACK_RANGE * PUT_BACK_RANGE
                    && beenNear(x, y, z)) {
                // walked onto it and got put back straight away, back to where we'd been
                if (!deadly(standBlock, (int) Math.floor(lastX), standFeet, (int) Math.floor(lastZ))) {
                    DEADLY.add(new Deadly(standBlock, (int) Math.floor(lastX), (int) Math.floor(lastZ), standFeet));
                    generation++;
                    if (Baritone.settings().learnFallFloor.value) {
                        Helper.HELPER.logDirect("Stepping onto " + standBlock.getName().getString() + " at y " + standFeet + " put us back, so the paths round here keep off it there and lower now (#set learnFallFloor false to stop that)");
                    }
                }
            }
            if (teleported && !settling && fell) {
                if (lowBlock != null) {
                    // came down on lowBlock and got put back: standing on that, that low, is what does it
                    int feet = (int) Math.floor(lowY + 0.1251);
                    if (!deadly(lowBlock, (int) Math.floor(lastX), feet, (int) Math.floor(lastZ))) {
                        DEADLY.add(new Deadly(lowBlock, (int) Math.floor(lastX), (int) Math.floor(lastZ), feet));
                        generation++;
                        if (Baritone.settings().learnFallFloor.value) {
                            Helper.HELPER.logDirect("Landing on " + lowBlock.getName().getString() + " at y " + feet + " put us back, so the paths round here keep off it there and lower now (#set learnFallFloor false to stop that)");
                        }
                    }
                } else {
                    // never touched down, put back in mid air: that low is somewhere the server won't have us
                    int floor = (int) Math.floor(lowY + 1.0E-3) + 1;
                    if (floor > learned(lastX, lastZ)) {
                        FLOORS.add(new Learned((int) Math.floor(lastX), (int) Math.floor(lastZ), floor));
                        generation++;
                        if (Baritone.settings().learnFallFloor.value) {
                            Helper.HELPER.logDirect("Falling to y " + (floor - 1) + " put us back, so the paths round here stay at y " + floor + " or higher now (#set learnFallFloor false to stop that)");
                        }
                    }
                }
            }
            if (teleported) {
                topY = lowY = Double.NaN;
                lowBlock = null;
            }
        }
        if (p.onGround()) {
            standBlock = p.level().getBlockState(p.getOnPos()).getBlock();
            standFeet = (int) Math.floor(y + 0.1251);
            long cell = net.minecraft.core.BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y + 0.01), (int) Math.floor(z));
            if (cell != seenLast) {
                seenLast = cell;
                SEEN[seenNext] = cell;
                seenNext = (seenNext + 1) % SEEN.length;
                seenCount = Math.min(seenCount + 1, SEEN.length);
            }
        } else {
            standBlock = null;
        }
        groundTicks = p.onGround() || p.onClimbable() ? groundTicks + 1 : 0;
        if (p.onGround() || p.onClimbable()) {
            // on the ground: the start of the next fall, unless this is the bottom of one (and still there a second later,
            // it's just somewhere lower down, walked or dropped to)
            if (Double.isNaN(topY) || y >= topY - 1 || groundTicks > 20) {
                topY = y;
                lowY = y;
                lowBlock = null;
            } else {
                lowY = Math.min(lowY, y);
                net.minecraft.core.BlockPos on = p.getOnPos();
                lowBlock = p.level().getBlockState(on).getBlock();
            }
        } else if (!Double.isNaN(lowY)) {
            lowY = Math.min(lowY, y);
        }
        lastX = x;
        lastY = y;
        lastZ = z;
    }
}
