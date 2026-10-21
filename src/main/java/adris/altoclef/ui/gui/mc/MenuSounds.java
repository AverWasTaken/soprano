package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

// the title screen's two sounds, both ours: gui-mock/tools/make-sounds.py synthesizes them, assets/soprano/sounds.json
// names them. forUI puts them on the interface channel, so the "Interface" slider turns them down with vanilla's button
// click. the SoundEvents are just names, client side playback never asks the registry about them
final class MenuSounds {

    private static final SoundEvent HOVER =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("soprano", "ui.hover"));
    private static final SoundEvent CLICK =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("soprano", "ui.click"));

    // major pentatonic, any run of it sounds like it meant to. the hover sample is an e5 and the dock starts 7 below,
    // on an a4, so the middle of a normal dock sits on the sample's own pitch
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19};
    private static final float HOVER_VOLUME = 0.6f;
    private static final float CLICK_VOLUME = 0.8f;
    // two notes closer than this are the mouse wobbling on a tile edge, not a sweep along the dock
    private static final long HOVER_GAP_NANOS = 30_000_000L;

    private static long lastHover;

    private MenuSounds() {
    }

    // the pitch for the step'th tile from the left. vanilla clamps pitch to 0.5..2 anyway, the scale tops out at 1.68
    static float note(int step) {
        int i = Math.max(0, Math.min(step, SCALE.length - 1));
        return (float) Math.pow(2.0, (SCALE[i] - 7) / 12.0);
    }

    static void hover(float pitch) {
        long now = System.nanoTime();
        if (now - lastHover < HOVER_GAP_NANOS) {
            return;
        }
        lastHover = now;
        play(Minecraft.getInstance().getSoundManager(), HOVER, pitch, HOVER_VOLUME);
    }

    static void click(SoundManager sounds) {
        play(sounds, CLICK, 1f, CLICK_VOLUME);
    }

    // a sound that can't play is not worth losing the menu over, so this never throws into the draw path
    private static void play(SoundManager sounds, SoundEvent sound, float pitch, float volume) {
        try {
            sounds.play(SimpleSoundInstance.forUI(sound, pitch, volume));
        } catch (Throwable t) {
            Safe.report("title sound", t);
        }
    }
}
