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

import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

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
    // what the last parting from a plan looked like, for the debug log
    public String lastDrift;
    // what the last tick spent its time on, for the debug log's slow ticks
    public String lastWork = "";

    public String variantNow() {
        return (onFallback ? "fallback " : "") + variant;
    }
    // finished on the touchdown, not standing still
    public boolean flowed;

    public JumpPilot(Landing land, double startX, double startZ, JumpPolicy.Variant planned) {
        this.land = land;
        this.startX = startX;
        this.startZ = startZ;
        this.planned = planned;
    }

    // asked on every tick we're down on the landing after the flight, before it's settled: can whatever comes next go from
    // right here, moving as we are. yes and we're done, the speed carries into it instead of being braked away
    private java.util.function.Predicate<PlayerSim> flow;

    public void flow(java.util.function.Predicate<PlayerSim> flow) {
        this.flow = flow;
    }

    // the next jump's whole rule, with the aim errors, is tens of ms: asked on the first couple of ticks down and that's it,
    // after that we're slow enough that settling costs next to nothing anyway
    private int flowTries;
    private static final int MAX_FLOW_TRIES = 2;

    private boolean flowCheck(PlayerSim live) {
        if (flowTries++ >= MAX_FLOW_TRIES) {
            return false;
        }
        lastWork += " flow";
        return flow.test(live);
    }

    public boolean flew() {
        return flew;
    }

    // a plan from the planner's full search for a jump the rule can't do: flown from the takeoff spot once we're standing still
    // on it (see stage), and whenever we're back there
    private TungstenSearch.Plan fallback;
    private boolean ruleRefused;
    private int fineTicks;
    // following fallback rather than a plan of the rule's: a bit more slack, there's no rule to replan with
    private boolean onFallback;

    public void fallback(TungstenSearch.Plan plan) {
        this.fallback = plan;
        // the planner only hands one of these over when the rule had nothing: no point asking it again (dozens of ms)
        this.ruleRefused = plan != null;
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

    // the same, steering back onto it every tick (see track): for a searched plan, which no rule can replan
    public void track(TungstenSearch.Plan plan) {
        follow(plan);
        tracking = true;
    }

    // following a searched plan: its keys are one exact flight, and a real aim that's off by a degree goes somewhere else by
    // the end of it. so every tick the plan's keys get the yaw nudged whichever way puts us back on its states soonest
    private boolean tracking;
    private static final float[] NUDGE = {0F, 1.5F, -1.5F, 3F, -3F, 6F, -6F, 10F, -10F};

    static PlayerSim.Controls track(PlayerSim live, TungstenSearch.Plan plan, int at) {
        PlayerSim.Controls k = plan.keys().get(at);
        if (at + 1 >= plan.states().size()) {
            return k;
        }
        PlayerSim.Controls best = k;
        double bestScore = Double.MAX_VALUE;
        for (float n : NUDGE) {
            PlayerSim.Controls c = n == 0F ? k : new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), k.moveYaw() + n, k.viewYaw() + n);
            PlayerSim probe = live.copy();
            if (!Keys.step(probe, c)) {
                continue;
            }
            double score = SimState.of(probe).distance(plan.states().get(at + 1));
            // and a couple of the plan's own ticks on from there: a nudge that's right now and wrong later isn't
            for (int i = at + 1; i < Math.min(at + 3, plan.ticks()); i++) {
                if (!Keys.step(probe, plan.keys().get(i))) {
                    score += 10;
                    break;
                }
                score += SimState.of(probe).distance(plan.states().get(i + 1));
            }
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (live.onGround && !best.jump()) {
            // a step or two ahead of the plan (the aim): these keys walk us off the edge a tick before the plan's jump.
            // jump now, off the last bit of floor there is, and let the steering sort out the rest in the air
            int jumpIn = jumpIn(plan, at);
            PlayerSim probe = live.copy();
            if (jumpIn > 0 && jumpIn <= 3 && Keys.step(probe, best) && !probe.onGround) {
                best = best.withJump(true);
            }
        }
        return best;
    }

    public Decision tick(PlayerSim live) {
        lastWork = "";
        if (live.onGround) {
            if (!flew) {
                groundY = live.y;
            }
        } else if (!flew && !overTakeoff(live)) {
            // off the ground and not over the floor it left: a hop along the runway (head hitter, ice) isn't the jump yet
            flew = true;
        }
        if (flew && flow != null && live.onGround && live.vy <= 1.0E-3 && !land.grab() && land.holds(live.x, live.y, live.z)
                && (memo == null || memo.phase != JumpPolicy.Phase.FAILED) && flowCheck(live)) {
            flowed = true;
            return new Decision(Status.DONE, null, null);
        }
        if (plan != null) {
            SimState now = SimState.of(live);
            if (planAt >= plan.ticks()) {
                if (land.reached(now, JumpPolicy.STILL * 2)) {
                    return new Decision(Status.DONE, null, null);
                }
            } else if (now.distance(plan.states().get(planAt)) < (onFallback || tracking ? FALLBACK_EPS : PLAN_EPS) && (tracking || !leavesEarly(live, plan, planAt))) {
                PlayerSim.Controls k = tracking ? track(live, plan, planAt) : plan.keys().get(planAt);
                planAt++;
                if (k.forward() > 0) {
                    ranUp = true;
                }
                return Decision.press(k);
            } else {
                drifts++;
                lastDrift = "tick " + planAt + "/" + plan.ticks() + (tracking ? " tracked" : "") + ": at " + now + ", plan " + plan.states().get(planAt);
            }
            int jumpIn = jumpIn(plan, planAt);
            boolean wasSearched = tracking;
            plan = null;
            tracking = false;
            if (wasSearched && flew && live.onGround && !land.reached(now, JumpPolicy.STILL * 2)) {
                // came off a searched plan with a touchdown in the middle of it (two neos in a row, the pad between them): the
                // rule only knows one jump at a time and this one isn't over, a short search from right here picks it up
                TungstenSearch.Plan rescue = TungstenSearch.search(live, land, region(live, land), AIR_SEARCH_BUDGET, null, live.y);
                if (rescue != null && rescue.ticks() > 0) {
                    follow(rescue);
                    tracking = true;
                    return Decision.press(track(live, plan, planAt++));
                }
            }
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
                        tracking = true;
                        return Decision.press(track(live, plan, planAt++));
                    }
                }
                return new Decision(Status.FAILED, null, memo.why);
            default:
                return Decision.press(keys);
        }
    }

    // in the air over the floor we took off from, at its height: one of the hops along a runway, and it comes down on it
    private boolean overTakeoff(PlayerSim live) {
        if (Double.isNaN(groundY) || live.y < groundY - 1.0E-3) {
            return false;
        }
        int fy = Mth.floor(groundY - 0.5);
        BoxList boxes = BoxList.temp();
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                boxes.clear();
                live.world().boxes(Mth.floor(live.x + ox), fy, Mth.floor(live.z + oz), boxes);
                for (int i = 0; i < boxes.size(); i++) {
                    if (Math.abs(boxes.get(i, 4) - groundY) < 1.0E-3) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // on the takeoff side, no plan: roll the whole jump out from right here and press its first tick. jumpIn is when the plan
    // we just came off was going to jump, tried first
    private Decision fromTheGround(PlayerSim live, int jumpIn) {
        if (!live.onGround) {
            // mid hop along the runway and off the plan: keep running at it, and plan again from the touchdown
            return Decision.press(Keys.run(Keys.yaw(land.centerX() - live.x, land.centerZ() - live.z), false));
        }
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
        // with a searched plan to fall back on, once the rule has said no it isn't asked again every tick of the staging
        if (!(fallback != null && ruleRefused)) {
            lastWork += " robust";
        }
        JumpPolicy.Rollout r = fallback != null && ruleRefused ? null
                : JumpPolicy.robust(live, land, first, fallback != null ? JumpPolicy.AIM_ERRORS.length : 0);
        if (r == null) {
            ruleRefused = true;
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
        if (fallback != null && off <= STAGE_NEAR && speed <= STAGE_NEAR_SPEED && tracksFrom(live, fallback)) {
            // near enough: the searched plan, steered back onto as it goes, still lands from right here (in the sim). the
            // real look wobbles too much to ever stand within a few hundredths of the exact spot
            follow(fallback);
            onFallback = true;
            tracking = true;
            return Decision.press(track(live, plan, planAt++));
        }
        if (fallback != null && off <= 0.12 && speed <= STAGE_NEAR_SPEED && ++fineTicks > FINE_GIVE_UP) {
            // been at it a while and it won't settle any closer: go from here, the steering takes up the rest
            fineTicks = 0;
            follow(fallback);
            onFallback = true;
            tracking = true;
            return Decision.press(track(live, plan, planAt++));
        }
        if (fallback != null && off <= FINE_STAGE && (off > stageOff || speed > STAGE_STILL)) {
            // a searched plan wants the exact spot, and one tick of W is a fifth of a block by the time it's coasted out:
            // walking at it overshoots back and forth for ever. look two ticks ahead instead (any push this tick, any push
            // the next, then coast) and take whichever comes to rest nearest the spot
            return Decision.press(fineStage(live));
        }
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
            tracking = true;
            return Decision.press(track(live, plan, planAt++));
        }
        return new Decision(Status.STUCK, null, "the rule has nothing from here");
    }

    private static final boolean FINE_DEBUG = System.getenv("FINE_DEBUG") != null;
    private static final double STAGE_NEAR = 0.25, STAGE_NEAR_SPEED = 0.04, FINE_STAGE = 0.4;
    private static final int FINE_YAWS = 16, FINE_COAST = 10, FINE_GIVE_UP = 30;

    private PlayerSim.Controls fineStage(PlayerSim live) {
        lastWork += " fine-stage";
        List<PlayerSim.Controls> options = new ArrayList<>(FINE_YAWS + 1), seconds = new ArrayList<>(FINE_YAWS / 2 + 1);
        options.add(Keys.release(Keys.yaw(startX - live.x, startZ - live.z)));
        seconds.add(options.get(0));
        for (int i = 0; i < FINE_YAWS; i++) {
            options.add(Keys.push(i * 360F / FINE_YAWS, false));
            if (i % 2 == 0) {
                seconds.add(options.get(options.size() - 1)); // the second tick only fixes up, half the yaws will do
            }
        }
        PlayerSim.Controls best = options.get(0);
        double bestOff = Double.MAX_VALUE;
        for (PlayerSim.Controls first : options) {
            PlayerSim one = live.copy();
            if (!Keys.step(one, first) || !one.onGround) {
                continue;
            }
            for (PlayerSim.Controls second : seconds) {
                PlayerSim two = one.copy();
                double rest = restOff(two, second);
                if (FINE_DEBUG) {
                    System.out.println("fine " + first.forward() + "@" + first.moveYaw() + " then " + second.forward() + "@" + second.moveYaw() + " -> " + rest);
                }
                if (rest < bestOff) {
                    bestOff = rest;
                    // letting go first and pushing after is the same as pushing now when we're standing still, and picking
                    // "let go" every tick is how you stand there for ever
                    best = first.forward() == 0 && second.forward() != 0 && live.vx * live.vx + live.vz * live.vz < 1.0E-6 ? second : first;
                }
            }
        }
        return best;
    }

    // the keys, then nothing until it stops: how far from the spot that is (MAX_VALUE if it went off the floor on the way)
    private double restOff(PlayerSim sim, PlayerSim.Controls keys) {
        if (!Keys.step(sim, keys) || !sim.onGround) {
            return Double.MAX_VALUE;
        }
        PlayerSim.Controls none = Keys.release(0F);
        for (int i = 0; i < FINE_COAST && sim.vx * sim.vx + sim.vz * sim.vz > 1.0E-6; i++) {
            if (!Keys.step(sim, none) || !sim.onGround) {
                return Double.MAX_VALUE;
            }
        }
        double ex = startX - sim.x, ez = startZ - sim.z;
        return Math.sqrt(ex * ex + ez * ez);
    }

    // the plan flown from this state, tracked the way it will be for real, lands
    private boolean tracksFrom(PlayerSim live, TungstenSearch.Plan p) {
        lastWork += " tracks-from";
        PlayerSim probe = live.copy();
        int jumpAt = jumpIn(p, 0);
        for (int i = 0; i < p.ticks(); i++) {
            if (SimState.of(probe).distance(p.states().get(i)) >= FALLBACK_EPS) {
                return false;
            }
            if (!Keys.step(probe, track(probe, p, i))) {
                return false;
            }
            if (probe.horizontalCollision && i < jumpAt) {
                return false; // into a wall before the jump: what the sprint does then is the camera's call (JumpPolicy.bumpsAWall)
            }
        }
        return land.reached(SimState.of(probe), JumpPolicy.STILL * 4) || land.holds(probe.x, probe.y, probe.z) && probe.onGround;
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
