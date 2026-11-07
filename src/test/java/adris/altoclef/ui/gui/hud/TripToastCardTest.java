package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import baritone.behavior.trip.GatherPlan;
import baritone.behavior.trip.Trip;
import baritone.behavior.trip.TripToast;
import org.junit.Test;

import static org.junit.Assert.*;

// the toast card without a game: what it says in each state, and that a click lands on the button it was drawn as at
// any gui scale and hud size
public class TripToastCardTest {

    private static TripToast.View ask(Trip.Ask kind, int blocks, boolean pickaxe, double saved) {
        TripToast t = new TripToast();
        t.ask(9, new GatherPlan(kind, blocks, blocks, pickaxe), saved);
        return t.view(0);
    }

    private static TripToast.View collecting(int have, int need, boolean pickaxe, TripToast.Phase phase) {
        TripToast t = new TripToast();
        t.collecting(9, pickaxe, have, need, phase);
        return t.view(0);
    }

    private static TripToast.View done(int got, boolean pickaxe) {
        TripToast t = new TripToast();
        t.done(9, pickaxe, got, 0);
        return t.view(0);
    }

    private static TripToastCard card(float scale) throws Exception {
        return new TripToastCard(new Theme(scale, new GlyphAtlas(AwtFonts.load())));
    }

    @Test
    public void wordsMatchTheMock() {
        TripToast.View stuck = ask(Trip.Ask.STUCK, 24, false, 0);
        assertEquals("Path needs blocks", TripToastCard.title(stuck));
        assertEquals("I need 24 blocks for this path.\nCollect them?", TripToastCard.body(stuck));
        assertNull(TripToastCard.meta(stuck));
        assertEquals("minecraft:dirt", TripToastCard.itemFor(stuck));

        TripToast.View faster = ask(Trip.Ask.FASTER, 24, true, 1600);
        assertEquals("Faster path found", TripToastCard.title(faster));
        assertEquals("saves 1m 20s", TripToastCard.meta(faster));
        assertEquals("It needs 24 blocks and a stone pickaxe.\nGet them first?", TripToastCard.body(faster));
        assertEquals("minecraft:stone_pickaxe", TripToastCard.itemFor(faster));
        assertEquals("It needs a stone pickaxe.\nMake one first?", TripToastCard.body(ask(Trip.Ask.FASTER, 0, true, 1600)));
        assertEquals("I need 1 block for this path.\nCollect them?", TripToastCard.body(ask(Trip.Ask.STUCK, 1, false, 0)));
    }

    @Test
    public void followUpStates() {
        TripToast.View c = collecting(12, 24, false, TripToast.Phase.BY_HAND);
        assertEquals("Collecting blocks", TripToastCard.title(c));
        assertEquals("12/24", TripToastCard.meta(c));
        assertEquals("Digging nearby, then back on the path.", TripToastCard.body(c));
        assertEquals("Mining nearby, then back on the path.", TripToastCard.body(collecting(12, 24, false, TripToast.Phase.MINING)));
        TripToast.View pick = collecting(0, 0, true, TripToast.Phase.PICKAXE);
        assertEquals("Making a stone pickaxe", TripToastCard.title(pick));
        assertNull("nothing to count", TripToastCard.meta(pick));
        assertEquals("minecraft:stone_pickaxe", TripToastCard.itemFor(pick));

        assertEquals("Got 24 blocks", TripToastCard.title(done(24, false)));
        assertEquals("Got 24 blocks and a stone pickaxe", TripToastCard.title(done(24, true)));
        assertEquals("Got a stone pickaxe", TripToastCard.title(done(0, true)));
        assertEquals("Carrying on with the path.", TripToastCard.body(done(24, false)));
    }

    @Test
    public void buttonsPerState() throws Exception {
        TripToastCard card = card(1f);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        card.show(a, 0);
        card.place(a, 2560, 1369, 16, 16);
        assertNotNull(card.bounds(TripToastCard.Hit.YES));
        assertNotNull(card.bounds(TripToastCard.Hit.NO));
        assertNull(card.bounds(TripToastCard.Hit.STOP));
        float[] yes = card.bounds(TripToastCard.Hit.YES);
        float[] no = card.bounds(TripToastCard.Hit.NO);
        assertTrue("yes is the rightmost, like the mock", yes[0] > no[0]);
        assertEquals("right edge 12 in from the card's", card.x() + card.w() - 12, yes[0] + yes[2], 0.01f);
        // the mock's sizes at scale 1
        assertEquals(380, card.w(), 0.01f);
        assertEquals(28, yes[3], 0.01f);

        TripToast.View c = collecting(3, 24, false, TripToast.Phase.BY_HAND);
        card.show(c, 1);
        card.place(c, 2560, 1369, 16, 16);
        assertNotNull(card.bounds(TripToastCard.Hit.STOP));
        assertNull(card.bounds(TripToastCard.Hit.YES));

        TripToast.View d = done(24, false);
        card.show(d, 2);
        card.place(d, 2560, 1369, 16, 16);
        assertNull(card.bounds(TripToastCard.Hit.STOP));
        assertTrue("done has no buttons, so it's shorter", card.h() < card.heightFor(a));
    }

