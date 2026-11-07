package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.hud.TripToastCard;
import baritone.altoclef.AltoClefBridge;
import baritone.behavior.trip.GatherPlan;
import baritone.behavior.trip.Trip;
import baritone.behavior.trip.TripToast;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

// the game side of the toast that runs without a game: which presses are ours, what an answer runs, the rebind, and
// that a toast that throws turns itself off without touching altoclef's hud
public class TripToastHudTest {

    private static TripToast.View asking() {
        TripToast t = new TripToast();
        t.ask(4, new GatherPlan(Trip.Ask.STUCK, 20, 24, false), 0);
        return t.view(0);
    }

    private static TripToast.View collecting() {
        TripToast t = new TripToast();
        t.collecting(4, false, 3, 24, TripToast.Phase.BY_HAND);
        return t.view(0);
    }

    private static TripToast.View done() {
        TripToast t = new TripToast();
        t.done(4, false, 24, 0);
        return t.view(0);
    }

    @Test
    public void keysOnlyWhileAQuestionWaits() {
        int press = InputConstants.PRESS;
        assertEquals(TripToastCard.Hit.YES, TripToastHud.keyHit(asking(), true, press, true, false));
        assertEquals(TripToastCard.Hit.NO, TripToastHud.keyHit(asking(), true, press, false, true));
        assertNull("some other key", TripToastHud.keyHit(asking(), true, press, false, false));
        assertEquals("N stops a gather", TripToastCard.Hit.STOP, TripToastHud.keyHit(collecting(), true, press, false, true));
        assertNull("Y means nothing while collecting", TripToastHud.keyHit(collecting(), true, press, true, false));
        assertNull("nothing to answer", TripToastHud.keyHit(done(), true, press, true, true));
        assertNull("no toast", TripToastHud.keyHit(null, true, press, true, true));
    }

    @Test
    public void notWhenNotReadyNorOnRepeatOrRelease() {
        // ready is false until this toast got drawn (and with the hud hidden), so a press then is just a press
        assertNull(TripToastHud.keyHit(asking(), false, InputConstants.PRESS, true, false));
        assertNull(TripToastHud.keyHit(asking(), true, InputConstants.REPEAT, true, false));
        assertNull(TripToastHud.keyHit(asking(), true, 0, true, false));
    }

    @Test
    public void whichScreensLetTheKeysAnswer() {
        // nothing open: the head of keyPress answers, the screen hook never runs
        assertTrue(TripToastHud.keysAnswer(TripToastHud.Cover.NONE, false, false));
        assertFalse(TripToastHud.keysAnswer(TripToastHud.Cover.NONE, true, false));
        // the inventory and the pause menu: only once the screen let the key go by, never at the head of keyPress
        for (TripToastHud.Cover c : new TripToastHud.Cover[]{TripToastHud.Cover.CONTAINER, TripToastHud.Cover.PAUSE}) {
            assertTrue(c.name(), TripToastHud.keysAnswer(c, true, false));
            assertFalse(c + " at the head, before the screen saw it", TripToastHud.keysAnswer(c, false, false));
            assertFalse(c + " with a text box focused", TripToastHud.keysAnswer(c, true, true));
        }
        // chat, signs, books, our own gui, anybody's screen: the y is a letter there
        for (TripToastHud.Cover c : new TripToastHud.Cover[]{TripToastHud.Cover.CHAT, TripToastHud.Cover.OTHER}) {
            assertFalse(c.name(), TripToastHud.keysAnswer(c, true, false));
            assertFalse(c.name(), TripToastHud.keysAnswer(c, false, false));
        }
        assertEquals(TripToastHud.Cover.NONE, TripToastHud.cover(null));
    }

