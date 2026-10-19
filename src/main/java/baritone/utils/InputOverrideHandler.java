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

package baritone.utils;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import net.minecraft.client.player.KeyboardInput;

import baritone.api.pathing.movement.IMovement;
import baritone.api.process.IBaritoneProcess;
import baritone.pathing.path.PathExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
        this.blockPlaceHelper.setWant(pos -> baritone.getBuilderProcess().placeAt(pos.getX(), pos.getY(), pos.getZ(), baritone.bsi.get0(pos)));
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            // this is the only place a click turns into a real one, so every process and movement gets muzzled at once
            setInputForceState(Input.CLICK_LEFT, false);
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        if (isInputForcedDown(Input.CLICK_RIGHT) && baritone.getInventoryBehavior().handWaitingOnSwap()) {
            // the block we selected is still on its way to the hotbar and the click would hit the fallback in BlockPlaceHelper
            // (useItem), so whatever is in that slot gets used instead. the movement asks again next tick, the swap has landed by then
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        // only while building, everything else has its own trace lines and these would drown them
        String who = baritone.getBuilderProcess().isActive() && (isInputForcedDown(Input.CLICK_LEFT) || isInputForcedDown(Input.CLICK_RIGHT)) ? clicker() : null;
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT), who);
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT), who);

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    // whose click this is for the trace: the builder's own, else the movement running, else whoever has the wheel
    private String clicker() {
        if (baritone.getBuilderProcess().clickedByBuilder()) {
            return "builder";
        }
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec != null) {
            List<IMovement> moves = exec.getPath().movements();
            int at = exec.getPosition();
            if (at >= 0 && at < moves.size()) {
                return moves.get(at).getClass().getSimpleName();
            }
        }
        return baritone.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName).orElse("nobody");
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }

    public BlockPlaceHelper getBlockPlaceHelper() {
        return blockPlaceHelper;
    }
}
