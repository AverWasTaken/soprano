package adris.altoclef.ui.gui.hud;

import org.junit.Test;

import static org.junit.Assert.*;

// the toast keeps off the subtitles, the hotbar, chat's input box and the #gamer card
public class ToastPlacementTest {

    private static final float W = 380;
    private static final float H = 119;
    private static final float M = 16;

    @Test
    public void bigWindowIsJustTheCorner() {
        float[] at = ToastPlacement.place(2560, 1369, 2, W, H, M, 0, false, null);
        assertEquals(16, at[0], 0f);
        assertEquals(16, at[1], 0f);
    }

    @Test
    public void subtitlesPushItUp() {
        float[] at = ToastPlacement.place(2560, 1369, 2, W, H, M, 3, false, null);
        // 3 rows reach 30 + 30 gui units up, plus 2, at gui scale 2, plus half the margin
        assertEquals((32 + 30) * 2 + 8, at[1], 0f);
        float[] more = ToastPlacement.place(2560, 1369, 2, W, H, M, 6, false, null);
        assertTrue(more[1] > at[1]);
    }

    @Test
    public void chatInputPushesItUp() {
        float[] at = ToastPlacement.place(2560, 1369, 2, W, H, M, 0, true, null);
        assertEquals(14 * 2 + 8, at[1], 0f);
    }

    @Test
    public void narrowWindowLiftsItOverTheHotbar() {
        // 854 wide at gui scale 2: the hotbar's right end is at 427 + 240, the toast starts at 854 - 16 - 380
        float[] at = ToastPlacement.place(854, 480, 2, W, H, M, 0, false, null);
        assertEquals(60 * 2 + 8, at[1], 0f);
        assertEquals(16, at[0], 0f);
    }

    @Test
    public void gamerCardSendsItLeft() {
        // a tall card on a short window, reaching down to the corner
        float[] card = {1600 - 8 - 304, 100, 304, 900};
        float[] at = ToastPlacement.place(1600, 1000, 2, W, H, M, 0, false, card);
        assertEquals(1600 - card[0] + 8, at[0], 0f);
        float left = 1600 - at[0] - W;
        assertFalse(ToastPlacement.overlaps(left, 1000 - at[1] - H, W, H, card));
    }

    @Test
    public void aCardUpTopIsLeftAlone() {
        float[] card = {2560 - 8 - 304, 400, 304, 400};
        float[] at = ToastPlacement.place(2560, 1369, 2, W, H, M, 0, false, card);
        assertEquals(16, at[0], 0f);
    }

    @Test
    public void scaleIsQuarterSteps() {
        assertEquals(1f, ToastPlacement.scale(1f, 1f), 0f);
        assertEquals(0.75f, ToastPlacement.scale(1f, 0.75f), 0f);
        assertEquals(1.25f, ToastPlacement.scale(1.5f, 0.8f), 0f);
        assertEquals(2.5f, ToastPlacement.scale(2f, 2f), 0f);
    }
}
