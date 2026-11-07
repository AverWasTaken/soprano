package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the status panel's own rules: the tree lines never touch an icon or the words, the count parse, the idle pill's
// clock, the fold and when it gives up on the tree for the compact one. a fake measurer (half the font size a char) so
// none of this needs the real font
public class StatusPanelTest {

    static final TextMeasurer FAKE = new TextMeasurer() {
        @Override
        public float measure(String s, TextStyle style) {
            return s.length() * style.sizePx() * 0.5f;
        }

        @Override
        public float ascent(TextStyle style) {
            return style.sizePx() * 0.8f;
        }

        @Override
        public float descent(TextStyle style) {
            return style.sizePx() * 0.2f;
        }
    };

    private static final PanelPlacement.Room BIG = new PanelPlacement.Room(16, 16, 2000, 1000);

    @Before
    public void anim() {
        Anim.enabled = true;
    }

    static StatusPanel panel(float scale) {
        return new StatusPanel(new Theme(scale, FAKE));
    }

    static List<StatusPanel.Row> chain(int depth, String leafState) {
        List<StatusPanel.Row> rows = new ArrayList<>();
        for (int i = 0; i <= depth; i++) {
            StatusPanel.Role role = depth == 0 ? StatusPanel.Role.ONLY : i == 0 ? StatusPanel.Role.ROOT : i == depth ? StatusPanel.Role.LEAF : StatusPanel.Role.MID;
            rows.add(new StatusPanel.Row("Task " + i, i == depth ? leafState : "", role, Icon.GET));
        }
        return rows;
    }

    // every scale the hud can land on (quarter steps, 0.5 to 2.5) and a few depths. the line starts at least 3 px under
    // the parent icon, stops at least 3 px before the child icon, runs under the parent icon and bends at the child's
    // middle. the words start after the child icon, so not touching the icon means not touching the words
    @Test
    public void treeLinesKeepOffIconsAndText() {
        for (float scale = 0.5f; scale <= 2.5f; scale += 0.25f) {
            StatusPanel p = panel(scale);
            int rowH = Math.round(p.theme().pxf(StatusPanel.ROW));
            int iconS = Math.round(p.theme().pxf(StatusPanel.ICON));
            int gap = Math.round(p.theme().pxf(StatusPanel.ICON_GAP));
            for (int d = 1; d <= 5; d++) {
                int left = 13;
                int rowTop = 40 + d * rowH;
                StatusPanel.Elbow e = p.elbowAt(left, rowTop, d);
                int parentX = left + p.indent(d - 1);
                int childX = left + p.indent(d);
                int iy = rowTop + (rowH - iconS) / 2;
                int parentBottom = iy - rowH + iconS;
                String at = "scale " + scale + " depth " + d + ": " + e;
                assertTrue(at + " starts too close under the parent icon", e.top() - parentBottom >= 3);
                assertTrue(at + " ends too close to the child icon", childX - e.right() >= 3);
                assertTrue(at + " isn't under the parent icon", e.x() > parentX && e.x() + e.t() - 1 < parentX + iconS - 1);
                assertTrue(at + " doesn't bend at the child icon's middle", e.y() - e.t() + 1 > iy && e.y() < iy + iconS);
                assertTrue(at + " thicker than its round", e.t() < e.r());
                assertTrue(at + " elbow above the start", e.bend() >= e.top());
                // the ring's last column is x + r - 1, it has to stay clear of the icon as well
                assertTrue(at + " elbow into the child icon", childX - (e.x() + e.r() - 1) >= 3);
                assertTrue(at + " line into the words", e.right() < childX + iconS + gap);
                assertTrue(at + " no room for the round", e.r() >= 1);
            }
        }
    }

    // the mock's numbers at 1x: line 4 under the parent icon, 4 before the child, elbow 5, bend at the icon middle (13)
    @Test
    public void treeLinesMatchTheMockAtOneX() {
        StatusPanel p = panel(1f);
        StatusPanel.Elbow e = p.elbowAt(0, 26, 1);
        // parent icon 0..15 at y 5..20 (bottom 21), child icon at x 22 y 31..46
        assertEquals(7, e.x());
        assertEquals(21 + 4, e.top());
        assertEquals(26 + 13, e.y());
        assertEquals(22 - 4, e.right());
        assertEquals(5, e.r());
        assertEquals(1, e.t());
        // double size: 2 thick, centred on the 32 px icon's middle (columns 15 and 16)
        StatusPanel.Elbow two = panel(2f).elbowAt(0, 52, 1);
        assertEquals(2, two.t());
        assertEquals(15, two.x());
        assertEquals(42 + 8, two.top());
        assertEquals(44 - 8, two.right());
    }

