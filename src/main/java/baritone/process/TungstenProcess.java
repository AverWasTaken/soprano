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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.sim.LiveSim;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.Landing;
import baritone.pathing.tungsten.SafeCollision;
import baritone.pathing.tungsten.SimState;
import baritone.pathing.tungsten.TungstenSearch;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code #tungsten} command: Tungsten the way Tungsten does it. No blocks, no grid, no planner: one search over the keys
 * themselves, tick by tick through the player physics sim, from where you stand to standing in the block you named, and then
 * those keys played back. For the short, weird stuff (a neo round a wall, three jumps across a few posts, a jump onto
 * something two away at an angle) rather than getting across the map, which is what {@code #goto} is for.
 * <p>
 * Played back closed loop: every tick the player is checked against where the plan said they'd be, and when they part
 * (the aim jitters, a server nudges you) it searches again from where they are, briefly on the spot in the air, properly off
 * the game thread on the ground.
 */
public final class TungstenProcess extends BaritoneProcessHelper {

    // the world as the pilot sees it this tick (see SafeCollision.forTick)
    private SafeCollision liveWorld;

    private enum Phase { IDLE, SEARCHING, PLAYING }

    // a plan is followed while the real player is within this of where it said (SimState.distance)
    private static final double PLAN_EPS = 0.06;
    private static final long AIR_SEARCH_BUDGET = 30_000;
    private static final int MAX_SEARCHES = 8;

    private Phase phase = Phase.IDLE;
    private Landing target;
    private CompletableFuture<TungstenSearch.Plan> searching;
    private TungstenSearch.Plan plan;
    private int planAt, searches;
    private long started;

    public TungstenProcess(Baritone baritone) {
        super(baritone);
    }

    // stand in this block: feet in it, on the ground, still
    public void go(BetterBlockPos feet) {
        onLostControl();
        target = new Landing(feet.x, feet.y, feet.z);
        searches = 0;
        if (search()) {
            logDirect("Tungsten: searching for a way to " + feet.x + " " + feet.y + " " + feet.z);
        }
    }

    public List<SimState> plannedStates() {
        TungstenSearch.Plan p = plan;
        return p == null ? List.of() : p.states();
    }

    @Override
    public boolean isActive() {
        return phase != Phase.IDLE && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        keys.clearAllKeys();
        if (phase == Phase.SEARCHING) {
            if (!searching.isDone()) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            TungstenSearch.Plan found = searching.isCompletedExceptionally() || searching.isCancelled() ? null : searching.getNow(null);
            searching = null;
            if (found == null) {
                logDirect("Tungsten: no way there found (tungstenSearchBudget is " + Baritone.settings().tungstenSearchBudget.value + " sim ticks)");
                stop();
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            logDebug("Tungsten: " + found.ticks() + " tick plan, " + found.nodes() + " nodes, " + found.simTicks() + " sim ticks, "
                    + (System.currentTimeMillis() - started) + " ms");
            plan = found;
            planAt = 0;
            phase = Phase.PLAYING;
        }
        LocalPlayer player = ctx.player();
        BlockStateInterface bsi = baritone.bsi;
        PlayerSim sim = bsi == null ? null : LiveSim.of(player, liveWorld = SafeCollision.forTick(liveWorld, bsi));
        if (sim == null) {
            logDirect("Tungsten: the sim can't follow the player here (water, a ladder, a potion?), stopping");
            stop();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        SimState now = SimState.of(sim);
        if (planAt >= plan.ticks()) {
            if (player.onGround() && target.holds(now.x, now.y, now.z) && now.horizontalSpeed() < 0.05) {
                logDirect("Tungsten: there");
                stop();
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            return again(sim);
        }
        if (now.distance(plan.states().get(planAt)) >= PLAN_EPS) {
            if (player.onGround()) {
                return again(sim);
            }
            // mid air: a quick search from right here, or carry on with the old keys and see where we come down
            TungstenSearch.Plan rescue = TungstenSearch.search(sim, target, region(sim), AIR_SEARCH_BUDGET, null);
            if (rescue != null && rescue.ticks() > 0) {
                plan = rescue;
                planAt = 0;
            }
        }
        press(plan.keys().get(planAt++));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // off the plan on the ground, or at its end somewhere that isn't the target: search again from here
    private PathingCommand again(PlayerSim sim) {
        if (++searches > MAX_SEARCHES) {
            logDirect("Tungsten: lost the plan too many times, stopping");
            stop();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (sim.horizontalCollision || Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz) > 0.003) {
            // let go and let the slide finish first, a search from a moving start is a search from where we won't be
            baritone.getInputOverrideHandler().clearAllKeys();
            ctx.player().setSprinting(false);
            searches--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        logDebug("Tungsten: off the plan, searching again");
        search();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean search() {
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) {
            return false;
        }
        // a copy of the loaded chunks, the game thread's cache isn't for sharing
        PlayerSim start = LiveSim.of(player, new SafeCollision(new BlockStateInterface(ctx, true)));
        if (start == null) {
            logDirect("Tungsten: the sim can't start from here (water, a ladder, sneaking, a potion?)");
            stop();
            return false;
        }
        Landing land = target;
        TungstenSearch.Region region = region(start);
        long budget = Baritone.settings().tungstenSearchBudget.value;
        started = System.currentTimeMillis();
        searching = CompletableFuture.supplyAsync(() -> TungstenSearch.search(start, land, region, budget, () -> Thread.currentThread().isInterrupted()),
                Baritone.getExecutor());
        plan = null;
        phase = Phase.SEARCHING;
        return true;
    }

    private TungstenSearch.Region region(PlayerSim start) {
        return TungstenSearch.Region.around(start.x, start.z, target.centerX(), target.centerZ(), Math.min(start.y, target.y()) - 6, 6);
    }

    private void press(PlayerSim.Controls c) {
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        baritone.getLookBehavior().updateTarget(new Rotation(c.moveYaw(), ctx.playerRotations().getPitch()), false);
        keys.setInputForceState(Input.MOVE_FORWARD, c.forward() > 0);
        keys.setInputForceState(Input.MOVE_BACK, c.forward() < 0);
        keys.setInputForceState(Input.MOVE_LEFT, c.left() > 0);
        keys.setInputForceState(Input.MOVE_RIGHT, c.left() < 0);
        keys.setInputForceState(Input.JUMP, c.jump());
        keys.setInputForceState(Input.SPRINT, c.sprint());
        if (!c.sprint()) {
            ctx.player().setSprinting(false); // Keys.step plays it this way, see there
        }
    }

    private void stop() {
        if (searching != null) {
            searching.cancel(true);
        }
        searching = null;
        plan = null;
        phase = Phase.IDLE;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public void onLostControl() {
        stop();
    }

    @Override
    public String displayName0() {
        return "Tungsten " + (target == null ? "" : target.x() + " " + target.y() + " " + target.z());
    }
}
