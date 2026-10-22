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

// flies one jump for real, a tick at a time, off the live player's state. no game in here (MovementTungsten hands it the
// sim and presses what it says), so the tests can fly it against a sim with the aim jittered the way LookBehavior jitters it.
//
// on the takeoff side it follows a plan: the rule's rollout from where we are, checked against where the player really is
// every tick, and planned again from here the moment they part (keeping the jump on the tick it was going to be, or a plan
// that keeps getting redone would keep putting the jump off until the last tick that still works). once off the ground the
// plan is followed while it holds, and after that the rule steers and brakes by itself
public final class JumpPilot {

    // what the caller should do this tick
    public enum Status {
        PRESS, // press keys
        DONE, // standing still on the landing
        STUCK, // standing still at the planner's start and the rule still has nothing: time for the full search
        FAILED // gone wrong somewhere there's no coming back from (see why)
    }

    public record Decision(Status status, PlayerSim.Controls keys, String why) {
        static Decision press(PlayerSim.Controls keys) {
            return new Decision(Status.PRESS, keys, null);
        }
    }

    // a plan is followed while the real player is within this of where it said (SimState.distance)
    public static final double PLAN_EPS = 0.06;
    // the same for the planner's fallback plan, which no rule can replan: more slack, and a run that drifts past it walks back
    // and starts over
    static final double FALLBACK_EPS = 0.2;
    // a search from mid air has a few ticks at most to be useful, so it runs right here with a small budget
    public static final long AIR_SEARCH_BUDGET = 30_000;

    // staging: close enough to the takeoff spot, and slow enough to call it standing
    private static final double STAGE_OFF = 0.1, STAGE_OFF_FALLBACK = 0.04, STAGE_STILL = 0.01;

    private final Landing land;
    // where the planner rolled the jump out from: the middle of src, standing
    private final double startX, startZ;
    private final JumpPolicy.Variant planned;
    private JumpPolicy.Variant variant;
    private JumpPolicy.Memo memo;
    private double groundY = Double.NaN;
    private boolean flew;
    // done backing up for a run up (or never had one), running at it now
    private boolean ranUp;
    private double backX = Double.NaN, backZ = Double.NaN;
    private TungstenSearch.Plan plan;
    private int planAt;
    // ticks we've replanned on the takeoff side, and how many of those because the player wasn't where the plan said
    public int replans, drifts;

    public JumpPilot(Landing land, double startX, double startZ, JumpPolicy.Variant planned) {
        this.land = land;
        this.startX = startX;
        this.startZ = startZ;
        this.planned = planned;
    }

    public boolean flew() {
        return flew;
    }

    // a plan from the planner's full search for a jump the rule can't do: flown from the takeoff spot once we're standing still
    // on it (see stage), and whenever we're back there
    private TungstenSearch.Plan fallback;
    // following fallback rather than a plan of the rule's: a bit more slack, there's no rule to replan with
    private boolean onFallback;

    public void fallback(TungstenSearch.Plan plan) {
        this.fallback = plan;
    }

    public boolean following() {
        return plan != null;
    }

    // a plan from somewhere else (the full search), followed from its first tick on
    public void follow(TungstenSearch.Plan plan) {
        this.plan = plan;
        this.planAt = 0;
        this.onFallback = false;
    }

    public Decision tick(PlayerSim live) {
        if (live.onGround) {
            if (!flew) {
                groundY = live.y;
            }
        } else {
            flew = true;
        }
        if (plan != null) {
            SimState now = SimState.of(live);
            if (planAt >= plan.ticks()) {
                if (land.reached(now, JumpPolicy.STILL * 2)) {
                    return new Decision(Status.DONE, null, null);
                }
            } else if (now.distance(plan.states().get(planAt)) < (onFallback ? FALLBACK_EPS : PLAN_EPS) && !leavesEarly(live, plan, planAt)) {
                PlayerSim.Controls k = plan.keys().get(planAt++);
                if (k.forward() > 0) {
                    ranUp = true;
                }
                return Decision.press(k);
            } else {
                drifts++;
            }
            int jumpIn = jumpIn(plan, planAt);
            plan = null;
            if (!flew) {
                return fromTheGround(live, jumpIn);
            }
        }
        if (!flew) {
            return fromTheGround(live, -1);
        }
        if (memo == null) {
            memo = new JumpPolicy.Memo();
            memo.phase = JumpPolicy.Phase.AIR;
            memo.peakY = Double.isNaN(groundY) ? live.y : Math.max(groundY, live.y);
            // going round a wall: the steering that looks ahead (see JumpPolicy.neoAirTick)
            memo.neo = JumpPolicy.blocked(live.world(), startX, Double.isNaN(groundY) ? live.y : groundY, startZ, land.centerX(), land.y(), land.centerZ());
            // already down: the rule's AIR looks at the touchdown (the fall, which block) first thing and moves on to SETTLE
        }
        PlayerSim.Controls keys = JumpPolicy.decide(live, live.copy(), land, JumpPolicy.Variant.EDGE, memo);
        switch (memo.phase) {
            case DONE:
                return new Decision(Status.DONE, null, null);
            case FAILED:
                if (!live.onGround) {
                    // the rule has lost it. a short search from right here, a flight only has a few ticks left in it
                    TungstenSearch.Plan rescue = TungstenSearch.search(live, land, region(live, land), AIR_SEARCH_BUDGET, null, memo.peakY);
                    if (rescue != null && rescue.ticks() > 0) {
                        memo.phase = JumpPolicy.Phase.AIR;
                        memo.airTicks = 0;
                        follow(rescue);
                        return Decision.press(plan.keys().get(planAt++));
                    }
                }
                return new Decision(Status.FAILED, null, memo.why);
            default:
                return Decision.press(keys);
        }
    }

