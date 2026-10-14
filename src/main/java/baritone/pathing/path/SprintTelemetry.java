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

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.pathing.sim.LiveSim;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.sim.WorldCollision;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// two questions about sprint, answered with lines of text (sprintTelemetry): when did it drop and who did it, and does PlayerSim
// predict what the game really did. all of it is read-only and none of it runs with the setting off.
//
// the order inside one game tick, which is what decides what "before" and "after" mean here:
//   Minecraft.tick head -> PathingBehavior.onTick -> begin() -> the executor sets keys, sprint flag and look target -> end()
//   -> LocalPlayer.aiStep (input.tick reads those keys, vanilla starts or stops the sprint, jump, travel) -> end of tick
//   -> next game tick -> begin()
// so begin() of tick N+1 sees the player exactly as aiStep N left it, nothing of tick N+1 has run yet, and end() of tick N sees
// the exact state aiStep N starts from together with the inputs it will be given. the drift check takes the snapshot in end(),
// replays aiStep N on it with the yaw moveRelative really used (LookBehavior.lastMoveYaw), and compares in begin()
public final class SprintTelemetry implements Helper {

    private static final int REPORT_EVERY = 1000;
    // a systematically wrong sim would otherwise write a line per tick
    private static final int DRIFT_LINES_PER_REPORT = 25;
    private static final double DRIFT = 0.01;

    private final Baritone baritone;
    private final IPlayerContext ctx;

    private int pathTicks, sprintTicks, drops, totalDrops, totalPathTicks;
    private int checks, mismatches, driftLogged, unsupported;

    // begin() of the tick that just ended: the sprint flag and the collision flags aiStep of the next one decides from
    private int lastTick = -1;
    private boolean lastSprint, lastHc, lastMinor;

    // end() of the tick that just ended: what the executor wanted and did
    private boolean lastPathing, lastWant, lastJump;
    private int lastIndex, lastLength;
    private String lastMovement = "", lastWhy = "", lastReleased = "";

    // the replay. the sim stands where the player did when the keys were set, the rest is the keys and the yaw they were set with
    private PlayerSim pending;
    private int pendingForward, pendingLeft, pendingMoveCount;
    private boolean pendingJump, pendingSprint;
    private float pendingViewYaw;
    // the sim cannot see LivingEntity.noJumpDelay, so it counts it for itself across ticks
    private int jumpDelay;

