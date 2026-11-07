package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.hud.ToastPlacement;
import adris.altoclef.ui.gui.hud.TripToastCard;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.Theme;
import baritone.behavior.trip.GatherPlan;
import baritone.behavior.trip.Trip;
import baritone.behavior.trip.TripToast;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

// the corner toast, one png per state in build/gui-preview/toast_*.png, next to gui-mock/hud's shots. the close ups are at
// scale 2 like the mock's dsf 2 close ups, the corner ones are a whole window at scale 1 with the vanilla bits it dodges
// drawn as boxes. the asserts are there so a card that threw fails instead of saving a lie
public class TripToastPreviewTest {

    private static final long T0 = 10_000_000_000L;
    // stands in for the mock's planks room: a warm wall and a darker floor, the glass has to read on both
    private static final int WALL = 0xFF8A6A3A;
    private static final int FLOOR = 0xFF5A3E26;
    private static final int VANILLA_BOX = 0x80000000;

    private final GlyphAtlas glyphs;
    private final ShapeAtlas shapes = new ShapeAtlas();

    public TripToastPreviewTest() throws Exception {
        Anim.enabled = true;
        glyphs = new GlyphAtlas(AwtFonts.load());
    }

    private static TripToast.View ask(Trip.Ask kind, int blocks, boolean pickaxe, double saved) {
        TripToast t = new TripToast();
        t.ask(9, new GatherPlan(kind, blocks, blocks, pickaxe), saved);
        return t.view(0);
    }

    private static TripToast.View collecting(int have, int need) {
        TripToast t = new TripToast();
        t.collecting(9, false, have, need, TripToast.Phase.BY_HAND);
        return t.view(0);
    }

    private static TripToast.View done(int got) {
        return done(got, false);
    }

    private static TripToast.View done(int got, boolean pickaxe) {
        TripToast t = new TripToast();
        t.done(9, pickaxe, got, 0);
        return t.view(0);
    }

    // a close up: the card bottom right of a small canvas, like the mock's toast crops
    private Path closeUp(String name, TripToast.View v, boolean dark, TripToastCard.Hit hover) throws Exception {
        return closeUp(name, v, dark, hover, true);
    }

    private Path closeUp(String name, TripToast.View v, boolean dark, TripToastCard.Hit hover, boolean live) throws Exception {
        Theme theme = new Theme(2f, glyphs);
        theme.setDark(dark);
        TripToastCard card = new TripToastCard(theme);
        card.setButtonsLive(live);
        int winW = 1160;
        int winH = 578;
        card.show(v, T0);
        card.place(v, winW, winH, theme.pxf(TripToastCard.MARGIN), theme.pxf(TripToastCard.MARGIN));
        float mx = -100;
        float my = -100;
        if (hover != null) {
            float[] b = card.bounds(hover);
            mx = b[0] + b[2] * 0.3f;
            my = b[1] + b[3] * 0.6f;
        }
        card.mouseMove(mx, my);
        Java2dCanvas c = new Java2dCanvas(winW, winH, glyphs, shapes);
        c.setTheme(theme);
        theme.update(T0 + Anim.ms(1000));
        c.clear(WALL);
        c.fillRect(0, winH * 0.62f, winW, winH * 0.38f, FLOOR);
        draw(card, c, T0 + Anim.ms(1000), mx, my);
        return save(name, c);
    }

