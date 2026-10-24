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

import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.PlayerSim;

import java.util.ArrayList;
import java.util.List;

// how to jump at a block from wherever you are, as a rule that looks at the player every tick and says which keys to hold.
// run at the landing (sprinting), jump at the last tick that still leaves from the floor (or earlier, for a short one), steer
// in the air so the coast would come down on the middle of the landing, and once down brake until we stand still on it.
//
// because it is a rule and not a list of keys, the planner and the executor run the very same thing: the planner rolls it
// out from a standing start on the takeoff block to find out whether a jump works and what it costs, the executor asks it
// for one tick of keys at a time from wherever the real player happens to be. the search (TungstenSearch) is what to use
// when no variant of the rule gets there
public final class JumpPolicy {

    public static final int MAX_RUN = 16, MAX_AIR = 60, MAX_SETTLE = 20;
    // vanilla's safe_fall_distance: a fall of more than this off the peak of the jump hurts
    public static final double SAFE_FALL = 3.0;

    // the furthest a fall may go: SAFE_FALL, or further if maxFallHeightNoWater says falls that far are fine (a parkour server
    // with fall damage off, where CubeCraft's levels drop four or five blocks at a time)
    public static double safeFall() {
        return Math.max(SAFE_FALL, baritone.Baritone.settings().maxFallHeightNoWater.value);
    }
    // standing still, near enough. below this the slide left is under a hundredth of a block
    static final double STILL = 0.02;

    // jumpAt < 0 is jump at the edge, the longest jump this run can make. NEVER runs off the edge without jumping at all, which
    // is how to drop two or three blocks across a gap without the extra 1.25 of the jump's peak turning it into a fall that
    // hurts. otherwise jump on that tick of the run (or at the edge, if it comes first). aim turns the run up that many degrees
    // off the line to the middle of the landing. back is a run up: first walk backwards (still facing the landing, so S) that
    // many blocks along the line away from it, then sprint at it from there. the only way across a four block gap, and what
    // makes an ice run up worth having
    // hop: under a ceiling, jump every time the feet touch the runway on the way to the edge. each jump is the sprint jump's
    // push again and the ceiling cuts the air time short, so it comes off the edge faster than any plain run: the head
    // hitter four block gap
    public record Variant(int jumpAt, float aim, float back, boolean hop) {
        public static final int NEVER = Integer.MAX_VALUE;
        public static final Variant EDGE = new Variant(-1, 0F, 0F, false);
        public static final Variant RUN_OFF = new Variant(NEVER, 0F, 0F, false);

        public Variant(int jumpAt, float aim) {
            this(jumpAt, aim, 0F, false);
        }

        public Variant(int jumpAt, float aim, float back) {
            this(jumpAt, aim, back, false);
        }

        @Override
        public String toString() {
            return (jumpAt < 0 ? "edge" : jumpAt == NEVER ? "run off" : "t" + jumpAt) + (aim != 0 ? (aim > 0 ? "+" : "") + aim : "")
                    + (back > 0 ? " after " + back + " back" : "") + (hop ? " hopping" : "");
        }
    }

    // EDGE first: it's the longest jump a standing start has in it, so when it comes up short no other standing start will
    // reach. then earlier and earlier jumps, which come down shorter and shorter, running off the edge for the drops, and last
    // the run ups, which cost the walk back but go further than anything
    public static final Variant[] VARIANTS = {
            Variant.EDGE, new Variant(3, 0F), new Variant(1, 0F), new Variant(0, 0F), new Variant(5, 0F), new Variant(7, 0F),
            Variant.RUN_OFF, new Variant(-1, 0F, 0.4F), new Variant(-1, 0F, 0.8F), new Variant(-1, 0F, 1.5F), new Variant(-1, 0F, 2.5F), new Variant(-1, 0F, 3.5F),
            new Variant(-1, 0F, 2.5F, true), new Variant(-1, 0F, 3.5F, true), new Variant(-1, 0F, 0F, true),
    };
    // a run up walks back at walking speed, a block in about 5 ticks
    static final int MAX_BACK_TICKS = 30;
    // on ice everything takes longer: the walk back slides, and the run takes a long time to get going (the speed comes from
    // the jumps, a hop's boost stays on ice where ground friction would eat it)
    static final int ICE_BACK_TICKS = 70, ICE_RUN = 48, ICE_HOP_RUN = 70;
    // hopping takes longer to get to the edge than running
    static final int MAX_HOP_RUN = 40;

    // how far off the middle of the landing cell the middle of the box can be and still be standing on the landing block: half
    // a block, plus the half width of the box hanging over the edge, less a margin so it's the block holding us and not a sliver
    // of it. coming down there is fine, settling walks us in the rest of the way
    static final double OVERHANG = 0.5 + 0.3 - 0.08;

    // GRIP: on the ladder (a grab landing), climbing or sliding to its cell
    public enum Phase { BACK, RUN, AIR, SETTLE, GRIP, DONE, FAILED }

    // what the rule remembers between ticks. the executor keeps one alive across the real ticks, a rollout makes its own
    public static final class Memo {
        public Phase phase = Phase.RUN;
        public int runTicks, airTicks, settleTicks;
        // highest feet y since we left the ground, for the fall damage
        public double peakY = Double.NEGATIVE_INFINITY;
        public String why;
        // a run up: where it started from and which way is back, worked out on the first tick
        boolean anchored;
        double anchorX, anchorZ, backX, backZ;
        int backTicks;
        // we've bounced off something (slime) since we left the ground
        public int bounces;
        // hopping along the runway: the height of the runway, NaN until the run starts
        double runFloorY = Double.NaN;
        // the straight line to the landing goes through something (a neo): the steering looks where it's going
        boolean neo;
        int gripTicks;
        // took off from something slippery (ice): the run up's limits are longer, see ICE_RUN
        boolean slip;
        private boolean slipKnown;
        // started in water (a jump out of a water block): swimming up it first, until we're out (see swimTick)
        int water; // 0 not known yet, 1 swimming, 2 out of it now, 3 never was
        int swimTicks;
        // the middle of the water's column we're swimming up
        double columnX, columnZ;

