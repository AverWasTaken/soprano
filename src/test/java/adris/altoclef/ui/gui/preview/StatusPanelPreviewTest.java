package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.hud.PanelPlacement;
import adris.altoclef.ui.gui.hud.StatusPanel;
import adris.altoclef.ui.gui.hud.StatusRows;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the status panel, one png per state in build/gui-preview/status_*.png. the close ups are 520 x 220 design px at scale 2,
// the same crop and size as gui-mock/hud's dsf 2 status close ups (1040 x 440), so they lay side by side. the window
// ones are scale 1 with the vanilla bits it dodges drawn as boxes
public class StatusPanelPreviewTest {

    private static final long T0 = 100_000L;
    // the mock's planks room in the dark shots, its pale grove in the light ones
    private static final int DARK_BG = 0xFF4A3121;
    private static final int DARK_BG2 = 0xFF3A2618;
    private static final int LIGHT_BG = 0xFFD9D6E2;
    private static final int LIGHT_BG2 = 0xFFC8C5D3;
    private static final int VANILLA_BOX = 0x80000000;

    private final GlyphAtlas glyphs;
    private final ShapeAtlas shapes = new ShapeAtlas();

    public StatusPanelPreviewTest() throws Exception {
        Anim.enabled = true;
        glyphs = new GlyphAtlas(AwtFonts.load());
    }

    // the mock's chain: goto > get > pickaxe > pickaxe, depth picks how far down it goes
    private static List<StatusRows.TaskLine> chain(int depth, boolean longName) {
        List<StatusRows.TaskLine> all = new ArrayList<>(List.of(
                new StatusRows.TaskLine("Getting blocks for the path", "", "", "", false),
                new StatusRows.TaskLine(longName ? "Collecting cobblestone and dirt for the bridge over the ravine" : "Mine and collect", "", "", "", false),
                new StatusRows.TaskLine("Mining dirt", "", "", "", false),
                new StatusRows.TaskLine("Breaking dirt", "", "", "", false)));
        List<StatusRows.TaskLine> out = new ArrayList<>(all.subList(0, depth + 1));
        StatusRows.TaskLine leaf = out.get(depth);
        out.set(depth, new StatusRows.TaskLine(leaf.hudName(), "12/24", "", "", false));
        return out;
    }

    private static List<StatusPanel.Row> rows(List<StatusRows.TaskLine> lines) {
        return StatusRows.build(null, null, lines, false);
    }

    private StatusPanel panel(float scale, boolean dark) {
        Theme theme = new Theme(scale, glyphs);
        theme.setDark(dark);
        return new StatusPanel(theme);
    }

    private Java2dCanvas canvas(int w, int h, StatusPanel p, boolean dark) {
        Java2dCanvas c = new Java2dCanvas(w, h, glyphs, shapes);
        c.setTheme(p.theme());
        p.theme().update(T0 * 1_000_000L);
        c.clear(dark ? DARK_BG : LIGHT_BG);
        // a lighter and a darker band, so the glass has something to sit on that isn't flat
        c.fillRect(0, h * 0.55f, w, h * 0.45f, dark ? DARK_BG2 : LIGHT_BG2);
        return c;
    }

    // the mock's close up: 520 x 220 design px at scale 2, panel at 16, 16
    private Path closeUp(String name, List<StatusPanel.Row> rows, boolean dark, float roomH, String timer, StatusPanel.Mode expect) throws Exception {
        StatusPanel p = panel(2f, dark);
        p.update(rows, 1, timer, T0);
        return shoot(name, p, dark, roomH, expect, T0 + 1000);
    }

    private Path shoot(String name, StatusPanel p, boolean dark, float roomH, StatusPanel.Mode expect, long at) throws Exception {
        int w = 1040;
        int h = 440;
        Java2dCanvas c = canvas(w, h, p, dark);
        float m = p.theme().pxf(StatusPanel.MARGIN);
        StatusPanel.Mode mode = p.layout(new PanelPlacement.Room(m, m, w - 2 * m, roomH < 0 ? h - 2 * m : roomH));
        assertEquals(name, expect, mode);
        draw(p, c, at);
        return save(name, c);
    }

    private static void draw(StatusPanel p, Java2dCanvas c, long at) {
        int before = Safe.reports();
        p.draw(c, at);
        if (Safe.reports() != before) {
            throw new AssertionError("the panel threw while drawing: " + Safe.lastReport());
        }
    }

    private static Path save(String name, Java2dCanvas c) throws Exception {
        Files.createDirectories(Preview.OUT);
        Path p = Preview.OUT.resolve(name + ".png");
        javax.imageio.ImageIO.write(c.image(), "png", p.toFile());
        return p;
    }

