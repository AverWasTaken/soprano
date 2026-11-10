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

// the effortless radial: picking by angle, the hub, the chips, and a picture of it in build/gui-preview
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

    private RadialMenu menu(int n) {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        Icon[] icons = {Icon.SHAPE_SINGLE, Icon.SHAPE_LINE, Icon.SHAPE_WALL, Icon.SHAPE_FLOOR, Icon.SHAPE_CUBOID,
                Icon.SHAPE_STAIRS, Icon.SHAPE_ROOF, Icon.SHAPE_PYRAMID, Icon.SHAPE_SPHERE, Icon.SHAPE_CYLINDER, Icon.SHAPE_CIRCLE};
        for (int i = 0; i < n; i++) {
            entries.add(new RadialMenu.Entry(icons[i % icons.length], "Shape " + i));
        }
        RadialMenu m = new RadialMenu(theme, entries, picked::add);
        m.setBounds(0, 0, 1280, 720);
        return m;
    }

    @Test
    public void hubIsNone() {
        RadialMenu m = menu(8);
        assertEquals(RadialMenu.HUB, m.pick(m.centerX(), m.centerY()));
    }

    @Test
    public void tilesGoClockwiseFromTheTop() {
        RadialMenu m = menu(8);
        float cx = m.centerX();
        float cy = m.centerY();
        assertEquals(0, m.pick(cx, cy - 90));
        assertEquals(2, m.pick(cx + 90, cy));
        assertEquals(4, m.pick(cx, cy + 90));
        assertEquals(6, m.pick(cx - 90, cy));
        // a flick towards a tile counts even well short of it
        assertEquals(2, m.pick(cx + 45, cy + 3));
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
    public void chipsAnswerClicks() throws IOException {
        RadialMenu m = menu(11);
        List<Integer> chip = new ArrayList<>();
        m.setRows(List.of(new RadialMenu.Row("Shape", List.of("Filled", "Hollow", "Walls", "Edges"), 1, chip::add),
                new RadialMenu.Row("Right click places", List.of("Into empty", "Over anything", "Replace hand"), 0, i -> {
                })));
        m.setSelected(4);
        Java2dCanvas c = new Java2dCanvas(1280, 720, glyphs, shapes);
        c.clear(0xFF2B3A2E);
        m.mouseMove(m.tileX(5), m.tileY(5));
        m.draw(c, new Frame(0, 0, m.tileX(5), m.tileY(5)));
        c.save(Paths.get("build", "gui-preview", "effortless_radial.png"));
        // the first chip of the first row sits at the panel's left edge, past the padding
        float x = m.panelX() + theme.pxf(RadialMenu.PAD) + 4;
        float y = m.centerY();
        boolean hit = false;
        for (int dy = -200; dy <= 200 && !hit; dy += 2) {
            if (m.mouseDown(x, y + dy, 0, 0) && !chip.isEmpty()) {
                hit = true;
            }
        }
        assertTrue("a chip in the first row took a click", hit);
        assertEquals(Integer.valueOf(0), chip.get(0));
    }
}