        public Memo copy() {
            Memo m = new Memo();
            m.phase = phase;
            m.runTicks = runTicks;
            m.airTicks = airTicks;
            m.settleTicks = settleTicks;
            m.peakY = peakY;
            m.why = why;
            m.anchored = anchored;
            m.anchorX = anchorX;
            m.anchorZ = anchorZ;
            m.backX = backX;
            m.backZ = backZ;
            m.backTicks = backTicks;
            m.bounces = bounces;
            m.runFloorY = runFloorY;
            m.neo = neo;
            m.gripTicks = gripTicks;
            m.slip = slip;
            m.slipKnown = slipKnown;
            m.water = water;
            m.swimTicks = swimTicks;
            m.columnX = columnX;
            m.columnZ = columnZ;
            return m;
        }

        Phase fail(String why) {
            this.why = why;
            return phase = Phase.FAILED;
        }
    }

    private JumpPolicy() {}

    // the keys for the coming tick, given the state the last one left. sim is only read; probe gets scribbled over (same
    // params and world as sim, it's for looking a tick ahead). memo moves on, and says DONE or FAILED when there is nothing
    // left to press
    public static PlayerSim.Controls decide(PlayerSim sim, PlayerSim probe, Landing land, Variant variant, Memo memo) {
        SimState now = SimState.of(sim);
        if (!memo.slipKnown) {
            memo.slipKnown = true;
            memo.slip = now.onGround && slippery(sim);
        }
        if (memo.phase == Phase.RUN && variant.back() > 0 && memo.backTicks == 0 && !memo.anchored) {
            memo.phase = Phase.BACK;
        }
        if (memo.water == 0) {
            memo.water = sim.copy().waterNow() ? 1 : 3;
        }
        if (memo.water == 1) {
            PlayerSim here = sim.copy();
            if (here.waterNow()) {
                if (memo.swimTicks == 0) {
                    findColumn(sim, now, memo);
                }
                return swimTick(now, here.waterDepth, land, memo);
            }
            memo.water = 2;
            memo.phase = Phase.AIR;
            memo.peakY = now.y;
        }
        advance(now, land, memo, variant, sim);
        float toward = Keys.yaw(land.centerX() - now.x, land.centerZ() - now.z);
        switch (memo.phase) {
            case BACK: {
                PlayerSim.Controls back = backTick(now, land, toward, variant, memo);
                if (back != null) {
                    return back;
                }
                return memo.phase == Phase.RUN ? runTick(now, probe, toward + variant.aim(), variant, memo) : Keys.release(toward);
            }
            case RUN:
                return runTick(now, probe, toward + variant.aim(), variant, memo);
            case AIR:
                if (++memo.airTicks > MAX_AIR) {
                    memo.fail("too long in the air");
                    return Keys.release(toward);
                }
                if (memo.neo) {
                    return neoAirTick(sim, probe, land, toward);
                }
                return airTick(now, land, toward, sim.params().gravity(), null);
            case GRIP:
                return gripTick(now, land, toward, memo);
            case SETTLE:
                return settleTick(now, probe, land, memo);
            default:
                return Keys.release(toward);
        }
    }

    // the longest a swim up out of a water block can take
    static final int MAX_SWIM = 60;
    // how far from the middle of the water's column towards the landing the swim up goes (still well in the water: the
    // player is 0.6 wide)
    static final double SWIM_LEAN = 0.6;

    // in the water we're jumping out of: up it (space swims up), kept in its column, and once the feet are near the top (the
    // water under getFluidJumpThreshold deep) a real jump, at the landing
    private static PlayerSim.Controls swimTick(SimState now, double depth, Landing land, Memo memo) {
        if (++memo.swimTicks > MAX_SWIM) {
            memo.fail("never got out of the water");
            return Keys.release(0F);
        }
        memo.peakY = now.y;
        float toward = Keys.yaw(land.centerX() - now.x, land.centerZ() - now.z);
        if (depth <= PlayerSim.FLUID_JUMP_THRESHOLD) {
            return new PlayerSim.Controls(1, 0, true, false, toward);
        }
        // kept in the water's column (a lone water block: out of its side is a fall), and on the way up already swimming at
        // the landing for the speed (the jump out of the top is a short one), as far over as still keeps us in the water.
        // where we'll be in a few ticks, at the speed we've got (coming into the water still moving, it's that that takes us
        // out the side)
        double tx = land.centerX() - now.x, tz = land.centerZ() - now.z, tl = Math.sqrt(tx * tx + tz * tz);
        double ux = tl < 1e-6 ? 0 : tx / tl, uz = tl < 1e-6 ? 0 : tz / tl;
        double lean = now.vy >= 0 ? SWIM_LEAN : 0;
        double px = now.x + now.vx * 2, pz = now.z + now.vz * 2;
        double gx = memo.columnX + ux * lean - px, gz = memo.columnZ + uz * lean - pz;
        if (gx * gx + gz * gz > 0.05 * 0.05) {
            return new PlayerSim.Controls(1, 0, true, false, Keys.yaw(gx, gz));
        }
        return new PlayerSim.Controls(0, 0, true, false, toward);
    }