    @Test
    public void progressReadsTheFirstCount() {
        assertEquals(0.5f, StatusPanel.progress("12/24"), 1e-6);
        assertEquals(0.5f, StatusPanel.progress("12 / 24"), 1e-6);
        assertEquals(3f / 7f, StatusPanel.progress("rods (3/7)"), 1e-6);
        assertEquals(4f / 9f, StatusPanel.progress("Digging, 4/9 dug"), 1e-6);
        assertEquals(1f, StatusPanel.progress("30/24"), 1e-6);
        assertEquals(0f, StatusPanel.progress("0/5"), 1e-6);
        assertEquals(-1f, StatusPanel.progress(""), 1e-6);
        assertEquals(-1f, StatusPanel.progress(null), 1e-6);
        assertEquals(-1f, StatusPanel.progress("Walking to the stash"), 1e-6);
        assertEquals(-1f, StatusPanel.progress("5/0"), 1e-6);
        // a number too big for an int doesn't throw, the match just starts further in
        assertEquals(1f, StatusPanel.progress("99999999999999999999/3"), 1e-6);
    }

    @Test
    public void progressBarOnlyUnderACount() {
        StatusPanel p = panel(1f);
        p.update(chain(2, "12/24"), 1, null, 0);
        assertEquals(StatusPanel.Mode.TREE, p.layout(BIG));
        assertEquals(0.5f, p.progressShown(), 1e-6);
        int withBar = p.h();
        p.update(chain(2, "Walking"), 1, null, 10);
        p.layout(BIG);
        assertEquals(-1f, p.progressShown(), 1e-6);
        assertEquals(withBar - p.barTall(), p.h());
    }

    @Test
    public void idlePillForThreeSecondsAfterTheLastTask() {
        StatusPanel p = panel(1f);
        // never had a task: no pill
        p.update(List.of(), 1, null, 0);
        assertFalse(p.wantsAnything());

        p.update(chain(1, ""), 1, null, 1000);
        assertTrue(p.wantsTree());
        p.update(List.of(), 1, null, 2000);
        assertTrue("the old rows hang on through the grace", p.wantsTree());
        long end = 2000 + StatusPanel.GRACE_MS;
        p.update(List.of(), 1, null, end);
        assertTrue(p.wantsIdle());
        assertEquals(StatusPanel.Mode.IDLE, p.layout(BIG));
        assertEquals(1f, p.alpha(end + StatusPanel.IDLE_MS - 1), 1e-6);
        p.update(List.of(), 1, null, end + StatusPanel.IDLE_MS - 1);
        assertTrue("still up just before 3 s", p.wantsIdle());
        long fade = Theme.ANIM_FAST;
        p.update(List.of(), 1, null, end + StatusPanel.IDLE_MS + fade);
        assertFalse("gone after 3 s and the fade", p.wantsAnything());
        // and it stays gone
        p.update(List.of(), 1, null, end + StatusPanel.IDLE_MS + fade + 5000);
        assertFalse(p.wantsAnything());
    }

    // one empty tick between two tasks: no pill, no fresh fade in, the rows just carry on
    @Test
    public void aOneTickGapIsNotTheEnd() {
        StatusPanel p = panel(1f);
        p.update(chain(1, ""), 1, "00:42", 0);
        p.layout(BIG);
        assertEquals(1f, p.alpha(1000), 1e-6);
        int w = p.w();
        p.update(List.of(), 1, null, 1000);
        assertTrue(p.wantsTree());
        assertFalse(p.wantsIdle());
        p.layout(BIG);
        assertEquals("the timer stays up through the gap", "00:42", p.laid().get(0).meta());
        assertEquals("and the width with it", w, p.w());
        p.update(chain(2, ""), 1, null, 1050);
        assertTrue(p.wantsTree());
        p.layout(BIG);
        assertEquals("no second fade in", 1f, p.alpha(1060), 1e-6);
    }

    @Test
    public void aNewTaskCutsThePillShort() {
        StatusPanel p = panel(1f);
        p.update(chain(1, ""), 1, null, 0);
        p.update(List.of(), 1, null, 100);
        p.update(List.of(), 1, null, 100 + StatusPanel.GRACE_MS);
        assertTrue(p.wantsIdle());
        p.update(chain(1, ""), 1, null, 500);
        assertTrue(p.wantsTree());
        assertFalse(p.wantsIdle());
        // and the next end gets its own full 3 s
        p.update(List.of(), 1, null, 10_000);
        p.update(List.of(), 1, null, 10_000 + StatusPanel.GRACE_MS);
        p.update(List.of(), 1, null, 10_000 + StatusPanel.GRACE_MS + StatusPanel.IDLE_MS - 1);
        assertTrue(p.wantsIdle());
    }

    @Test
    public void switchedOffForgetsThePill() {
        StatusPanel p = panel(1f);
        p.update(chain(1, ""), 1, null, 0);
        p.update(null, 1, null, 100);
        assertFalse(p.wantsAnything());
        p.update(List.of(), 1, null, 200);
        p.update(List.of(), 1, null, 200 + StatusPanel.GRACE_MS);
        assertFalse("no pill for a task that ended while it was off", p.wantsIdle());
    }

