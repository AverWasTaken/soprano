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
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
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
            this.stillSince = 0;
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
            case DROPPING: {
                net.minecraft.client.player.LocalPlayer p = ctx.player();
                Float yaw = p == null ? null : dropDirection();
                boolean gone = p == null || p.position().distanceToSqr(dropFrom) > 16 || p.getY() < dropFrom.y - 3;
                if (gone || yaw == null || ++dropTicks > DROP_TICKS) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    this.state = State.PATH_REQUESTED;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                baritone.getLookBehavior().updateTarget(new Rotation(yaw, 30F), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
            }
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
                    RouteMemory.failedFrom(ctx.playerFeet());
                    logDebug("No way on from here yet, looking again (" + groundRetries + " of " + Baritone.settings().noPathRetries.value + ")");
                    airWaitTicks = GROUND_WAIT_TICKS;
                    tableWaitTicks = TABLE_WAIT_TICKS;
                    this.state = State.WAITING_FOR_TABLES;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if ((calcFailed || noProgress()) && dropRetries < DROP_RETRIES && dropDirection() != null) {
                    // stuck somewhere there's no way on from (the top of a ladder, a block the last jump came down short
                    // of) on a parkour course: step off, the way a player would, and get put back on the last checkpoint
                    dropRetries++;
                    groundRetries = 0;
                    dropTicks = 0;
                    dropFrom = ctx.player().position();
                    stillSince = 0;
                    logDirect("No way on from here: stepping off to be put back on the course (" + dropRetries + " of " + DROP_RETRIES + ")");
                    this.state = State.DROPPING;
                    return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                }
                if (calcFailed) {
                    tableRetries = 0;
                    airRetries = 0;
                    groundRetries = 0;
                    dropRetries = 0;
                    // a user trip keeps its own copy of the goal, so dropping it here is fine. see TripBehavior
                    baritone.getTripBehavior().onCalcFailed(this.goal);
                    onLostControl();
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (baritone.getPathingBehavior().getCurrent() != null) {
                    airRetries = 0; // (a plan came through: the retries start over)
                    groundRetries = 0;
                    if (baritone.getPathingBehavior().getCurrent().getPosition() > 2) {
                        dropRetries = 0; // (and it's getting somewhere)
                    }
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
    // how many times a goal may step off to be put back, and how long one goes on for
    private static final int DROP_RETRIES = 3, DROP_TICKS = 60;
    private int tableRetries, tableWaitTicks, airRetries, airWaitTicks, groundRetries, dropRetries, dropTicks;
    private net.minecraft.world.phys.Vec3 dropFrom = net.minecraft.world.phys.Vec3.ZERO;
    private long tableMisses;

    // which way to walk to step off into a drop, on a course that puts you back when you fall (FallFloor learned something
    // round here, or there's a remembered trail): the nearest side with nothing under it for a few blocks. null for none, or
    // not on such a course
    // a minute round the same spot (in eight blocks) without getting any nearer the goal, standing: paths that go a few blocks
    // and back (Chess (harder)'s snow under the tower, above where a fall puts you back) keep the no-path count from ever
    // getting to the step off
    private static final long NO_PROGRESS_MS = 60_000;
    private long stillSince;
    private double bestH = Double.MAX_VALUE;
    private net.minecraft.world.phys.Vec3 stillAt = net.minecraft.world.phys.Vec3.ZERO;

    private boolean noProgress() {
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        if (p == null || this.goal == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        double h = this.goal.heuristic(ctx.playerFeet());
        if (stillSince == 0 || p.position().distanceToSqr(stillAt) > 64) {
            // (somewhere else now: a jump on, a put back)
            stillSince = now;
            stillAt = p.position();
            bestH = h;
            return false;
        }
        if (h < bestH - 1) {
            bestH = h;
            stillSince = now;
            return false;
        }
        return p.onGround() && now - stillSince > NO_PROGRESS_MS;
    }

    private Float dropDirection() {
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        if (p == null || !Baritone.settings().learnFallFloor.value || !FallFloor.parkourHere(p.getX(), p.getZ())) {
            return null;
        }
        net.minecraft.core.BlockPos feet = p.blockPosition();
        for (int r = 1; r <= 2; r++) {
            for (int d = 0; d < 8; d++) {
                int dx = new int[]{1, -1, 0, 0, 1, 1, -1, -1}[d] * r, dz = new int[]{0, 0, 1, -1, 1, -1, 1, -1}[d] * r;
                boolean open = true;
                for (int dy = -1; dy >= -3 && open; dy--) {
                    open = ctx.world().getBlockState(feet.offset(dx, dy, dz)).getCollisionShape(ctx.world(), feet.offset(dx, dy, dz)).isEmpty();
                }
                for (int dy = 0; dy <= 1 && open; dy++) {
                    open = ctx.world().getBlockState(feet.offset(dx, dy, dz)).getCollisionShape(ctx.world(), feet.offset(dx, dy, dz)).isEmpty();
                }
                if (open) {
                    double wx = feet.getX() + dx + 0.5 - p.getX(), wz = feet.getZ() + dz + 0.5 - p.getZ();
                    return net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(wz, wx)) - 90F);
                }
            }
        }
        // no drop near enough: on the edge of something that puts you back (the dam's glass floor, the middle of us still
        // over the gap, so it hasn't): onto the middle of it, and that puts us back
        for (double ox : new double[]{-0.29, 0.29}) {
            for (double oz : new double[]{-0.29, 0.29}) {
                int bx = net.minecraft.util.Mth.floor(p.getX() + ox), bz = net.minecraft.util.Mth.floor(p.getZ() + oz);
                net.minecraft.core.BlockPos under = new net.minecraft.core.BlockPos(bx, feet.getY() - 1, bz);
                if (FallFloor.deadly(ctx.world().getBlockState(under).getBlock(), bx, feet.getY(), bz)) {
                    double wx = bx + 0.5 - p.getX(), wz = bz + 0.5 - p.getZ();
                    return net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(wz, wx)) - 90F);
                }
            }
        }
        return null;
    }

    @Override
    public void onLostControl() {
        this.stillSince = 0;
        this.bestH = Double.MAX_VALUE;
        this.tableRetries = 0;
        this.airRetries = 0;
        this.groundRetries = 0;
        this.dropRetries = 0;
        if (this.state == State.DROPPING) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        }
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
        WAITING_FOR_TABLES,
        // stepping off the course to be put back on it (dropDirection)
        DROPPING
    }
}