    // the water cell we're in: the one under the middle of us if it's water, else the wettest one the box touches
    private static void findColumn(PlayerSim sim, SimState now, Memo memo) {
        int fx = (int) Math.floor(now.x), fz = (int) Math.floor(now.z);
        int y0 = (int) Math.floor(now.y), y1 = (int) Math.floor(now.y + 1.7);
        double best = -1;
        memo.columnX = fx + 0.5;
        memo.columnZ = fz + 0.5;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                double ox = Math.max(0, Math.min(fx + dx + 1, now.x + 0.3) - Math.max(fx + dx, now.x - 0.3));
                double oz = Math.max(0, Math.min(fz + dz + 1, now.z + 0.3) - Math.max(fz + dz, now.z - 0.3));
                if (ox * oz <= 0) {
                    continue;
                }
                for (int cy = y0; cy <= y1; cy++) {
                    if (sim.world().water(fx + dx, cy, fz + dz) > 0) {
                        double score = ox * oz + (dx == 0 && dz == 0 ? 1 : 0);
                        if (score > best) {
                            best = score;
                            memo.columnX = fx + dx + 0.5;
                            memo.columnZ = fz + dz + 0.5;
                        }
                        break;
                    }
                }
            }
        }
    }

    // the phase the state we were just handed puts us in
    private static void advance(SimState now, Landing land, Memo memo, Variant variant, PlayerSim sim) {
        if (land.water() && (memo.phase == Phase.AIR || memo.phase == Phase.RUN) && land.reached(now, 0)) {
            memo.phase = Phase.DONE; // in the water: no settling, it holds us up
            return;
        }
        if (memo.phase == Phase.BACK && !now.onGround) {
            memo.fail("walked off the back of the run up");
        }
        if (memo.phase == Phase.RUN && !now.onGround && variant.hop() && overRunway(now, memo, sim)) {
            return; // one of the hops along the runway, not the jump
        }
        if (memo.phase == Phase.RUN && !now.onGround) {
            memo.phase = Phase.AIR; // jumped, or ran off something on the way, it's flying either way now
        }
        if (land.grab() && (memo.phase == Phase.AIR || memo.phase == Phase.RUN) && land.gripping(now)) {
            memo.phase = Phase.GRIP; // caught it. a ladder resets the fall (handleOnClimbable)
            memo.peakY = now.y;
        }
        if (memo.phase == Phase.GRIP) {
            if (land.reached(now, 0)) {
                memo.phase = Phase.DONE;
            } else if (!land.gripping(now)) {
                memo.fail("let go of the ladder");
            }
            return;
        }
        if (memo.phase == Phase.AIR) {
            memo.peakY = Math.max(memo.peakY, now.y);
            if (now.onGround && now.vy > 1.0E-3) {
                // came down on something bouncy and it threw us back up. still flying, and the fall so far doesn't count
                // (landing on slime takes no damage)
                memo.bounces++;
                memo.peakY = now.y;
            } else if (now.onGround) {
                if (memo.peakY - now.y > safeFall()) {
                    memo.fail("that fall hurts");
                } else if (!onTheBlock(now, land) && !standingOn(now, land)) {
                    memo.fail("came down off the landing");
                } else {
                    memo.phase = Phase.SETTLE;
                }
            }
        }
        if (memo.phase == Phase.SETTLE) {
            if (!now.onGround || !onTheBlock(now, land) && !standingOn(now, land)) {
                memo.fail("slid off the landing");
            } else if (now.horizontalSpeed() < STILL && land.holdsWell(now.x, now.y, now.z)) {
                memo.phase = Phase.DONE;
            }
        }
    }

    // down on the landing block itself, however little of it is under us (the game says that's the block holding us up):
    // a touchdown on its last sliver is still a touchdown, settling walks us in off the edge
    static boolean standingOn(SimState s, Landing land) {
        return s.onGround && s.hasSupport && s.supportX == land.x() && s.supportY == land.y() - 1 && s.supportZ == land.z()
                && land.feetOk(s.y);
    }

    // standing at the landing's height with the box over the landing block, see OVERHANG
    static boolean onTheBlock(SimState s, Landing land) {
        return land.feetOk(s.y) && Math.abs(s.x - land.centerX()) < OVERHANG && Math.abs(s.z - land.centerZ()) < OVERHANG;
    }

    // the run up: S held facing the landing until we're `back` blocks behind where we started, along the line away from it
    private static PlayerSim.Controls backTick(SimState now, Landing land, float toward, Variant variant, Memo memo) {
        if (!memo.anchored) {
            double ex = land.centerX() - now.x, ez = land.centerZ() - now.z;
            double len = Math.sqrt(ex * ex + ez * ez);
            if (len < 1.0E-6) {
                memo.fail("nowhere to run up from");
                return Keys.release(toward);
            }
            memo.anchored = true;
            memo.anchorX = now.x;
            memo.anchorZ = now.z;
            memo.backX = -ex / len;
            memo.backZ = -ez / len;
        }
        double along = (now.x - memo.anchorX) * memo.backX + (now.z - memo.anchorZ) * memo.backZ;
        // the slide we're on would carry us this much further back
        double coast = (now.vx * memo.backX + now.vz * memo.backZ) * 1.2;
        if (along + Math.max(0, coast) >= variant.back() - 0.05 || ++memo.backTicks > (memo.slip ? ICE_BACK_TICKS : MAX_BACK_TICKS)) {
            memo.phase = Phase.RUN;
            memo.backTicks = Math.max(memo.backTicks, 1);
            return null; // the caller asks again, now as a run
        }
        float yaw = Keys.yaw(-memo.backX, -memo.backZ);
        return new PlayerSim.Controls(-1, 0, false, false, yaw);
    }

    // in the air on a hop, still over the runway it left (the floor it was running on is right under it, at that height)
    private static boolean overRunway(SimState now, Memo memo, PlayerSim sim) {
        if (Double.isNaN(memo.runFloorY) || now.y < memo.runFloorY - 1.0E-3) {
            return false;
        }
        int fy = net.minecraft.util.Mth.floor(memo.runFloorY - 0.5);
        baritone.pathing.sim.BoxList boxes = baritone.pathing.sim.BoxList.temp();
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                boxes.clear();
                sim.world().boxes(net.minecraft.util.Mth.floor(now.x + ox), fy, net.minecraft.util.Mth.floor(now.z + oz), boxes);
                for (int i = 0; i < boxes.size(); i++) {
                    if (Math.abs(boxes.get(i, 4) - memo.runFloorY) < 1.0E-3) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static PlayerSim.Controls runTick(SimState now, PlayerSim probe, float yaw, Variant variant, Memo memo) {
        if (variant.hop()) {
            if (Double.isNaN(memo.runFloorY) && now.onGround) {
                memo.runFloorY = now.y;
            }
            if (!now.onGround) {
                // mid hop: keep sprinting at it, and let go of space so the next touchdown can jump straight away
                if (memo.runTicks++ >= (memo.slip ? ICE_HOP_RUN : MAX_HOP_RUN)) {
                    memo.fail("never got to jump");
                    return Keys.release(yaw);
                }
                return Keys.run(yaw, false);
            }
        }
        if (memo.runTicks++ >= (variant.hop() ? memo.slip ? ICE_HOP_RUN : MAX_HOP_RUN : memo.slip ? ICE_RUN : MAX_RUN)) {
            memo.fail("never got to jump");
            return Keys.release(yaw);
        }
        if (now.horizontalCollision && !now.minorHorizontalCollision && memo.runTicks > 1) {
            memo.fail("ran into a wall");
            return Keys.release(yaw);
        }
        boolean jump = variant.jumpAt() >= 0 && variant.jumpAt() != Variant.NEVER && memo.runTicks - 1 >= variant.jumpAt();
        if (variant.hop() && memo.runTicks > 1 && (memo.slip || ceilingAt(probe, now.x, now.y, now.z, 2))) {
            // every touchdown under the ceiling (out from under it it's a plain run to the edge), and on ice every touchdown
            // anyway: each jump's push stays, the ice hardly takes any of it back
            jump = true;
        }
        if (!jump && variant.jumpAt() != Variant.NEVER) {
            // the edge: if this tick without space walks us off the floor, this tick is the last one we can jump on
            now.write(probe);
            if (!Keys.step(probe, Keys.run(yaw, false)) || !probe.onGround
                    || probe.horizontalCollision && !probe.minorHorizontalCollision && memo.runTicks > 1) {
                jump = true; // or the last tick before we run into a wall: a neo jumps off the corner of its takeoff
            }
        }
        memo.peakY = now.y; // from the floor, so a run off counts its whole drop
        return Keys.run(yaw, jump);
    }

    // in the air, pick the keys whose coast comes down closest to the middle of the landing: straight at it, at where the coast
    // is off by, a little either side of that, against the way we're going (W held facing back keeps the sprint, S wouldn't),
    // or nothing. scored a tick ahead in open air, see Keys.airStep
    private static final float[] SPREAD = {0F, 20F, -20F, 45F, -45F};

    private static PlayerSim.Controls airTick(SimState now, Landing land, float toward, double gravity, CollisionProvider walls) {
        double[] out = new double[6], hit = new double[2];
        double cx = land.centerX(), cz = land.centerZ();
        // a ladder is caught anywhere in its cell, aim to come down through the lower part of it
        double floorY = land.grab() || land.water() ? land.y() + 0.3 : land.floor();
        // where doing nothing at all comes down, and so which way the correction is
        float fix = toward;
        if (Keys.coastTo(now.x, now.y, now.z, now.vx, now.vy, now.vz, floorY, gravity, hit)) {
            double ex = cx - hit[0], ez = cz - hit[1];
            if (ex * ex + ez * ez > 1.0E-6) {
                fix = Keys.yaw(ex, ez);
            }
        }
        PlayerSim.Controls best = null;
        double bestScore = Double.MAX_VALUE;
        List<PlayerSim.Controls> options = new ArrayList<>(SPREAD.length + 3);
        options.add(Keys.run(toward, false));
        for (float s : SPREAD) {
            options.add(Keys.run(fix + s, false));
        }
        if (now.horizontalSpeed() > 1.0E-3) {
            options.add(Keys.run(Keys.yaw(-now.vx, -now.vz), false));
        }
        options.add(Keys.release(toward));
        for (PlayerSim.Controls c : options) {
            Keys.airStep(now, c.forward(), c.moveYaw(), c.sprint(), gravity, out);
            double score;
            if (Keys.coastTo(out[0], out[1], out[2], out[3], out[4], out[5], floorY, gravity, hit)) {
                double dx = cx - hit[0], dz = cz - hit[1];
                score = Math.sqrt(dx * dx + dz * dz);
                if (walls != null && blocked(walls, out[0], out[1], out[2], hit[0], floorY, hit[1])) {
                    score += 50; // that coast goes through the wall we're going round
                }
            } else {
                // falls short of (or under) the landing height: the further it stays from it, the worse
                double dx = cx - out[0], dz = cz - out[2];
                score = 100 + Math.sqrt(dx * dx + dz * dz);
            }
            if (score < bestScore - 1.0E-9) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    // on the landing, still moving: W held facing back the way we came stops us in a couple of ticks, but a tick of it too many
    // walks us back off the near edge. so brake only while the tick of braking doesn't turn us round, and coast otherwise.
    // came down hanging over the edge: walk in to the middle first (or coast in, if we're already going that way fast enough)
    private static PlayerSim.Controls settleTick(SimState now, PlayerSim probe, Landing land, Memo memo) {
        if (++memo.settleTicks > MAX_SETTLE) {
            memo.fail("never stopped on the landing");
            return Keys.release(0F);
        }
        if (!land.holdsWell(now.x, now.y, now.z)) {
            double ex = land.centerX() - now.x, ez = land.centerZ() - now.z;
            float in = Keys.yaw(ex, ez);
            // coasting gets there if the slide left (a tick's speed times 0.546 / (1 - 0.546)) covers the way in
            double along = (now.vx * ex + now.vz * ez) / Math.sqrt(ex * ex + ez * ez);
            PlayerSim.Controls walk = along * 1.2 > Math.sqrt(ex * ex + ez * ez) ? Keys.release(in) : Keys.push(in, false);
            now.write(probe);
            if (Keys.step(probe, walk) && probe.onGround && onTheBlock(SimState.of(probe), land)) {
                return walk;
            }
            memo.fail("can't walk in off the edge");
            return Keys.release(in);
        }
        float back = Keys.yaw(-now.vx, -now.vz);
        PlayerSim.Controls brake = Keys.push(back, false);
        now.write(probe);
        if (Keys.step(probe, brake) && probe.onGround && onTheBlock(SimState.of(probe), land)
                && probe.vx * now.vx + probe.vz * now.vz >= 0) {
            return brake;
        }
        PlayerSim.Controls coast = Keys.release(back);
        now.write(probe);
        if (Keys.step(probe, coast) && probe.onGround && onTheBlock(SimState.of(probe), land)) {
            return coast;
        }
        memo.fail("slides off the landing");
        return coast;
    }

    static final int MAX_GRIP = 40;

    // in the air going round a wall: every way W could point this tick, each played out to the end (real collisions, then
    // straight at the landing every tick after), and the one that gets there (soonest), or failing that ends up nearest.
    // the plain airTick only knows open air and turns in the moment the line looks clear, which clips the corner
    private static final float[] NEO_SPREAD = {0F, 20F, -20F, 45F, -45F, 90F, -90F};
    private static final int NEO_LOOKAHEAD = 18;

    private static PlayerSim.Controls neoAirTick(PlayerSim sim, PlayerSim probe, Landing land, float toward) {
        PlayerSim.Controls best = null;
        double bestScore = Double.MAX_VALUE;
        SimState now = SimState.of(sim);
        float lead = lead(now, land);
        // round where the speed says to aim, plus straight at it and nothing at all
        for (int o = 0; o <= NEO_SPREAD.length + 1; o++) {
            PlayerSim.Controls c = o == NEO_SPREAD.length + 1 ? Keys.release(toward)
                    : Keys.run(o == NEO_SPREAD.length ? toward : lead + NEO_SPREAD[o], false);
            now.write(probe);
            double score = playOut(probe, land, c);
            if (score < bestScore - 1.0E-9) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    // first c, then W at the landing every tick: how that goes. under 100 it got there (the ticks it took), over it didn't
    // (100 plus how far off it ended up)
    private static double playOut(PlayerSim p, Landing land, PlayerSim.Controls first) {
        PlayerSim.Controls c = first;
        for (int t = 0; t < NEO_LOOKAHEAD; t++) {
            if (!Keys.step(p, c)) {
                return 1000;
            }
            SimState s = SimState.of(p);
            if (land.grab() ? land.gripping(s) : s.onGround && onTheBlock(s, land)) {
                return t;
            }
            if (s.onGround || s.y < land.floor() - 1.5) {
                break;
            }
            c = Keys.run(lead(s, land), false);
        }
        return 100 + land.horizontalDistance(p.x, p.z) + Math.max(0, land.floor() - p.y);
    }

    // W at the landing less where the speed we have is taking us anyway: brakes what would overshoot, pushes what falls short
    private static float lead(SimState s, Landing land) {
        return Keys.yaw(land.centerX() - s.x - LEAD * s.vx, land.centerZ() - s.z - LEAD * s.vz);
    }

    private static final double LEAD = 4.0;

    // on the ladder: hold jump to climb up to the landing's cell, let go to slide down to it
    private static PlayerSim.Controls gripTick(SimState now, Landing land, float toward, Memo memo) {
        if (++memo.gripTicks > MAX_GRIP) {
            memo.fail("never got to the ladder's cell");
            return Keys.release(toward);
        }
        // hold the column: a vine has no box and a ladder only has one on one side, so whatever sideways speed we caught it
        // with carries us straight out the other side unless something pushes back. W at the middle while we're off it
        double off = land.horizontalDistance(now.x, now.z);
        int forward = off > 0.12 ? 1 : 0;
        boolean up = Landing.feetY(now.y) < land.y();
        return new PlayerSim.Controls(forward, 0, up, false, toward);
    }

    // does a box going in a straight line from a to b (feet), at body height over the higher of the two, run into anything.
    // that's a wall a jump has to go round: a neo
    public static boolean blocked(CollisionProvider w, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / 0.2));
        double top = Math.max(ay, by);
        baritone.pathing.sim.BoxList boxes = baritone.pathing.sim.BoxList.temp();
        for (int i = 0; i <= n; i++) {
            double px = ax + dx * i / n, pz = az + dz * i / n;
            for (double h : BODY) {
                double py = top + h;
                for (double ox : CORNERS) {
                    for (double oz : CORNERS) {
                        double qx = px + ox, qz = pz + oz;
                        boxes.clear();
                        w.boxes(net.minecraft.util.Mth.floor(qx), net.minecraft.util.Mth.floor(py), net.minecraft.util.Mth.floor(qz), boxes);
                        for (int k = 0; k < boxes.size(); k++) {
                            if (qx > boxes.get(k, 0) && qx < boxes.get(k, 3) && py > boxes.get(k, 1) && py < boxes.get(k, 4)
                                    && qz > boxes.get(k, 2) && qz < boxes.get(k, 5)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // the corners of the box and the middle of each side too: a fence or a wall post (a quarter to half a block wide) in the
    // middle of the line goes right between the corners
    private static final double[] BODY = {0.5, 1.2}, CORNERS = {-0.29, 0, 0.29};

    // round a wall: run off to one side of the line, jump at the edge (or the wall), and steer back in once past it
    public static final Variant[] NEO_VARIANTS = {
            new Variant(-1, 15F), new Variant(-1, -15F), new Variant(-1, 25F), new Variant(-1, -25F),
            new Variant(-1, 40F), new Variant(-1, -40F), new Variant(-1, 55F), new Variant(-1, -55F),
            new Variant(-1, 15F, 1.5F), new Variant(-1, -15F, 1.5F), new Variant(-1, 25F, 1.5F), new Variant(-1, -25F, 1.5F),
            new Variant(-1, 40F, 1.5F), new Variant(-1, -40F, 1.5F),
    };

    // the variants worth trying from start: the neo ones when the straight line goes through a wall, the rest otherwise
    public static Variant[] variants(PlayerSim start, Landing land) {
        // (not for a drop of two or more: the straight line down to it goes through the edge we drop off, and the neo
        // variants' look ahead steering is a hundred times the work, all of it for nothing)
        if (land.floor() > start.y - 1.5 && blocked(start.world(), start.x, start.y, start.z, land.centerX(), land.y(), land.centerZ())) {
            return NEO_VARIANTS;
        }
        return start.onGround && slippery(start) ? ICE_VARIANTS : VARIANTS;
    }

    // on ice a longer run up is still getting faster, and hopping along it (every touchdown a jump) faster still
    public static final Variant[] ICE_VARIANTS;

    static {
        List<Variant> ice = new ArrayList<>(List.of(VARIANTS));
        for (float back : new float[]{5.5F, 7.5F, 9.5F}) {
            ice.add(new Variant(-1, 0F, back));
        }
        for (float back : new float[]{1.5F, 5.5F, 7.5F, 9.5F}) {
            ice.add(new Variant(-1, 0F, back, true));
        }
        ICE_VARIANTS = ice.toArray(new Variant[0]);
    }

    // standing on something with ice's friction or more
    static boolean slippery(PlayerSim sim) {
        int x = net.minecraft.util.Mth.floor(sim.x), z = net.minecraft.util.Mth.floor(sim.z), y = net.minecraft.util.Mth.floor(sim.y - 0.5000001);
        return sim.world().friction(x, y, z) > 0.7F && sim.world().bounce(x, y, z) == 0F;
    }

    // a whole jump played out in the sim
    public record Rollout(boolean ok, Variant variant, List<PlayerSim.Controls> keys, List<SimState> states, String why) {

        public int ticks() {
            return keys.size();
        }

        public SimState last() {
            return states.get(states.size() - 1);
        }
    }

    // play the rule out from sim's state until it stands still on the landing or gives up. sim is moved along to the end
    public static Rollout rollout(PlayerSim sim, Landing land, Variant variant) {
        return rollout(sim, land, variant, new Memo());
    }

    public static Rollout rollout(PlayerSim sim, Landing land, Variant variant, Memo memo) {
        return rollout(sim, land, variant, memo, 0F);
    }

    // the same, with every yaw the rule asks for played this many degrees off. the rule doesn't know (it decides off the
    // state like it always does), which is how a real aim that's a bit off looks to it
    static Rollout rollout(PlayerSim sim, Landing land, Variant variant, Memo memo, float aimError) {
        PlayerSim probe = sim.copy();
        // the look ahead steering is for the neo variants (the ones aimed off the line): a few thousand sim ticks a jump
        if (memo.phase == Phase.RUN && memo.runTicks == 0 && !memo.neo && variant.aim() != 0F) {
            memo.neo = blocked(sim.world(), sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ());
        }
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        states.add(SimState.of(sim));
        double floor = Math.min(sim.y, land.floor()) - 1.0;
        int cap = ICE_BACK_TICKS + ICE_HOP_RUN + MAX_AIR + MAX_SETTLE + 4;
        for (int i = 0; i < cap; i++) {
            PlayerSim.Controls c = decide(sim, probe, land, variant, memo);
            if (memo.phase == Phase.DONE) {
                return new Rollout(true, variant, keys, states, null);
            }
            if (memo.phase == Phase.FAILED) {
                return new Rollout(false, variant, keys, states, memo.why);
            }
            PlayerSim.Controls played = aimError == 0F ? c
                    : new PlayerSim.Controls(c.forward(), c.left(), c.jump(), c.sprint(), c.moveYaw() + aimError, c.viewYaw() + aimError);
            if (!Keys.step(sim, played)) {
                return new Rollout(false, variant, keys, states, "the sim can't do " + sim.failure());
            }
            keys.add(c);
            states.add(SimState.of(sim));
            if (!sim.onGround && sim.y < floor && !bounceUnder(sim)) {
                return new Rollout(false, variant, keys, states, "fell short");
            }
        }
        return new Rollout(false, variant, keys, states, "took too long");
    }

    // a ceiling low enough to cut a jump short, two or three blocks over the feet where we start: the only place hopping
    // along the runway is worth trying
    static boolean ceiling(PlayerSim start) {
        // anywhere along the runway behind us too: a hop run up starts back there
        double ex = start.x, ez = start.z;
        for (int back = 0; back <= 4; back++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1 && ceilingAt(start, ex + dx * back, start.y, ez + dz * back, 3)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // something solid `upTo` blocks or less over the feet (from 2 up: one over the head is the lowest a ceiling can be)
    static boolean ceilingAt(PlayerSim sim, double x, double y, double z, int upTo) {
        int bx = net.minecraft.util.Mth.floor(x), bz = net.minecraft.util.Mth.floor(z), by = net.minecraft.util.Mth.floor(y + 1.0E-3);
        baritone.pathing.sim.BoxList boxes = baritone.pathing.sim.BoxList.temp();
        for (int up = 2; up <= upTo; up++) {
            sim.world().boxes(bx, by + up, bz, boxes);
        }
        return boxes.size() > 0;
    }

    // falling past the landing's height is only not falling short with something bouncy (slime) coming up underneath
    private static boolean bounceUnder(PlayerSim sim) {
        // anywhere a couple of blocks round where we're coming down, the steering can still get us over it
        int x = net.minecraft.util.Mth.floor(sim.x), z = net.minecraft.util.Mth.floor(sim.z), y = net.minecraft.util.Mth.floor(sim.y);
        for (int dy = 0; dy <= 8; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (sim.world().bounce(x + dx, y - dy, z + dz) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // best, but from wherever: on the ground that's best, in the air there's no variant left to pick, just the steering home.
    // peakY is how high this flight got so far (for the fall damage), ignored on the ground
    // the full search asks this at every node near the landing: plain variants only, a neo is the search's own business (the
    // neo variants' look ahead steering is a few thousand sim ticks a go, that many asks would eat its budget)
    public static Rollout bestFrom(PlayerSim start, Landing land, double peakY) {
        if (start.onGround) {
            return best(start, land, null, false, false);
        }
        Memo memo = new Memo();
        memo.phase = Phase.AIR;
        memo.peakY = Math.max(peakY, start.y);
        Rollout r = rollout(start.copy(), land, Variant.EDGE, memo);
        return r.ok() ? r : null;
    }

    // every variant from this state, best (fewest ticks) first that works. null if none does. start isn't touched
    public static Rollout best(PlayerSim start, Landing land) {
        return best(start, land, null);
    }

    // the same, trying `first` before the rest (the one that worked last time is the likeliest to work again)
    public static Rollout best(PlayerSim start, Landing land, Variant first) {
        return best(start, land, first, true);
    }

    static Rollout best(PlayerSim start, Landing land, Variant first, boolean neos) {
        return best(start, land, first, neos, true);
    }

    // shortBacks: the little step back to the far edge of a post (0.4, 0.8). the full search's shortcut leaves those out: off
    // a pad by a wall they find a way round that only works with the aim dead on, where the search's own would have room
    static Rollout best(PlayerSim start, Landing land, Variant first, boolean neos, boolean shortBacks) {
        Rollout best = null;
        if (first != null) {
            best = rollout(start.copy(), land, first);
            if (best.ok()) {
                return best;
            }
            best = null;
        }
        boolean standingShort = false;
        for (Variant v : neos ? variants(start, land) : VARIANTS) {
            if (v.equals(first) || standingShort && v.back() == 0 && !v.hop() || v.hop() && !ceiling(start) && !slippery(start)
                    || !shortBacks && v.back() > 0 && v.back() < 1) {
                continue;
            }
            Rollout r = rollout(start.copy(), land, v);
            if (r.ok()) {
                return r; // VARIANTS is ordered so the first that lands is the one to take, see there
            }
            if (v == Variant.EDGE && shortOfTheMiddle(r, land)) {
                // the longest standing jump came down short, the earlier ones only come down shorter. a run up might not
                standingShort = true;
            }
        }
        return best;
    }

    // the aim the executor really gets is the asked for yaw plus up to a degree or so either way, every tick
    // (LookBehavior's randomLooking113), so a variant that only works with a perfect aim is a coin toss. how far off the aim
    // can be, all the way through, for this variant to still land
    static final float[] AIM_ERRORS = {2.0F, -2.0F};

    // every variant that works from here, the one that's the most forgiving of a bad aim first (then the quickest).
    // a few times the work of best, which is fine for the executor (one of these a tick on the takeoff side) and too much for
    // the planner, which only wants to know whether there's a jump at all
    public static Rollout robust(PlayerSim start, Landing land, Variant first) {
        return robust(start, land, first, 0);
    }

    // the same, but only a rollout that stands at least minScore of AIM_ERRORS (with a searched plan to fall back on, a
    // rollout of the rule's that a wobble throws off is worse than the plan)
    public static Rollout robust(PlayerSim start, Landing land, Variant first, int minScore) {
        Rollout pick = null;
        int pickScore = -1;
        List<Variant> order = new ArrayList<>();
        if (first != null) {
            order.add(first);
        }
        for (Variant v : variants(start, land)) {
            if (!v.equals(first)) {
                order.add(v);
            }
        }
        boolean standingShort = false;
        for (Variant v : order) {
            if (standingShort && v.back() == 0 && !v.hop() || v.hop() && !ceiling(start) && !slippery(start)) {
                continue;
            }
            Rollout r = rollout(start.copy(), land, v);
            if (!r.ok() || bumpsAWall(r)) {
                if (!r.ok() && v == Variant.EDGE && shortOfTheMiddle(r, land)) {
                    standingShort = true; // see best
                }
                continue;
            }
            int score = 0;
            for (float e : AIM_ERRORS) {
                if (rollout(start.copy(), land, v, new Memo(), e).ok()) {
                    score++;
                }
            }
            if (minScore > 0 && v.aim() != 0F) {
                // (a neo of the rule's has to stand the worse aim forgiving asks of one too)
                for (float e : NEO_AIM_ERRORS) {
                    if (!rollout(start.copy(), land, v, new Memo(), e).ok()) {
                        score = -1;
                        break;
                    }
                }
                if (score < 0) {
                    continue;
                }
            }
            if (score > pickScore || score == pickScore && r.ticks() < pick.ticks()) {
                pick = r;
                pickScore = score;
            }
            if (pickScore == AIM_ERRORS.length && v != first) {
                break; // can't do better than all of them, and the order already put the quicker ones first
            }
        }
        return pickScore >= minScore ? pick : null;
    }

    // the planner's question: is there a variant that lands even with the aim off by AIM_ERRORS all the way through. a jump
    // that only works with a perfect aim gets flown by a bot whose aim isn't, so it isn't one to plan. quickest such first
    public static Rollout forgiving(PlayerSim start, Landing land) {
        return forgiving(start, land, null);
    }

    // the same, trying `first` before the rest
    public static Rollout forgiving(PlayerSim start, Landing land, Variant first) {
        boolean standingShort = false;
        Rollout tight = null;
        List<Variant> order = new ArrayList<>();
        if (first != null) {
            order.add(first);
        }
        for (Variant v : variants(start, land)) {
            if (!v.equals(first)) {
                order.add(v);
            }
        }
        for (Variant v : order) {
            if (standingShort && v.back() == 0 && !v.hop() || v.hop() && !ceiling(start) && !slippery(start)) {
                continue;
            }
            Rollout r = rollout(start.copy(), land, v);
            if (!r.ok() || bumpsAWall(r) || onTheEdge(r, land)) {
                if (!r.ok() && v == Variant.EDGE && shortOfTheMiddle(r, land)) {
                    standingShort = true;
                }
                continue;
            }
            boolean all = true;
            // a neo variant curves round a wall with little to spare, and a wobble that sends a straight jump a bit wide
            // sends one of these into the wall: it has to stand a worse aim than the rest
            for (float e : v.aim() != 0F ? NEO_AIM_ERRORS : AIM_ERRORS) {
                if (!rollout(start.copy(), land, v, new Memo(), e).ok()) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return r;
            }
            if (tight == null && v.aim() == 0F) {
                // (never a neo of the rule's: curving round a wall, a degree is too little to spare)
                // not with the aim 2 degrees off the whole way, but with it 1 off: a small landing (a wall's post, a pot) that
                // the real aim, which wobbles about the right line rather than sitting off it, still makes. kept in case
                // there's nothing better
                boolean one = true;
                for (float e : TIGHT_AIM_ERRORS) {
                    if (!rollout(start.copy(), land, v, new Memo(), e).ok()) {
                        one = false;
                        break;
                    }
                }
                if (one) {
                    tight = new Rollout(true, r.variant(), r.keys(), r.states(), TIGHT);
                }
            }
        }
        return tight;
    }

    private static final float[] TIGHT_AIM_ERRORS = {1.0F, -1.0F};

    // how far off the landing's middle the touchdown can be, for the planner: the rule settles in from a sliver of the edge,
    // but in the real game a flight that's a fifth of a block short of a sliver is a fall (the corner of a stair, the side of
    // a post). a plan has to come down with the middle of us over the landing's cell, or near enough
    static final double TOUCHDOWN = 0.65, TOUCHDOWN_AIMED = 0.4;

    static boolean onTheEdge(Rollout r, Landing land) {
        if (land.grab() || land.water()) {
            return false;
        }
        boolean up = false;
        List<SimState> states = r.states();
        for (int i = 0; i < states.size(); i++) {
            SimState s = states.get(i);
            if (!s.onGround) {
                up = true;
            } else if (up) {
                // the first time down: on the landing, unless it's straight back off the ground again (a bounce off slime on
                // the way there, a hop along the run up under a ceiling), which is somewhere else
                if (i + 1 < states.size() && !states.get(i + 1).onGround) {
                    continue;
                }
                double dx = s.x - land.centerX(), dz = s.z - land.centerZ();
                double limit = land.ax() != 0.5 || land.az() != 0.5 ? TOUCHDOWN_AIMED : TOUCHDOWN;
                return dx * dx + dz * dz > limit * limit; // (a corner's further out than a side)
            }
        }
        return false;
    }

    // what a rollout that only stands TIGHT_AIM_ERRORS says for why
    public static final String TIGHT = "tight";

    private static final float[] NEO_AIM_ERRORS = {2.0F, -2.0F, 3.5F, -3.5F};

    // the run up runs into the side of something before the jump. whether the game keeps the sprint after a bump depends on
    // where the camera looks (LocalPlayer's minor collision check goes by the real yaw, and with freeLook that's wherever
    // the player left it, not the way we're going), and a jump without the sprint has none of its push. a jump that needs
    // a bump first isn't planned: the searches go round with room to spare instead. (a bump in the air after the last jump,
    // a bounce up the side of a ledge, only costs a little air steering either way)
    static boolean bumpsAWall(Rollout r) {
        List<SimState> s = r.states();
        List<PlayerSim.Controls> k = r.keys();
        for (int i = 1; i < s.size(); i++) {
            if (!s.get(i).horizontalCollision) {
                continue;
            }
            for (int j = i; j < k.size(); j++) {
                if (k.get(j).jump()) {
                    return true;
                }
            }
        }
        return false;
    }

    // a rollout that failed in the air on the near side of the landing, without hitting its head on the way (a bonk is
    // short for a reason a different jump tick might not have)
    private static boolean shortOfTheMiddle(Rollout r, Landing land) {
        if (r.ok() || r.states().size() < 2) {
            return false;
        }
        for (SimState s : r.states()) {
            if (s.verticalCollision && !s.onGround) {
                return false;
            }
        }
        SimState start = r.states().get(0), end = r.last();
        double tx = land.centerX() - start.x, tz = land.centerZ() - start.z;
        double ex = end.x - start.x, ez = end.z - start.z;
        double len = Math.sqrt(tx * tx + tz * tz);
        return len > 1.0E-6 && (ex * tx + ez * tz) / len < len - 0.5 && end.y < land.floor();
    }
}
