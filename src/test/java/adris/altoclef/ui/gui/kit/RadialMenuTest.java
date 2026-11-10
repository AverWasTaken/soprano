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

    // the radial as the game builds it: the real shapes, each with its 3D picture and group
    private RadialMenu real() {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        for (baritone.effortless.ShapeKind k : adris.altoclef.ui.gui.mc.EffortlessRadialScreen.SHAPES) {
            entries.add(new RadialMenu.Entry(Icon.SHAPE_CUBOID, k.title, adris.altoclef.ui.gui.mc.EffortlessRadialScreen.picture(k, 46),
                    adris.altoclef.ui.gui.mc.EffortlessRadialScreen.group(k)));
        }
        RadialMenu m = new RadialMenu(theme, entries, picked::add);
        m.setBounds(0, 0, 1280, 720);
        return m;
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
    public void theMiddleIsNothingAndTheTopIsNone() {
        RadialMenu m = menu(7);
        assertEquals(RadialMenu.NOTHING, m.pick(m.centerX(), m.centerY()));
        assertEquals(RadialMenu.HUB, m.pick(m.centerX(), m.centerY() - 100));
    }

    @Test
    public void wedgesGoClockwiseAfterNone() {
        // 7 shapes and none: 8 wedges of 45 degrees, none at the top
        RadialMenu m = menu(7);
        float cx = m.centerX();
        float cy = m.centerY();
        assertEquals(1, m.pick(cx + 100, cy));
        assertEquals(3, m.pick(cx, cy + 100));
        assertEquals(5, m.pick(cx - 100, cy));
        // a short flick past the dead zone counts
        assertEquals(1, m.pick(cx + 40, cy + 2));
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
        RadialMenu m = real();
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
        // somewhere in the panel the first row's first chip takes a click
        boolean hit = false;
        for (int dy = -300; dy <= 300 && !hit; dy += 3) {
            for (int dx = 0; dx <= 400 && !hit; dx += 4) {
                if (m.mouseDown(m.panelX() + dx, m.centerY() + dy, 0, 0) && !chip.isEmpty()) {
                    hit = true;
                }
            }
        }
        assertTrue("a chip in the first row took a click", hit);
        assertEquals(Integer.valueOf(0), chip.get(0));
    }

    @Test
    public void mixRowsWithToggles() throws IOException {
        RadialMenu m = real();
        List<String> toggled = new ArrayList<>();
        m.setRows(List.of(new RadialMenu.Row("Shape", List.of("Filled", "Outline"), 0, i -> {
                }),
                new RadialMenu.Row("With", List.of("Hand", "Offhand", "Hotbar mix"), 2, i -> {
                }),
                RadialMenu.Row.heading("Hotbar mix"),
                new RadialMenu.Row("1  Stone Bricks  60%", List.of("Off", "1", "2", "3", "4", "5"), 3, i -> {
                }, null, false, null),
                new RadialMenu.Row("2  Cracked Stone Bri\u2026  20%", List.of("Off", "1", "2", "3", "4", "5"), 1, i -> {
                }, null, false, null),
                new RadialMenu.Row("3  Oak Log  20%", List.of("Off", "1", "2", "3", "4", "5"), 1, i -> {
                }, "Turn", true, () -> toggled.add("log")),
                new RadialMenu.Row("Pattern", List.of("Reroll"), -1, i -> {
                })));
        m.setSelected(3);
        Java2dCanvas c = new Java2dCanvas(1280, 720, glyphs, shapes);
        c.clear(0xFF2B3A2E);
        m.draw(c, new Frame(0, 0, -1, -1));
        c.save(Paths.get("build", "gui-preview", "effortless_radial_mix.png"));
        boolean hit = false;
        for (int dy = -300; dy <= 300 && !hit; dy += 3) {
            for (int dx = 0; dx <= 600 && !hit; dx += 4) {
                m.mouseDown(m.panelX() + dx, m.centerY() + dy, 0, 0);
                hit = !toggled.isEmpty();
            }
        }
        assertTrue("the Turn toggle took a click", hit);
    }
}
