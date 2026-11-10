/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.preview.Java2dCanvas;
import org.junit.Before;
import org.junit.Test;

import java.awt.FontFormatException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the effortless radial: the ring of tiles, the hub, the two panels' controls, and pictures in build/gui-preview
public class RadialMenuTest {

    private GlyphAtlas glyphs;
    private ShapeAtlas shapes;
    private Theme theme;
    private final List<Integer> picked = new ArrayList<>();

    @Before
    public void setUp() throws IOException, FontFormatException {
        glyphs = new GlyphAtlas(AwtFonts.load());
        shapes = new ShapeAtlas();
        theme = new Theme(1f, glyphs);
    }

    // the ring as the game builds it: the real shapes with their glyphs
    private RadialMenu real() {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        for (baritone.effortless.ShapeKind k : adris.altoclef.ui.gui.mc.EffortlessRadialScreen.SHAPES) {
            entries.add(new RadialMenu.Entry(adris.altoclef.ui.gui.mc.EffortlessRadialScreen.icon(k), k.title));
        }
        RadialMenu m = new RadialMenu(theme, entries, picked::add);
        m.setBounds(0, 0, 1600, 900);
        return m;
    }

    private RadialMenu menu(int n) {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(new RadialMenu.Entry(Icon.SHAPE_CUBOID, "Shape " + i));
        }
        RadialMenu m = new RadialMenu(theme, entries, picked::add);
        m.setBounds(0, 0, 1280, 720);
        return m;
    }

    private static final java.util.function.IntConsumer NOOP = i -> {
    };

    // the panels the game shows for a cube with the hotbar mix on
    private void cubePanels(RadialMenu m, List<Integer> fills, List<String> toggled) {
        m.setPanels(List.of(
                new RadialMenu.Title("Blocks", "Used for every shape"),
                new RadialMenu.Choice("Place with", List.of("Held block", "Hotbar mix"), 1, NOOP),
                new RadialMenu.Stepper("1  Stone Bricks", "60%", 3, 0, 9, NOOP, null, false, null),
                new RadialMenu.Stepper("2  Cracked Stone Bricks", "20%", 1, 0, 9, NOOP, null, false, null),
                new RadialMenu.Stepper("3  Oak Log", "20%", 1, 0, 9, NOOP, "Turn", true, () -> toggled.add("log")),
                new RadialMenu.Stepper("4  Glass", null, 0, 0, 9, NOOP, null, false, null),
                new RadialMenu.Action("Reroll the pattern", () -> {
                }),
                new RadialMenu.Choice("Take blocks from", List.of("Inventory", "Storage", "Gather"), 0, NOOP)
        ), List.of(
                new RadialMenu.Title("Cube", "3 clicks: a corner, the far corner, then how far out"),
                new RadialMenu.Choice("Filling", List.of("Filled", "Hollow", "Skeleton"), 0, fills::add),
                new RadialMenu.Choice("Facing", List.of("Both", "Vertical", "Horizontal"), 0, NOOP),
                new RadialMenu.Choice("Plane length", List.of("Variable", "Equal"), 0, NOOP),
                new RadialMenu.Title("Clicks", "Hold a block in your offhand to match it"),
                new RadialMenu.Choice("Right click fills", List.of("Empty", "Existing", "Both"), 1, NOOP),
                new RadialMenu.Choice("Existing blocks", List.of("Any", "Offhand's", "Not offhand's"), 2, NOOP),
                new RadialMenu.Choice("Left click breaks", List.of("All", "Offhand's", "Not offhand's"), 0, NOOP),
                new RadialMenu.Choice("Containers", List.of("Break", "Leave"), 0, NOOP)
        ));
    }

    @Test
    public void theHubIsNone() {
        RadialMenu m = menu(8);
        assertEquals(RadialMenu.HUB, m.pick(m.centerX(), m.centerY()));
    }

    @Test
    public void tilesGoClockwiseFromTheTop() {
        RadialMenu m = menu(8);
        float cx = m.centerX();
        float cy = m.centerY();
        assertEquals(0, m.pick(cx, cy - 110));
        assertEquals(2, m.pick(cx + 110, cy));
        assertEquals(4, m.pick(cx, cy + 110));
        assertEquals(6, m.pick(cx - 110, cy));
        // a short flick past the hub counts
        assertEquals(2, m.pick(cx + 45, cy + 2));
        // far off the ring is nothing
        assertEquals(RadialMenu.NOTHING, m.pick(cx + 600, cy));
    }

    @Test
    public void clickingATilePicksIt() {
        RadialMenu m = menu(8);
        assertTrue(m.mouseDown(m.tileX(3), m.tileY(3), 0, 0));
        assertEquals(3, m.selected());
        assertEquals(List.of(3), picked);
    }

    @Test
    public void panelsAnswerClicksAndArePictured() throws IOException {
        RadialMenu m = real();
        List<Integer> fills = new ArrayList<>();
        List<String> toggled = new ArrayList<>();
        cubePanels(m, fills, toggled);
        m.setSelected(4);
        Java2dCanvas c = new Java2dCanvas(1600, 900, glyphs, shapes);
        c.clear(0xFF2B3A2E);
        m.mouseMove(m.tileX(5), m.tileY(5));
        m.draw(c, new Frame(0, 0, m.tileX(5), m.tileY(5)));
        c.save(Paths.get("build", "gui-preview", "effortless_radial.png"));
        // the ring stays in the middle
        assertEquals(800f, m.centerX(), 0.01f);
        // somewhere right of the ring a fill segment takes a click, and its index is the segment
        boolean hit = false;
        for (float dy = -400; dy <= 400 && !hit; dy += 3) {
            for (float dx = 0; dx <= 400 && !hit; dx += 4) {
                m.mouseDown(m.rightX() + dx, m.centerY() + dy, 0, 0);
                hit = !fills.isEmpty();
            }
        }
        assertTrue("a filling segment took a click", hit);
        assertEquals(Integer.valueOf(0), fills.get(0));
        // and left of it the Turn chip
        boolean turned = false;
        for (float dy = -400; dy <= 400 && !turned; dy += 3) {
            for (float dx = 0; dx <= 500 && !turned; dx += 4) {
                m.mouseDown(2 * m.centerX() - m.rightX() - dx, m.centerY() + dy, 0, 0);
                turned = !toggled.isEmpty();
            }
        }
        assertTrue("the Turn toggle took a click", turned);
        // clicks on a panel never pick a tile behind it
        assertTrue(picked.isEmpty());
    }

    @Test
    public void noShapeNoPanels() throws IOException {
        RadialMenu m = real();
        Java2dCanvas c = new Java2dCanvas(1600, 900, glyphs, shapes);
        c.clear(0xFF2B3A2E);
        m.draw(c, new Frame(0, 0, -1, -1));
        c.save(Paths.get("build", "gui-preview", "effortless_radial_none.png"));
        assertFalse(m.mouseDown(m.rightX() + 50, m.centerY(), 0, 0));
    }
}
