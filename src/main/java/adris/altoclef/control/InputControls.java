package adris.altoclef.control;

import baritone.api.utils.input.Input;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> _toUnpress = new ArrayDeque<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.
    private final KeyPort _keys;

    public InputControls() {
        this(new GameKeys());
    }

    public InputControls(KeyPort keys) {
        _keys = keys;
    }

    // the real keys. the only place that knows about Minecraft.getInstance()
    private static final class GameKeys implements KeyPort {
        private static KeyMapping inputToKeyBinding(Input input) {
            Options o = Minecraft.getInstance().options;
            return switch (input) {
                case MOVE_FORWARD -> o.keyUp;
                case MOVE_BACK -> o.keyDown;
                case MOVE_LEFT -> o.keyLeft;
                case MOVE_RIGHT -> o.keyRight;
                case CLICK_LEFT -> o.keyAttack;
                case CLICK_RIGHT -> o.keyUse;
                case JUMP -> o.keyJump;
                case SNEAK -> o.keyShift;
                case SPRINT -> o.keySprint;
                default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
            };
        }

        @Override
        public boolean isDown(Input input) {
            return inputToKeyBinding(input).isDown();
        }

        @Override
        public void setDown(Input input, boolean down) {
            inputToKeyBinding(input).setDown(down);
        }

        @Override
        public void click(Input input) {
            KeyMapping.click(inputToKeyBinding(input).getDefaultKey());
        }

        @Override
        public boolean aimedAtEntity() {
            return Minecraft.getInstance().hitResult instanceof EntityHitResult;
        }
    }

    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        _keys.setDown(input, true);
        // Also necessary to ensure the game registers the input as "pressed"
        _keys.click(input);
        _toUnpress.add(input);
        _waitForRelease.add(input);
    }

    public void hold(Input input) {
        // the only way a block click turns into a swing at a mob. tryPress is left alone, the dragon task wants its hits
        if (input == Input.CLICK_LEFT && !ClickGuard.allowLeftHold(_keys.aimedAtEntity())) {
            _keys.setDown(input, false);
            return;
        }
        if (!_keys.isDown(input)) {
            _keys.click(input);
        }
        _keys.setDown(input, true);
    }

    public void release(Input input) {
        _keys.setDown(input, false);
    }

    public boolean isHeldDown(Input input) {
        return _keys.isDown(input);
    }

    public void forceLook(float yaw, float pitch) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.setYRot(yaw);
            Minecraft.getInstance().player.setXRot(pitch);
        }
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!_toUnpress.isEmpty()) {
            _keys.setDown(_toUnpress.remove(), false);
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}