    public SprintTelemetry(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    // setting is off: forget everything so turning it on later does not compare against a tick from a minute ago
    public void off() {
        lastTick = -1;
        lastPathing = false;
        pending = null;
    }

    /**
     * First thing in a game tick, before anything of ours has touched the keys or the sprint flag
     */
    public void begin(boolean pathing) {
        LocalPlayer player = ctx.player();
        if (player == null) {
            off();
            return;
        }
        int tick = player.tickCount;
        if (lastTick >= 0 && tick == lastTick + 1 && lastPathing) {
            replay(player);
            if (lastSprint && !player.isSprinting()) {
                drops++;
                emit(describeDrop(player));
            }
        } else {
            pending = null;
        }
        if (pathing) {
            pathTicks++;
            if (player.isSprinting()) {
                sprintTicks++;
            }
            if (pathTicks >= REPORT_EVERY) {
                report();
            }
        }
        lastTick = tick;
        lastSprint = player.isSprinting();
        lastHc = player.horizontalCollision;
        lastMinor = player.minorHorizontalCollision;
    }

    /**
     * After the executor and everything else of ours have had their say for this tick. The executor is null when there is no path
     */
    public void end(PathExecutor executor, boolean paused) {
        LocalPlayer player = ctx.player();
        pending = null;
        lastPathing = executor != null;
        if (player == null || executor == null) {
            return;
        }
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        List<?> movements = executor.getPath().movements();
        lastIndex = executor.getPosition();
        lastLength = movements.size();
        lastMovement = lastIndex >= 0 && lastIndex < lastLength ? movements.get(lastIndex).getClass().getSimpleName() : "past the end";
        lastWant = executor.isSprinting();
        lastWhy = executor.sprintWhy();
        lastReleased = paused ? "behavior pause" : executor.releasedWhy();
        lastJump = keys.isInputForcedDown(Input.JUMP);
        if (paused || baritone.bsi == null || keys.isInputForcedDown(Input.SNEAK)) {
            return; // nothing of ours is moving the player, or it is a kind of moving the sim does not do
        }
        PlayerSim sim = LiveSim.of(player, new WorldCollision(baritone.bsi));
        if (sim == null) {
            unsupported++;
            return;
        }
        sim.noJumpDelay = jumpDelay;
        pending = sim;
        pendingForward = (keys.isInputForcedDown(Input.MOVE_FORWARD) ? 1 : 0) - (keys.isInputForcedDown(Input.MOVE_BACK) ? 1 : 0);
        pendingLeft = (keys.isInputForcedDown(Input.MOVE_LEFT) ? 1 : 0) - (keys.isInputForcedDown(Input.MOVE_RIGHT) ? 1 : 0);
        pendingJump = lastJump;
        pendingSprint = lastWant; // PathingBehavior.onPlayerSprintState hands this to vanilla as the sprint key
        pendingViewYaw = player.getYRot();
        pendingMoveCount = baritone.getLookBehavior().moveCount();
    }

    // run aiStep of the tick that just finished on the snapshot and hold the result against the player
    private void replay(LocalPlayer player) {
        PlayerSim sim = pending;
        pending = null;
        if (sim == null) {
            return;
        }
        LookBehavior look = baritone.getLookBehavior();
        if (look.moveCount() != pendingMoveCount + 1) {
            return; // moveRelative did not run exactly once, so whatever happened was not an ordinary tick
        }
        PlayerSim.Controls controls = new PlayerSim.Controls(pendingForward, pendingLeft, pendingJump, pendingSprint, look.lastMoveYaw(), pendingViewYaw);
        if (!sim.tick(controls)) {
            unsupported++;
            return;
        }
        jumpDelay = sim.noJumpDelay;
        checks++;
        double ex = player.getX() - sim.x, ey = player.getY() - sim.y, ez = player.getZ() - sim.z;
        double err = Math.sqrt(ex * ex + ey * ey + ez * ez);
        boolean flags = sim.onGround != player.onGround() || sim.sprinting != player.isSprinting()
                || sim.horizontalCollision != player.horizontalCollision || sim.minorHorizontalCollision != player.minorHorizontalCollision;
        if (err <= DRIFT && !flags) {
            return;
        }
        mismatches++;
        if (driftLogged++ < DRIFT_LINES_PER_REPORT) {
            emit(String.format(Locale.ROOT, "drift tick=%d %s err=%.4f (%.4f,%.4f,%.4f) predicted ground=%s sprint=%s hc=%s minor=%s, actual ground=%s sprint=%s hc=%s minor=%s"
                            + " keys fwd=%d left=%d jump=%s sprintKey=%s moveYaw=%.2f viewYaw=%.2f vel=(%.4f,%.4f,%.4f)",
                    player.tickCount - 1, lastMovement, err, ex, ey, ez,
                    sim.onGround, sim.sprinting, sim.horizontalCollision, sim.minorHorizontalCollision,
                    player.onGround(), player.isSprinting(), player.horizontalCollision, player.minorHorizontalCollision,
                    pendingForward, pendingLeft, pendingJump, pendingSprint, look.lastMoveYaw(), pendingViewYaw,
                    player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z));
        }
    }

    // the player was sprinting at the start of the last tick and is not now: the executor let go, or aiStep dropped it
    private String describeDrop(LocalPlayer player) {
        Vec3 pos = player.position();
        BlockPos cell = player.blockPosition();
        float forward = player.input.getMoveVector().y;
        int food = player.getFoodData().getFoodLevel();
        String cause;
        if (!lastReleased.isEmpty()) {
            cause = "keys released (" + lastReleased + ")";
        } else if (!lastWant) {
            cause = "executor said no (" + (lastWhy.isEmpty() ? "no reason given" : lastWhy) + ")";
        } else if (!(forward > 1.0E-5F)) {
            cause = "no forward impulse";
        } else if (lastHc && !lastMinor) {
            cause = "horizontal collision";
        } else if (food <= 6) {
            cause = "food";
        } else {
            cause = "something else in aiStep";
        }
        return String.format(Locale.ROOT, "drop tick=%d path=%d/%d %s cause=%s feet=(%.3f,%.3f,%.3f) cell+off=(%d,%d,%d)+(%.3f,%.3f)"
                        + " ground=%s hc=%s minor=%s prevHc=%s prevMinor=%s forward=%.2f food=%d wantSprint=%s why=\"%s\" released=\"%s\" jump=%s",
                player.tickCount - 1, lastIndex, lastLength, lastMovement, cause, pos.x, pos.y, pos.z,
                cell.getX(), cell.getY(), cell.getZ(), pos.x - cell.getX(), pos.z - cell.getZ(),
                player.onGround(), player.horizontalCollision, player.minorHorizontalCollision, lastHc, lastMinor, forward, food,
                lastWant, lastWhy, lastReleased, lastJump);
    }

    private void report() {
        totalDrops += drops;
        totalPathTicks += pathTicks;
        emit(String.format(Locale.ROOT, "%d sprint drops in %d path ticks (%d sprinting), %.2f per 1000, %d over %d so far. sim: %d ticks checked, %d off, %d unsupported%s",
                drops, pathTicks, sprintTicks, drops * 1000.0 / pathTicks, totalDrops, totalPathTicks,
                checks, mismatches, unsupported, driftLogged > DRIFT_LINES_PER_REPORT ? " (" + (driftLogged - DRIFT_LINES_PER_REPORT) + " drift lines not shown)" : ""));
        pathTicks = 0;
        sprintTicks = 0;
        drops = 0;
        checks = 0;
        mismatches = 0;
        driftLogged = 0;
        unsupported = 0;
    }

    // chat when chatDebug is on, and the game log either way so a whole walk can be grepped afterwards
    private void emit(String line) {
        String text = "[sprintTelemetry] " + line;
        logDebug(text);
        System.out.println(text);
    }
}
