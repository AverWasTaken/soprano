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

package baritone.pathing.movement.movements;

import baritone.behavior.RouteMemory;
import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CurvedMovement;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.sim.LiveSim;
import baritone.pathing.sim.WorldCollision;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.JumpPilot;
import baritone.pathing.tungsten.JumpPolicy;
import baritone.pathing.tungsten.Keys;
import baritone.pathing.tungsten.Landing;
import baritone.pathing.tungsten.SafeCollision;
import baritone.pathing.tungsten.SimState;
import baritone.pathing.tungsten.TungstenJumps;
import baritone.pathing.tungsten.TungstenSearch;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * A jump at any angle, found and flown with the player physics sim: see {@link TungstenJumps} for how the planner picks
 * them and {@link JumpPolicy} for the rule that flies them.
 * <p>
 * Every tick on the ground the whole jump is rolled out again from wherever the real player is, and the first tick of it is
 * pressed. So whatever the last movement handed over (a run up in some other direction, a bit of lag) the jump starts from
 * what is, not from what the planner assumed. If nothing works from there it goes to the planner's start, standing still in
 * the middle of src, and if even that doesn't work (somebody changed the blocks, a speed potion) the full Tungsten search
 * gets a go, off the game thread. In the air the rule steers, and once down it brakes onto the middle of the landing.
 */
public class MovementTungsten extends Movement implements CurvedMovement {

    // the world as the pilot sees it this tick (see SafeCollision.forTick)
    private SafeCollision liveWorld;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int SEARCH_WAIT = 100, STUCK = 40;

    private final JumpPolicy.Variant planned;
    // the planner's full search plan, for a jump the rule can't do (a ladder neo)
    private final TungstenSearch.Plan plannedSearch;
    // the planner's rollout, for drawing the arc and knowing which cells we fly through
    private final List<SimState> trace;
    // the lowest the planned flight goes (feet cell). below that, a block more, and it's a fall
    private final int lowestPlanned;
    private static final int REFUSED_IN_AIR = 3;
    // a tick of the pilot taking this long is worth a line in the debug log: the game only has 50 ms for the whole tick
    private static final long SLOW_TICK_MS = 15;

    private JumpPilot pilot;
    private CompletableFuture<TungstenSearch.Plan> searching;
    private boolean searched;
    private int waitTicks, stuckTicks;

    // the sector the jump into src comes from, for a jump that carries its speed on (TungstenJumps.Jump.carry), -1 otherwise
    private final int carry;

    private MovementTungsten(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, JumpPolicy.Variant planned, List<SimState> trace,
                             TungstenSearch.Plan searched) {
        this(baritone, src, dest, planned, trace, searched, -1);
    }

    private MovementTungsten(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, JumpPolicy.Variant planned, List<SimState> trace,
                             TungstenSearch.Plan searched, int carry) {
        super(baritone, src, dest, EMPTY);
        this.carry = carry;
        this.planned = planned;
        this.plannedSearch = searched;
        this.trace = trace;
        int low = Math.min(src.y, dest.y);
        for (SimState s : trace) {
            low = Math.min(low, Landing.feetY(s.y)); // a bounce goes down to the slime and back up
        }
        this.lowestPlanned = low;
    }

    public static MovementTungsten cost(CalculationContext context, BetterBlockPos src, int bucket) {
        TungstenJumps.Jump j = TungstenJumps.get(context, src.x, src.y, src.z, bucket);
        if (j == null) {
            // dest == src never matches anything in Path.runBackwards, so this is inert
            return new MovementTungsten(context.getBaritone(), src, src, null, List.of(), null);
        }
        return new MovementTungsten(context.getBaritone(), src, j.dest(), j.variant(), j.trace(), j.plan(), j.carry());
    }

    // a leap along the remembered way (TungstenJumps.trailLeap)
    public static MovementTungsten leap(CalculationContext context, BetterBlockPos src) {
        TungstenJumps.Jump j = TungstenJumps.trailLeap(context, src.x, src.y, src.z);
        if (j == null) {
            return new MovementTungsten(context.getBaritone(), src, src, null, List.of(), null);
        }
        return new MovementTungsten(context.getBaritone(), src, j.dest(), j.variant(), j.trace(), j.plan(), -1);
    }

