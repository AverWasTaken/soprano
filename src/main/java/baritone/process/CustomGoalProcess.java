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

import baritone.behavior.FallFloor;
import baritone.behavior.RouteMemory;
import baritone.pathing.movement.movements.PendingTables;
import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/**
 * As set by ExampleBaritoneControl or something idk
 *
 * @author leijurv
 */
public final class CustomGoalProcess extends BaritoneProcessHelper implements ICustomGoalProcess {

    /**
     * The current goal
     */
    private Goal goal;

    /**
     * The most recent goal. Not invalidated upon {@link #onLostControl()}
     */
    private Goal mostRecentGoal;

    /**
     * The current process state.
     *
     * @see State
     */
    private State state;

    public CustomGoalProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void setGoal(Goal goal) {
        if (!java.util.Objects.equals(goal, this.goal)) {
            FallFloor.markGoal();
            RouteMemory.newGoal(); // (somewhere to go: where we've been on the way to anywhere else isn't going back)
        }
        this.goal = goal;
        this.mostRecentGoal = goal;
        if (baritone.getElytraProcess().isActive()) {
            try {
                baritone.getElytraProcess().pathTo(goal);
            } catch (IllegalArgumentException e) {
                logDirect("Failed to update elytra goal because: " + e.getMessage(), ChatFormatting.RED);
            }
        }
        if (this.state == State.NONE) {
            this.state = State.GOAL_SET;
        }
        if (this.state == State.EXECUTING) {
            this.state = State.PATH_REQUESTED;
        }
    }

    @Override
    public void path() {
        this.state = State.PATH_REQUESTED;
    }

    @Override
    public Goal getGoal() {
        return this.goal;
    }

    @Override
    public Goal mostRecentGoal() {
        return this.mostRecentGoal;
    }

    @Override
    public boolean isActive() {
        return this.state != State.NONE;
    }

    // actually going somewhere. a bare #goal stays GOAL_SET forever while the player walks around by hand
    public boolean isPathingToGoal() {
        return this.state == State.PATH_REQUESTED || this.state == State.EXECUTING || this.state == State.WAITING_FOR_TABLES;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (this.state) {
            case GOAL_SET:
                return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            case WAITING_FOR_TABLES:
                if (airWaitTicks > 0) {
                    airWaitTicks--; // (see the mid air retry below: wait for where we land, tables or no tables)
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if (!PendingTables.waiting() || ++tableWaitTicks > TABLE_WAIT_TICKS) {
                    this.state = State.PATH_REQUESTED;
                }
                return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
            case PATH_REQUESTED:
                // return FORCE_REVALIDATE_GOAL_AND_PATH just once
                tableMisses = PendingTables.misses();
                PathingCommand ret = new PathingCommand(this.goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                this.state = State.EXECUTING;
                return ret;
            case EXECUTING:
                if (calcFailed && tableRetries < TABLE_RETRIES && PendingTables.misses() != tableMisses) {
                    // the first path of a session runs into shapes nobody has worked out yet (a neo, a ladder grab, a
                    // momentum jump): "don't know" for now, worked out in a second in the background. give it that second
                    // and ask again, instead of telling the user there's no path the first time and finding one the second
                    tableRetries++;
                    tableWaitTicks = 0;
                    logDebug("Some of the jumps here are still being worked out, trying again in a moment");
                    this.state = State.WAITING_FOR_TABLES;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if (calcFailed && airRetries < AIR_RETRIES && ctx.player() != null && !ctx.player().onGround()
                        && !ctx.player().onClimbable()) {
                    // planned from mid air (falling off a jump): nowhere to stand yet, so of course nothing. a parkour server
                    // puts us back on a checkpoint in a moment; ask again from wherever we end up
                    airRetries++;
                    airWaitTicks = AIR_WAIT_TICKS;
                    tableWaitTicks = TABLE_WAIT_TICKS;
                    this.state = State.WAITING_FOR_TABLES;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if (calcFailed && groundRetries < Baritone.settings().noPathRetries.value) {
                    // nothing from here, this time: stood still a moment, ask again (Settings.noPathRetries)
                    groundRetries++;
                    logDebug("No way on from here yet, looking again (" + groundRetries + " of " + Baritone.settings().noPathRetries.value + ")");
                    airWaitTicks = GROUND_WAIT_TICKS;
                    tableWaitTicks = TABLE_WAIT_TICKS;
                    this.state = State.WAITING_FOR_TABLES;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if (calcFailed) {
                    tableRetries = 0;
                    airRetries = 0;
                    groundRetries = 0;
                    // a user trip keeps its own copy of the goal, so dropping it here is fine. see TripBehavior
                    baritone.getTripBehavior().onCalcFailed(this.goal);
                    onLostControl();
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (baritone.getPathingBehavior().getCurrent() != null) {
                    airRetries = 0; // (a plan came through: the retries start over)
                    groundRetries = 0;
                }
                if (this.goal == null || (this.goal.isInGoal(ctx.playerFeet()) && this.goal.isInGoal(baritone.getPathingBehavior().pathStart()))) {
                    baritone.getTripBehavior().onArrived(this.goal);
                    RouteMemory.save(); // the way here, kept now (see RouteMemory)
                    onLostControl(); // we're there xd
                    if (Baritone.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                        }
                    }
                    if (Baritone.settings().notificationOnPathComplete.value) {
                        logNotification("Pathing complete", false);
                    }
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
            default:
                throw new IllegalStateException("Unexpected state " + this.state);
        }
    }

    private static final int TABLE_RETRIES = 8, TABLE_WAIT_TICKS = 600, AIR_RETRIES = 20, AIR_WAIT_TICKS = 20, GROUND_WAIT_TICKS = 40;
    private int tableRetries, tableWaitTicks, airRetries, airWaitTicks, groundRetries;
    private long tableMisses;

    @Override
    public void onLostControl() {
        this.tableRetries = 0;
        this.airRetries = 0;
        this.groundRetries = 0;
        this.state = State.NONE;
        this.goal = null;
    }

    @Override
    public String displayName0() {
        return "Custom Goal " + this.goal;
    }

    protected enum State {
        NONE,
        GOAL_SET,
        PATH_REQUESTED,
        EXECUTING,
        // a search came back empty after the neo / momentum / climb tables told it "don't know yet": waiting for them
        WAITING_FOR_TABLES
    }
}