    // on the takeoff side, no plan: roll the whole jump out from right here and press its first tick. jumpIn is when the plan
    // we just came off was going to jump, tried first
    private Decision fromTheGround(PlayerSim live, int jumpIn) {
        replans++;
        JumpPolicy.Variant first = jumpIn >= 0 ? new JumpPolicy.Variant(jumpIn, 0F) : variant != null ? variant : planned;
        if (!ranUp && (variant != null ? variant : planned) != null && (variant != null ? variant : planned).back() > 0) {
            // knocked off a run up while still backing up: back up what's left of it, measured from where it started
            JumpPolicy.Variant runUp = variant != null ? variant : planned;
            double ax = Double.isNaN(backX) ? startX : backX, az = Double.isNaN(backZ) ? startZ : backZ;
            double ex = land.centerX() - ax, ez = land.centerZ() - az, len = Math.sqrt(ex * ex + ez * ez);
            double done = len < 1.0E-6 ? 0 : -((live.x - ax) * ex + (live.z - az) * ez) / len;
            double left = runUp.back() - done;
            first = left > 0.2 ? new JumpPolicy.Variant(-1, runUp.aim(), (float) left, runUp.hop())
                    : new JumpPolicy.Variant(-1, runUp.aim(), 0F, runUp.hop());
        } else if (variant != null && variant.hop()) {
            first = new JumpPolicy.Variant(-1, variant.aim(), 0F, true); // the jump tick of a hopping run is every touchdown
        }
        JumpPolicy.Rollout r = JumpPolicy.robust(live, land, first);
        if (r == null) {
            return stage(live);
        }
        if (r.ticks() == 0) {
            return new Decision(Status.DONE, null, null);
        }
        variant = r.variant();
        backX = live.x; // where this variant's run up (if it has one) is measured from
        backZ = live.z;
        follow(new TungstenSearch.Plan(r.keys(), r.states(), 0, 0));
        PlayerSim.Controls k = plan.keys().get(planAt++);
        if (k.forward() > 0) {
            ranUp = true;
        }
        return Decision.press(k);
    }

    // the rule has nothing from here: go and stand where the planner said it works from (the middle of src, still), and only
    // if it still says no there is it a STUCK
    private Decision stage(PlayerSim live) {
        double ex = startX - live.x, ez = startZ - live.z;
        double off = Math.sqrt(ex * ex + ez * ez);
        double speed = Math.sqrt(live.vx * live.vx + live.vz * live.vz);
        float in = Keys.yaw(ex, ez);
        double stageOff = fallback != null ? STAGE_OFF_FALLBACK : STAGE_OFF;
        if (off > stageOff) {
            // close and still moving: let go and coast. ground friction eats 45% a tick, holding W the whole way overshoots
            return Decision.press(off < 0.3 && speed > 0.05 ? Keys.release(in) : Keys.push(in, false));
        }
        if (speed > STAGE_STILL) {
            return Decision.press(Keys.release(in)); // coast to a stop
        }
        if (fallback != null) {
            follow(fallback);
            onFallback = true;
            return Decision.press(plan.keys().get(planAt++));
        }
        return new Decision(Status.STUCK, null, "the rule has nothing from here");
    }

    // on the ground, the plan's keys for this tick would take us off it when the plan has us still on it next tick: we're a
    // hair further along than it thought (the aim), and its jump a tick later comes from the air, or never. replan now
    private static boolean leavesEarly(PlayerSim live, TungstenSearch.Plan plan, int at) {
        if (!live.onGround || at + 1 >= plan.states().size() || !plan.states().get(at + 1).onGround) {
            return false;
        }
        PlayerSim probe = live.copy();
        return Keys.step(probe, plan.keys().get(at)) && !probe.onGround;
    }

    // ticks from keys[at] until the plan presses jump, -1 if it never does
    private static int jumpIn(TungstenSearch.Plan plan, int at) {
        for (int i = at; i < plan.ticks(); i++) {
            if (plan.keys().get(i).jump()) {
                return i - at;
            }
        }
        return -1;
    }

    public static TungstenSearch.Region region(PlayerSim start, Landing land) {
        return TungstenSearch.Region.around(start.x, start.z, land.centerX(), land.centerZ(), Math.min(start.y, land.y()) - 4, 3);
    }
}