    // a whole window at scale 1, the way the game would place it, with the hotbar and subtitles as boxes
    private Path corner(String name, TripToast.View v, int winW, int winH, int guiScale, int subtitleRows, boolean chat) throws Exception {
        Theme theme = new Theme(ToastPlacement.scale(guiScale * 0.5f, 1f), glyphs);
        TripToastCard card = new TripToastCard(theme);
        card.show(v, T0);
        float[] at = ToastPlacement.place(winW, winH, guiScale, card.width(), card.heightFor(v), theme.pxf(TripToastCard.MARGIN),
                subtitleRows, chat, null);
        card.place(v, winW, winH, at[0], at[1]);
        Java2dCanvas c = new Java2dCanvas(winW, winH, glyphs, shapes);
        c.setTheme(theme);
        theme.update(T0 + Anim.ms(1000));
        c.clear(WALL);
        c.fillRect(0, winH * 0.62f, winW, winH * 0.38f, FLOOR);
        // hotbar 182 x 22 centred at the bottom, hearts and food above it
        float hw = 182 * guiScale;
        c.fillRect((winW - hw) / 2f, winH - 22 * guiScale, hw, 22 * guiScale, VANILLA_BOX);
        c.fillRect((winW - hw) / 2f, winH - 49 * guiScale, hw, 20 * guiScale, 0x40000000);
        for (int i = 0; i < subtitleRows; i++) {
            float rowMid = winH - (35 + i * 10) * guiScale;
            float sw = 120 * guiScale;
            c.fillRect(winW - sw - 2 * guiScale, rowMid - 5 * guiScale, sw, 10 * guiScale, 0xCC000000);
        }
        if (chat) {
            c.fillRect(2 * guiScale, winH - 14 * guiScale, winW - 4 * guiScale, 12 * guiScale, 0x80000000);
        }
        draw(card, c, T0 + Anim.ms(1000), -100, -100);
        // nothing it dodges may be under it
        float hotbarRight = winW / 2f + 91 * guiScale;
        assertTrue(name + ": on the hotbar", card.x() >= hotbarRight || card.y() + card.h() <= winH - 49 * guiScale);
        if (subtitleRows > 0) {
            assertTrue(name + ": on the subtitles", card.y() + card.h() <= winH - (30 + 10 * subtitleRows) * guiScale);
        }
        if (chat) {
            assertTrue(name + ": on chat's input box", card.y() + card.h() <= winH - 14 * guiScale);
        }
        return save(name, c);
    }

    private static void draw(TripToastCard card, Java2dCanvas c, long now, float mx, float my) {
        int before = Safe.reports();
        card.draw(c, new Frame(now, 1f / 60f, mx, my));
        if (Safe.reports() != before) {
            throw new AssertionError("the toast threw while drawing: " + Safe.lastReport());
        }
    }

    private static Path save(String name, Java2dCanvas c) throws Exception {
        Files.createDirectories(Preview.OUT);
        Path p = Preview.OUT.resolve(name + ".png");
        javax.imageio.ImageIO.write(c.image(), "png", p.toFile());
        return p;
    }

    @Test
    public void questionStates() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String t = dark ? "dark" : "light";
            assertTrue(Files.size(closeUp("toast_ask_" + t, ask(Trip.Ask.STUCK, 24, false, 0), dark, null)) > 0);
            closeUp("toast_ask_hover_yes_" + t, ask(Trip.Ask.STUCK, 24, false, 0), dark, TripToastCard.Hit.YES);
            closeUp("toast_faster_" + t, ask(Trip.Ask.FASTER, 24, true, 1600), dark, null);
            // under a screen that keeps its keys (a sign, our own gui): same card, buttons that don't look pressable
            assertTrue(Files.size(closeUp("toast_ask_dimmed_" + t, ask(Trip.Ask.STUCK, 24, false, 0), dark, null, false)) > 0);
        }
    }

    @Test
    public void followUpStates() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String t = dark ? "dark" : "light";
            closeUp("toast_collecting_" + t, collecting(12, 24), dark, null);
            closeUp("toast_done_" + t, done(24), dark, null);
        }
        closeUp("toast_collecting_hover_stop_dark", collecting(12, 24), true, TripToastCard.Hit.STOP);
        // the longest title it says, "Got 24 blocks and a stone pickaxe"
        closeUp("toast_done_pickaxe_dark", done(24, true), true, null);
    }

    @Test
    public void inTheCorner() throws Exception {
        corner("toast_corner_2560", ask(Trip.Ask.STUCK, 24, false, 0), 2560, 1369, 2, 0, false);
        corner("toast_corner_subtitles", ask(Trip.Ask.STUCK, 24, false, 0), 2560, 1369, 2, 4, false);
        corner("toast_corner_chat", ask(Trip.Ask.STUCK, 24, false, 0), 2560, 1369, 2, 0, true);
        corner("toast_corner_small", ask(Trip.Ask.STUCK, 24, false, 0), 854, 480, 2, 0, false);
        corner("toast_corner_1080_gui3", collecting(5, 24), 1920, 1080, 3, 0, false);
    }
}