    @Test
    public void aClickInGuiUnitsLandsOnTheButtonItWasDrawnAs() throws Exception {
        // gui scale 3 is KitSurface's 1.5, times a 1.5 hud size is 2.25
        int guiScale = 3;
        float scale = ToastPlacement.scale(1.5f, 1.5f);
        assertEquals(2.25f, scale, 0f);
        TripToastCard card = card(scale);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        card.show(a, 0);
        float[] at = ToastPlacement.place(2560, 1440, guiScale, card.width(), card.heightFor(a), card.theme().pxf(TripToastCard.MARGIN), 0, true, null);
        card.place(a, 2560, 1440, at[0], at[1]);
        for (TripToastCard.Hit which : new TripToastCard.Hit[]{TripToastCard.Hit.YES, TripToastCard.Hit.NO}) {
            float[] b = card.bounds(which);
            // what ChatScreen hands us: the middle of the button, in gui units
            double guiX = (b[0] + b[2] / 2f) / guiScale;
            double guiY = (b[1] + b[3] / 2f) / guiScale;
            assertEquals(which, card.hit(ToastPlacement.toDevice(guiX, guiScale), ToastPlacement.toDevice(guiY, guiScale)));
        }
        // the glass up by the corner is the card's but no button's
        float gx = ToastPlacement.toDevice((card.x() + 4) / guiScale, guiScale);
        float gy = ToastPlacement.toDevice((card.y() + 4) / guiScale, guiScale);
        assertNull(card.hit(gx, gy));
        assertTrue(card.contains(gx, gy));
        assertFalse(card.contains(10, 10));
    }

    @Test
    public void aRebuiltCardIsTheSameToast() throws Exception {
        TripToastCard old = card(1f);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        assertTrue(old.show(a, 0));
        TripToastCard bigger = card(1.5f);
        bigger.adopt(old);
        assertFalse("no second slide in and no second sound", bigger.show(a, 100));
        assertSame(a, bigger.view());

        // and one fading out keeps fading out where it was
        old.place(a, 1920, 1080, 16, 16);
        old.show(null, 200);
        TripToastCard again = card(1.5f);
        again.adopt(old);
        assertTrue(again.visible());
        assertEquals(old.x(), again.x(), 0f);
        assertEquals(old.y(), again.y(), 0f);
        assertEquals(old.w(), again.w(), 0f);
    }

    @Test
    public void goneMeansNoHits() throws Exception {
        TripToastCard card = card(1f);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        card.show(a, 0);
        card.place(a, 1920, 1080, 16, 16);
        float[] yes = card.bounds(TripToastCard.Hit.YES);
        card.show(null, 10);
        assertTrue("fading out", card.visible());
        assertNull(card.hit(yes[0] + 2, yes[1] + 2));
    }

    @Test
    public void aBodyPastThreeLinesSaysItWasCut() throws Exception {
        // every character 40 px wide, so the plain ask body wraps to a column of short lines
        TextMeasurer wide = new TextMeasurer() {
            public float measure(String s, TextStyle style) {
                return s.length() * 40f;
            }

            public float ascent(TextStyle style) {
                return 10f;
            }

            public float descent(TextStyle style) {
                return 3f;
            }
        };
        TripToastCard card = new TripToastCard(new Theme(1f, wide));
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        java.util.List<String> lines = card.bodyLines(a);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2), lines.get(2).endsWith("…"));
        // the two-line body that fits is left alone
        TripToastCard real = card(1f);
        java.util.List<String> fits = real.bodyLines(a);
        assertEquals(java.util.List.of("I need 24 blocks for this path.", "Collect them?"), fits);
    }

    @Test
    public void dimmedButtonsKeepTheirPlace() throws Exception {
        TripToastCard card = card(1f);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        card.show(a, 0);
        card.place(a, 1920, 1080, 16, 16);
        card.setButtonsLive(false);
        assertFalse(card.buttonsLive());
        // the layout doesn't move when they dim, and a rebuilt card keeps them dimmed
        assertNotNull(card.bounds(TripToastCard.Hit.YES));
        TripToastCard bigger = card(1.5f);
        bigger.adopt(card);
        assertFalse(bigger.buttonsLive());
    }

    @Test
    public void unboundKeyLosesItsChip() throws Exception {
        TripToastCard card = card(1f);
        TripToast.View a = ask(Trip.Ask.STUCK, 24, false, 0);
        card.show(a, 0);
        card.place(a, 1920, 1080, 16, 16);
        float withChip = card.bounds(TripToastCard.Hit.YES)[2];
        card.setKeys(null, "N");
        card.place(a, 1920, 1080, 16, 16);
        assertTrue(card.bounds(TripToastCard.Hit.YES)[2] < withChip);
        card.setKeys("Left Shift", "N");
        card.place(a, 1920, 1080, 16, 16);
        assertTrue("a long key name gets a wider chip", card.bounds(TripToastCard.Hit.YES)[2] > withChip);
    }
}
