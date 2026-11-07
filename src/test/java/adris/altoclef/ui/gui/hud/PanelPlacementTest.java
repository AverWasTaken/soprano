package adris.altoclef.ui.gui.hud;

import org.junit.Test;

import static org.junit.Assert.*;

// device px everywhere, the vanilla bits in gui units times the gui scale
public class PanelPlacementTest {

    // chat at its default closed height (90 gui), times chat scale 1
    private static final float CHAT = 90f;

    @Test
    public void bossBarsCountLikeVanilla() {
        // 240 tall gui: bars at 12, 31, 50, 69, then 88 is past a third (80)
        assertEquals(0, PanelPlacement.bossBars(240, 0));
        assertEquals(1, PanelPlacement.bossBars(240, 1));
        assertEquals(4, PanelPlacement.bossBars(240, 4));
        assertEquals(4, PanelPlacement.bossBars(240, 9));
        // a short window still gets the first one
        assertEquals(1, PanelPlacement.bossBars(30, 3));
    }

    @Test
    public void bigWindowJustTheCorner() {
        PanelPlacement.Room r = PanelPlacement.room(2560, 1369, 2, 16, 0, CHAT, null, null, 160);
        assertEquals(16, r.x(), 0);
        assertEquals(16, r.y(), 0);
        // stops above chat: (684.5 - 40 - 90) x 2 - 8
        assertEquals((1369 / 2f - 40 - 90) * 2 - 8 - 16, r.h(), 0.01);
    }

    // the dragon's bar on a big window: 182 wide in the middle, nowhere near the corner, nothing changes
    @Test
    public void endOnABigWindowLeavesItAlone() {
        PanelPlacement.Room none = PanelPlacement.room(2560, 1369, 2, 16, 0, CHAT, null, null, 340);
        PanelPlacement.Room boss = PanelPlacement.room(2560, 1369, 2, 16, 1, CHAT, null, null, 340);
        assertEquals(none.y(), boss.y(), 0);
        assertTrue(boss.x() + Math.min(boss.w(), 340) < (1280 - 91) * 2);
    }

    // 854x480 at gui 2: the bar starts at (213.5 - 91) x 2 = 245. with only 160 asked for it narrows and stays up top,
    // with the panel's real ask (its max width, 340) it goes under the bar: 17 x 2 + 8
    @Test
    public void smallWindowNarrowsOrGoesUnder() {
        PanelPlacement.Room r = PanelPlacement.room(854, 480, 2, 16, 1, CHAT, null, null, 160);
        assertEquals(16, r.y(), 0);
        assertEquals(245 - 8 - 16, r.w(), 0.01);
        PanelPlacement.Room u = PanelPlacement.room(854, 480, 2, 16, 1, CHAT, null, null, 340);
        assertEquals(17 * 2 + 8, u.y(), 0.01);
        assertEquals(854 - 32, u.w(), 0.01);
    }

    // same window at gui 3: barely 130 px beside it, so under it instead. last bar bottom 12 + 5 = 17 gui
    @Test
    public void tinyRoomGoesUnderTheBars() {
        PanelPlacement.Room r = PanelPlacement.room(854, 480, 3, 24, 1, CHAT, null, null, 240);
        assertEquals(17 * 3 + 12, r.y(), 0.01);
        assertEquals(854 - 48, r.w(), 0.01);
    }

    @Test
    public void openChatTakesTheRoom() {
        PanelPlacement.Room closed = PanelPlacement.room(854, 480, 2, 16, 0, CHAT, null, null, 160);
        PanelPlacement.Room open = PanelPlacement.room(854, 480, 2, 16, 0, 180, null, null, 160);
        assertTrue(open.h() < closed.h());
        // (240 - 40 - 180) x 2 - 8 - 16: not even the pill fits, so nothing draws
        assertEquals(16, open.h(), 0.01);
    }

    // the trip toast on a small window with a big hud: the panel stops above it, that leaves more than stopping left of it
    @Test
    public void tripToastCutsTheBottom() {
        float[] toast = {854 - 20 - 475, 227, 475, 160};
        PanelPlacement.Room r = PanelPlacement.room(854, 480, 2, 20, 0, 0, null, toast, 200);
        assertEquals(227 - 10 - 20, r.h(), 0.01);
        assertEquals(854 - 40, r.w(), 0.01);
        // a toast up high leaves little above it, so the panel stops left of it instead
        float[] high = {500, 40, 334, 300};
        PanelPlacement.Room s = PanelPlacement.room(854, 480, 2, 20, 0, 0, null, high, 200);
        assertEquals(500 - 10 - 20, s.w(), 0.01);
        // nowhere near: nothing changes
        float[] far = {2000, 1300, 475, 60};
        assertEquals(PanelPlacement.room(2560, 1369, 2, 16, 0, 27, null, null, 160),
                PanelPlacement.room(2560, 1369, 2, 16, 0, 27, null, far, 160));
    }

    // the #gamer card on the right edge only narrows the panel when it comes down that far
    @Test
    public void gamerCardNarrowsWhenItReaches() {
        float[] card = {854 - 312, 60, 304, 300};
        PanelPlacement.Room r = PanelPlacement.room(854, 480, 2, 16, 0, CHAT, card, null, 160);
        assertEquals(card[0] - 8 - 16, r.w(), 0.01);
        float[] low = {854 - 312, 400, 304, 60};
        assertEquals(854 - 32, PanelPlacement.room(854, 480, 2, 16, 0, CHAT, low, null, 160).w(), 0.01);
    }
}