    @Test
    public void reducedMotionPillStillEnds() {
        Anim.enabled = false;
        StatusPanel p = panel(1f);
        p.update(chain(1, ""), 1, null, 0);
        long start = 100 + StatusPanel.GRACE_MS;
        p.update(List.of(), 1, null, 100);
        p.update(List.of(), 1, null, start);
        p.layout(BIG);
        assertEquals(1f, p.alpha(start + StatusPanel.IDLE_MS - 1), 1e-6);
        assertEquals(0f, p.alpha(start + StatusPanel.IDLE_MS), 1e-6);
        p.update(List.of(), 1, null, start + StatusPanel.IDLE_MS);
        assertFalse(p.wantsAnything());
    }

    @Test
    public void foldKeepsTheTopAndTheLastFew() {
        List<StatusPanel.Row> rows = chain(8, "");
        List<StatusPanel.Row> f = StatusPanel.fold(rows, 2, 6);
        assertEquals(6, f.size());
        assertEquals("Task 0", f.get(0).name());
        assertEquals("Task 1", f.get(1).name());
        assertEquals(StatusPanel.Role.MORE, f.get(2).role());
        assertEquals("4 more tasks", f.get(2).name());
        assertEquals("Task 8", f.get(5).name());
        assertSame(rows, StatusPanel.fold(rows, 2, 9));
        // the fold line takes a row itself, so it always hides at least two
        assertEquals("2 more tasks", StatusPanel.fold(chain(5, ""), 1, 5).get(1).name());
    }

    @Test
    public void deepChainsFoldInTheTree() {
        StatusPanel p = panel(1f);
        p.update(chain(9, ""), 2, null, 0);
        assertEquals(StatusPanel.Mode.TREE, p.layout(BIG));
        assertEquals(StatusPanel.MAX_ROWS, p.laid().size());
    }

    @Test
    public void widthStaysBetweenTheMockLimits() {
        for (float scale : new float[]{0.75f, 1f, 2f}) {
            StatusPanel p = panel(scale);
            p.update(chain(1, ""), 1, null, 0);
            p.layout(BIG);
            assertEquals("short names still get the min width", Math.round(scale * StatusPanel.MIN_W), p.w());
            List<StatusPanel.Row> rows = new ArrayList<>(chain(2, "12/24"));
            rows.set(1, new StatusPanel.Row("Collecting cobblestone and dirt for the bridge over the ravine", "", StatusPanel.Role.MID, Icon.GET));
            p.update(rows, 1, null, 10);
            p.layout(BIG);
            assertEquals("a long name stops at the max width", Math.round(scale * StatusPanel.MAX_W), p.w());
            String cut = p.laid().get(1).name();
            assertTrue(cut, cut.endsWith("…"));
            assertEquals("the count survives a long name", "12/24", p.laid().get(2).meta());
        }
    }

    @Test
    public void aLongNameKeepsAShortCountBesideIt() {
        StatusPanel p = panel(1f);
        List<StatusPanel.Row> rows = List.of(
                new StatusPanel.Row("Root", "", StatusPanel.Role.ROOT, Icon.GOTO),
                new StatusPanel.Row("Mining the longest name anybody ever gave a task in this mod", "12/24", StatusPanel.Role.LEAF, Icon.PICKAXE));
        p.update(rows, 1, null, 0);
        p.layout(BIG);
        StatusPanel.Laid leaf = p.laid().get(1);
        assertEquals("12/24", leaf.meta());
        assertTrue(leaf.name().endsWith("…"));
    }

    @Test
    public void shortRoomGoesCompactThenAway() {
        StatusPanel p = panel(1f);
        p.update(chain(2, "12/24"), 1, null, 0);
        assertEquals(StatusPanel.Mode.TREE, p.layout(new PanelPlacement.Room(16, 16, 600, 400)));
        assertEquals(StatusPanel.Mode.COMPACT, p.layout(new PanelPlacement.Room(16, 16, 600, 60)));
        assertEquals("Task 2", p.laid().get(0).name());
        assertEquals("12/24", p.laid().get(0).meta());
        assertEquals("Task 0", p.sub());
        assertTrue(p.h() <= 60);
        assertEquals(StatusPanel.Mode.NONE, p.layout(new PanelPlacement.Room(16, 16, 600, 30)));
        // squeezed narrow (beside the boss bars on a small window): compact, not a tree of dots
        assertEquals(StatusPanel.Mode.COMPACT, p.layout(new PanelPlacement.Room(16, 16, 140, 400)));
        assertTrue(p.w() <= 140);
        assertEquals(StatusPanel.Mode.NONE, p.layout(new PanelPlacement.Room(16, 16, 100, 400)));
    }

    @Test
    public void timerGoesOnTheTopRow() {
        StatusPanel p = panel(1f);
        p.update(chain(2, "12/24"), 1, "01:05", 0);
        p.layout(BIG);
        assertEquals("01:05", p.laid().get(0).meta());
        assertEquals("12/24", p.laid().get(2).meta());
        // compact: under the leaf, right of what it's for
        p.layout(new PanelPlacement.Room(16, 16, 600, 60));
        assertEquals(StatusPanel.Mode.COMPACT, p.mode());
        assertEquals("12/24", p.laid().get(0).meta());
    }
}