    @Test
    public void treeDepths() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String t = dark ? "dark" : "light";
            for (int d = 1; d <= 3; d++) {
                assertTrue(Files.size(closeUp("status_tree_depth" + d + "_" + t, rows(chain(d, false)), dark, -1, null, StatusPanel.Mode.TREE)) > 0);
            }
            closeUp("status_tree_long_name_" + t, rows(chain(3, true)), dark, -1, null, StatusPanel.Mode.TREE);
            closeUp("status_tree_timer_" + t, rows(chain(2, false)), dark, -1, "04:12", StatusPanel.Mode.TREE);
        }
    }

    @Test
    public void compactAndIdle() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String t = dark ? "dark" : "light";
            // a room too short for the tree, like a small window with chat under it
            closeUp("status_compact_" + t, rows(chain(2, false)), dark, 120, null, StatusPanel.Mode.COMPACT);
            StatusPanel p = panel(2f, dark);
            p.update(rows(chain(2, false)), 1, null, T0);
            p.update(List.of(), 1, null, T0 + 50);
            p.update(List.of(), 1, null, T0 + 50 + StatusPanel.GRACE_MS);
            shoot("status_idle_" + t, p, dark, -1, StatusPanel.Mode.IDLE, T0 + 1000);
        }
    }

    @Test
    public void deepChainFolds() throws Exception {
        List<StatusRows.TaskLine> lines = new ArrayList<>();
        lines.add(new StatusRows.TaskLine("Getting a Diamond Pickaxe", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Getting an Iron Pickaxe", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Smelting 3 Iron Ingots", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Getting fuel", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Collecting 8 Coal", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Mine and collect", "", "", "", false));
        lines.add(new StatusRows.TaskLine("Walking to the coal", "3/8", "", "", false));
        List<StatusPanel.Row> r = StatusRows.build("Beating the game", "Beating the game", lines, false);
        StatusPanel p = panel(2f, true);
        p.update(r, 2, "12:40", T0);
        shoot("status_deep_fold_dark", p, true, -1, StatusPanel.Mode.TREE, T0 + 1000);
    }

    // whole windows at scale 1 (gui 2) with the boss bar, chat and the #gamer card as boxes. the asserts are the
    // "never on top of it" rules
    @Test
    public void inTheWindow() throws Exception {
        window("status_window_2560_end", 2560, 1369, 2, 1, false, false);
        window("status_window_854_end", 854, 480, 2, 1, false, false);
        window("status_window_854_gui3_end", 854, 480, 3, 1, false, false);
        window("status_window_854", 854, 480, 2, 0, false, false);
        window("status_window_1080_gui4", 1920, 1080, 4, 0, false, false);
        // chat open on a small window takes all the room, the panel steps aside (draws nothing) until it closes
        window("status_window_854_chat_open", 854, 480, 2, 0, true, false);
        window("status_window_854_gamer", 854, 480, 2, 0, false, true);
    }

    private void window(String name, int winW, int winH, int gs, int bosses, boolean chatOpen, boolean gamerCard) throws Exception {
        float scale = gs * 0.5f;
        StatusPanel p = panel(scale, true);
        p.update(rows(chain(3, false)), 1, null, T0);
        Java2dCanvas c = canvas(winW, winH, p, true);
        // open: the whole focused box. closed: three recent lines still up (9 gui each)
        float chatTall = chatOpen ? 180 : 27;
        float[] card = null;
        if (gamerCard) {
            float cw = 152 * gs;
            float ch = 200 * gs;
            card = new float[]{winW - 4 * gs - cw, (winH - ch) / 2f, cw, ch};
            c.fillRect(card[0], card[1], card[2], card[3], VANILLA_BOX);
        }
        if (bosses > 0) {
            // the dragon's bar: 182 x 5 centred at y 12, its name above it
            float bx = (winW / (float) gs / 2f - 91) * gs;
            c.fillRect(bx, 12 * gs, 182 * gs, 5 * gs, 0xFFB03AD0);
            c.fillRect(winW / 2f - 35 * gs, 3 * gs, 70 * gs, 8 * gs, VANILLA_BOX);
        }
        // chat: 320 wide, its lines up to chatTall above 40 from the bottom
        float chatTop = winH - (40 + chatTall) * gs;
        c.fillRect(0, chatTop, Math.min(winW, 324 * gs), chatTall * gs, 0x40000000);
        float m = p.theme().pxf(StatusPanel.MARGIN);
        PanelPlacement.Room room = PanelPlacement.room(winW, winH, gs, m, bosses, chatTall, card, null, p.theme().pxf(StatusPanel.NARROW));
        StatusPanel.Mode mode = p.layout(room);
        if (mode != StatusPanel.Mode.NONE) {
            draw(p, c, T0 + 1000);
            assertTrue(name + ": into chat", p.y() + p.h() <= chatTop);
            if (bosses > 0) {
                float bx = (winW / (float) gs / 2f - 91) * gs;
                boolean beside = p.x() + p.w() <= bx;
                boolean under = p.y() >= 17 * gs;
                assertTrue(name + ": on the boss bar", beside || under);
            }
            if (card != null) {
                assertTrue(name + ": on the gamer card", p.x() + p.w() <= card[0] || p.y() + p.h() <= card[1]);
            }
        }
        save(name, c);
    }
}