    @Test
    public void buttonsDimOnlyWhereNothingAnswers() {
        assertTrue(TripToastHud.buttonsLive(TripToastHud.Cover.NONE, false));
        assertTrue("clicks work with chat open", TripToastHud.buttonsLive(TripToastHud.Cover.CHAT, false));
        assertTrue("the keys work under the inventory", TripToastHud.buttonsLive(TripToastHud.Cover.CONTAINER, false));
        assertFalse("not while the creative search has them", TripToastHud.buttonsLive(TripToastHud.Cover.CONTAINER, true));
        assertTrue(TripToastHud.buttonsLive(TripToastHud.Cover.PAUSE, false));
        assertFalse(TripToastHud.buttonsLive(TripToastHud.Cover.OTHER, false));
        // only the pause menu blurs, so only it gets the toast drawn over it
        assertTrue(TripToastHud.drawsOverScreen(TripToastHud.Cover.PAUSE));
        for (TripToastHud.Cover c : new TripToastHud.Cover[]{TripToastHud.Cover.NONE, TripToastHud.Cover.CHAT,
                TripToastHud.Cover.CONTAINER, TripToastHud.Cover.OTHER}) {
            assertFalse(c.name(), TripToastHud.drawsOverScreen(c));
        }
    }

    @Test
    public void answersRunTheGatherCommand() {
        assertEquals("gather yes 17", TripToastHud.command(TripToastCard.Hit.YES, 17));
        assertEquals("gather no 17", TripToastHud.command(TripToastCard.Hit.NO, 17));
        assertEquals("gather stop 17", TripToastHud.command(TripToastCard.Hit.STOP, 17));
    }

    @Test
    public void rebindingIsRespected() {
        KeyMapping[] keys = SopranoKeys.all();
        Assume.assumeTrue("key mappings need the game's input classes", keys.length == 2);
        KeyMapping yes = keys[0];
        KeyMapping no = keys[1];
        assertSame("built once", yes, SopranoKeys.all()[0]);
        assertTrue(SopranoKeys.isYes(new KeyEvent(InputConstants.KEY_Y, 0, 0)));
        assertTrue(SopranoKeys.isNo(new KeyEvent(InputConstants.KEY_N, 0, 0)));
        assertFalse(SopranoKeys.isYes(new KeyEvent(InputConstants.KEY_N, 0, 0)));
        try {
            yes.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_B));
            assertFalse("the old key is just a key now", SopranoKeys.isYes(new KeyEvent(InputConstants.KEY_Y, 0, 0)));
            assertTrue(SopranoKeys.isYes(new KeyEvent(InputConstants.KEY_B, 0, 0)));
            yes.setKey(InputConstants.UNKNOWN);
            assertFalse("unbound answers nothing", SopranoKeys.isYes(new KeyEvent(InputConstants.KEY_Y, 0, 0)));
            assertNull("and gets no chip", SopranoKeys.label(true));
        } finally {
            yes.setKey(yes.getDefaultKey());
            no.setKey(no.getDefaultKey());
        }
        assertEquals("Soprano", SopranoKeys.name("key.category.soprano.soprano"));
        assertNull(SopranoKeys.name("key.jump"));
    }

    @Test
    public void aThrowingToastTurnsItselfOffAndLeavesAltoclefsHudAlone() throws Exception {
        Field broken = TripToastHud.class.getDeclaredField("broken");
        Field errors = TripToastHud.class.getDeclaredField("errors");
        broken.setAccessible(true);
        errors.setAccessible(true);
        Field hudBroken = AltoClefBridge.class.getDeclaredField("hudBroken");
        hudBroken.setAccessible(true);
        boolean altoBefore = hudBroken.getBoolean(null);
        broken.setBoolean(null, false);
        errors.setInt(null, 0);
        try {
            // no game here, so the real draw falls over too. it may not escape into the hud extract either way
            TripToastHud.render(null, 0);
            broken.setBoolean(null, false);
            errors.setInt(null, 0);
            int[] ran = {0};
            for (int i = 0; i < 5; i++) {
                TripToastHud.guard("test", () -> {
                    ran[0]++;
                    throw new IllegalStateException("boom");
                });
            }
            assertEquals("three strikes and it stops trying", 3, ran[0]);
            assertTrue(broken.getBoolean(null));
            assertEquals("altoclef's hud is none of its business", altoBefore, hudBroken.getBoolean(null));
            // and once off, the real thing is a no-op too
            TripToastHud.render(null, 0);
        } finally {
            broken.setBoolean(null, false);
            errors.setInt(null, 0);
        }
    }
}