    public static void leap(CalculationContext context, int x, int y, int z, MutableMoveResult res) {
        TungstenJumps.Jump j = TungstenJumps.trailLeap(context, x, y, z);
        if (j == null) {
            return;
        }
        res.x = j.dest().x;
        res.y = j.dest().y;
        res.z = j.dest().z;
        res.cost = j.cost();
    }

    public static void cost(CalculationContext context, int x, int y, int z, int bucket, MutableMoveResult res) {
        TungstenJumps.Jump j = TungstenJumps.get(context, x, y, z, bucket);
        if (j == null) {
            return;
        }
        res.x = j.dest().x;
        res.y = j.dest().y;
        res.z = j.dest().z;
        res.cost = j.cost();
    }

    @Override
    public double calculateCost(CalculationContext context) {
        if (dest.equals(src)) {
            return COST_INF;
        }
        TungstenJumps.Jump j = TungstenJumps.between(context, src, dest, carry);
        if (j == null && carry < 0) {
            j = TungstenJumps.leapBetween(context, src, dest); // (a leap along the remembered way)
        }
        return j == null ? COST_INF : j.cost();
    }

    @Override
    public void reset() {
        super.reset();
        pilot = null;
        if (searching != null) {
            searching.cancel(true);
        }
        searching = null;
        searched = false;
        waitTicks = stuckTicks = 0;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        // the whole box between src and dest, a block wider all round, from a block under the lower one to two over the higher.
        // the executor rewinds or skips the path the moment the feet are outside this set, and the flight really goes wherever
        // the pilot steers it from wherever the player started it, not only along the planner's rollout. a feet cell mid jump
        // that happens to be in an earlier movement's set would get the path rewound to that movement in mid air
        // the planner's rollout counts as well: a run up backs off the far side of src, a bounce goes down to the slime
        int minX = Math.min(src.x, dest.x), maxX = Math.max(src.x, dest.x), minZ = Math.min(src.z, dest.z), maxZ = Math.max(src.z, dest.z);
        int minY = Math.min(src.y, dest.y);
        for (SimState s : trace) {
            minX = Math.min(minX, Mth.floor(s.x));
            maxX = Math.max(maxX, Mth.floor(s.x));
            minZ = Math.min(minZ, Mth.floor(s.z));
            maxZ = Math.max(maxZ, Mth.floor(s.z));
            minY = Math.min(minY, Landing.feetY(s.y));
        }
        int lowY = minY - 1, highY = Math.max(src.y, dest.y) + 2;
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = lowY; y <= highY; y++) {
                    set.add(new BetterBlockPos(x, y, z));
                }
            }
        }
        // and the planner's rollout, which can swing outside that (a neo round a wall)
        for (SimState s : trace) {
            int fy = Landing.feetY(s.y);
            for (double ox : new double[]{-0.3, 0, 0.3}) {
                for (double oz : new double[]{-0.3, 0, 0.3}) {
                    set.add(new BetterBlockPos(Mth.floor(s.x + ox), fy, Mth.floor(s.z + oz)));
                    set.add(new BetterBlockPos(Mth.floor(s.x + ox), fy + 1, Mth.floor(s.z + oz)));
                }
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // anything on the ground before we've left it is fine to interrupt, the flight very much isn't
        return state.getStatus() != MovementStatus.RUNNING || (pilot == null || !pilot.flew()) && ctx.player().onGround();
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        LocalPlayer player = ctx.player();
        BetterBlockPos feet = ctx.playerFeet();
        BlockStateInterface bsi = ((Baritone) baritone).bsi;
        if (bsi != null) {
            liveWorld = SafeCollision.forTick(liveWorld, bsi);
        }
        // a ladder neo or any other jump onto a ladder ends hanging on it, everything else standing on dest
        Landing land = landing();
        if (feet.y < lowestPlanned - 1) {
            RouteMemory.jumped(src, dest, false);
            logDebug("tungsten jump: fell");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (pilot == null) {
            // from where in src the floor is (the middle, mostly; the top of a ladder's along one side, a pillar's rim is
            // next door)
            double[] at = liveWorld == null ? new double[]{0.5, 0.5} : TungstenJumps.standAt(liveWorld, src.x, src.y, src.z);
            pilot = new JumpPilot(land, src.x + at[0], src.z + at[1], planned);
            pilot.fallback(plannedSearch);
            pilot.flow(this::flowsOn);
        }
        PlayerSim sim = bsi == null ? null : LiveSim.of(player, liveWorld);
        if (sim == null) {
            // somewhere the sim doesn't model: a potion, sneaking, using an item, water. nothing to steer with
            if (player.onGround() && feet.equals(dest) && player.getDeltaMovement().horizontalDistance() < 0.05) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            // a refusal for a tick or two is usually something left over from the movement before (a sneak still on), it
            // goes away by itself. in the air there's no time for more than that
            if (++stuckTicks > (!player.onGround() || pilot.flew() ? REFUSED_IN_AIR : STUCK)) {
                logDebug("tungsten jump: the sim can't follow the player here (" + (bsi == null ? "no world" : LiveSim.lastRefusal) + ")");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            return state; // let go of everything for a tick, a sneak or an item use left over from the last movement ends
        }
        int driftsBefore = pilot.drifts;
        long t0 = System.nanoTime();
        JumpPilot.Decision d = pilot.tick(sim);
        long took = (System.nanoTime() - t0) / 1_000_000;
        if (took >= SLOW_TICK_MS && Baritone.settings().chatDebug.value) {
            logDebug("tungsten jump: a slow tick, " + took + " ms:" + pilot.lastWork);
        }
        if (pilot.drifts != driftsBefore && Baritone.settings().chatDebug.value) {
            logDebug("tungsten jump: off the plan, " + pilot.lastDrift);
        }
        switch (d.status()) {
            case PRESS:
                return press(state, d.keys());
            case DONE:
                if (pilot.flew()) {
                    RouteMemory.jumped(src, dest, true);
                }
                return state.setStatus(MovementStatus.SUCCESS);
            case STUCK:
                if (++stuckTicks > STUCK + SEARCH_WAIT) {
                    logDebug("tungsten jump: can't get going");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return noRuleFromHere(state, player, land);
            default:
                if (pilot.flew()) {
                    RouteMemory.jumped(src, dest, false); // (came down off the landing, and the like)
                }
                logDebug("tungsten jump: " + d.why());
                return state.setStatus(MovementStatus.UNREACHABLE);
        }
    }

    // the movement after this one in the path, told by the executor every tick (null at the end of the path)
    private IMovement next;

    public void next(IMovement next) {
        this.next = next;
    }

    private Landing landing() {
        if (MovementHelper.isClimbable(BlockStateInterface.get(ctx, dest).getBlock())) {
            return Landing.grab(dest.x, dest.y, dest.z);
        }
        if (WorldCollision.isWaterState(BlockStateInterface.get(ctx, dest))) {
            // into a water block (TungstenJumps.waterCell), the top of a column or a lone one
            return Landing.swim(dest.x, dest.y, dest.z, WorldCollision.isWaterState(BlockStateInterface.get(ctx, dest.below())));
        }
        // a bottom slab under dest: the feet come down half a block lower than the cell
        net.minecraft.world.level.block.state.BlockState floor = BlockStateInterface.get(ctx, dest.below());
        if (MovementHelper.isHalfFloor(floor)) {
            return Landing.slab(dest.x, dest.y, dest.z);
        }
        // a stair: the feet can come down on its low step, half a block into its own cell
        Landing land = TungstenJumps.stepped(floor) ? Landing.step(dest.x, dest.y, dest.z) : new Landing(dest.x, dest.y, dest.z);
        // a floor along one side of the cell (the top of a ladder), or a pillar's rim next door: come down there
        if (liveWorld != null && (MovementHelper.isClimbable(floor.getBlock()) || !TungstenJumps.stepped(floor) && !MovementHelper.canWalkOn(ctx, dest.below(), floor))) {
            double[] at = TungstenJumps.standAt(liveWorld, dest.x, dest.y, dest.z);
            if (at[0] != 0.5 || at[1] != 0.5) {
                land = land.aimed(at[0], at[1]);
            }
        }
        return land;
    }

    // down on dest and still moving: can the next movement go from right here, without the stop
    private boolean flowsOn(PlayerSim live) {
        IMovement after = next;
        if (after == null || !after.getSrc().equals(dest)) {
            return false;
        }
        if (after instanceof MovementTungsten) {
            MovementTungsten jump = (MovementTungsten) after;
            if (jump.planned == null || jump.plannedSearch != null || jump.dest.equals(jump.src)) {
                return false; // a jump only the full search does goes from standing still on the takeoff spot
            }
            // the next jump's rule, from the state we're in, with the aim as wobbly as it gets
            JumpPolicy.Rollout r = JumpPolicy.forgiving(live, jump.landing(), jump.planned);
            return r != null && r.ticks() > 0;
        }
        // anything else walks on from wherever it starts. only not if the slide we're on, let go of, would take us off the
        // block (or off the next one): coasting has to come to a stop on one of the two
        BetterBlockPos to = after.getDest();
        PlayerSim probe = live.copy();
        float yaw = Keys.yaw(to.x + 0.5 - live.x, to.z + 0.5 - live.z);
        for (int i = 0; i < 25; i++) {
            if (Math.sqrt(probe.vx * probe.vx + probe.vz * probe.vz) < 0.02) {
                return true;
            }
            if (!Keys.step(probe, Keys.release(yaw)) || !probe.onGround) {
                return false;
            }
            int fx = Mth.floor(probe.x), fz = Mth.floor(probe.z), fy = Landing.feetY(probe.y);
            boolean onSrc = fx == dest.x && fz == dest.z && fy == dest.y, onNext = fx == to.x && fz == to.z && fy == to.y;
            if (!onSrc && !onNext) {
                return false;
            }
        }
        return false;
    }

    // standing still in the middle of src (where the planner said it works from) and the rule still says no: somebody changed
    // the blocks, or a speed potion. ask the full search, off the game thread
    private MovementState noRuleFromHere(MovementState state, LocalPlayer player, Landing land) {
        if (searching == null && !searched) {
            searched = true;
            int budget = Baritone.settings().tungstenSearchBudget.value;
            // a copy of the loaded chunks, the game thread's cache isn't for sharing
            PlayerSim start = LiveSim.of(player, new SafeCollision(new BlockStateInterface(ctx, true)));
            if (start == null) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            TungstenSearch.Region region = JumpPilot.region(start, land);
            logDebug("tungsten jump: the rule has nothing from here, searching");
            searching = CompletableFuture.supplyAsync(() -> TungstenSearch.search(start, land, region, budget, () -> Thread.currentThread().isInterrupted()),
                    Baritone.getExecutor());
            waitTicks = 0;
        }
        if (searching != null) {
            if (!searching.isDone()) {
                if (++waitTicks > SEARCH_WAIT) {
                    searching.cancel(true);
                    logDebug("tungsten jump: the search is taking too long");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return state; // stand still while it thinks
            }
            TungstenSearch.Plan found = searching.isCompletedExceptionally() ? null : searching.getNow(null);
            searching = null;
            if (found == null || found.ticks() == 0) {
                logDebug("tungsten jump: nothing works from here");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            logDebug("tungsten jump: searched " + found.nodes() + " nodes for a " + found.ticks() + " tick plan");
            pilot.track(found);
            return state; // the plan starts from where we stand now, next tick
        }
        logDebug("tungsten jump: nothing works from here");
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    private MovementState press(MovementState state, PlayerSim.Controls keys) {
        state.setTarget(new MovementState.MovementTarget(new Rotation(keys.moveYaw(), ctx.playerRotations().getPitch()), false));
        if (keys.forward() > 0) {
            state.setInput(Input.MOVE_FORWARD, true);
        } else if (keys.forward() < 0) {
            state.setInput(Input.MOVE_BACK, true);
        }
        if (keys.left() > 0) {
            state.setInput(Input.MOVE_LEFT, true);
        } else if (keys.left() < 0) {
            state.setInput(Input.MOVE_RIGHT, true);
        }
        if (keys.jump()) {
            state.setInput(Input.JUMP, true);
        }
        if (keys.sprint()) {
            state.setInput(Input.SPRINT, true);
        }
        return state;
    }

    // the planner's rollout, thinned out, in block corner coordinates like path positions are
    @Override
    public Vec3[] curve() {
        if (trace.size() < 2) {
            return null;
        }
        int n = Math.min(CURVE_POINTS, trace.size());
        Vec3[] points = new Vec3[n];
        for (int i = 0; i < n; i++) {
            SimState s = trace.get((int) Math.round(i * (trace.size() - 1) / (double) (n - 1)));
            points[i] = new Vec3(s.x - 0.5, s.y, s.z - 0.5);
        }
        // floating point, and the line should meet the movements either side exactly
        points[0] = new Vec3(src.x, src.y, src.z);
        points[n - 1] = new Vec3(dest.x, dest.y, dest.z);
        return points;
    }

    private static final int CURVE_POINTS = 16;
}
