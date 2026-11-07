package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoSettings;
import adris.altoclef.ui.gui.hud.HudObstacles;
import adris.altoclef.ui.gui.hud.PanelPlacement;
import adris.altoclef.ui.gui.hud.StatusPanel;
import adris.altoclef.ui.gui.hud.ToastPlacement;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.utils.accessor.IBossHealthOverlay;
import baritone.utils.accessor.IChatComponent;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.List;

// the status panel in the game. CommandStatusOverlay builds the rows inside guard() and hands them here, this finds
// the room (boss bars, chat, the #gamer card), keeps a kit surface at the hud's scale and draws. same shape as
// TripToastHud: the surface is built on a scheduled task, never in the hud extract, and three throws turn the panel off
// for the session without touching AltoClefBridge's hudBroken, so the #gamer card and the toast carry on. client
// thread only
public final class StatusPanelHud {

    private static final int MAX_ERRORS = 3;

    private static int errors;
    private static boolean broken;

    private static boolean building;
    private static KitSurface surface;
    private static StatusPanel panel;
    private static float surfaceScale;

    private StatusPanelHud() {
    }

    // everything the overlay does per frame runs in here, the line building included
    public static void guard(String where, Runnable r) {
        if (broken) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            fail(where, t);
        }
    }

    // every frame while altoclef is up. rows null means switched off (altoShowTaskChains), empty means nothing is
    // running (the idle pill's cue). hidden is f1 or f3: the panel keeps counting, it just doesn't draw
    public static void render(GuiGraphicsExtractor g, List<StatusPanel.Row> rows, int keepTop, String timer, boolean hidden) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (panel != null) {
                // a new world doesn't get an idle pill for the old one's last task
                panel.reset();
            }
            return;
        }
        Window win = mc.getWindow();
        int guiScale = Math.max(1, win.getGuiScale());
        float scale = scale(guiScale, AltoSettings.hudScale());
        if (surface == null || surfaceScale != scale) {
            // the old one (if any) keeps drawing until the new one is ready
            schedule(mc, scale);
            if (panel == null) {
                return;
            }
        }
        long nowMs = System.currentTimeMillis();
        panel.update(rows, keepTop, timer, nowMs);
        if (hidden || !panel.wantsAnything()) {
            return;
        }
        int winW = win.getWidth();
        int winH = win.getHeight();
        StatusPanel p = panel;
        PanelPlacement.Room room = PanelPlacement.room(winW, winH, guiScale, p.theme().pxf(StatusPanel.MARGIN), bosses(mc),
                chatTall(mc), HudObstacles.card(), HudObstacles.toast(), p.theme().pxf(StatusPanel.NARROW));
        if (p.layout(room) == StatusPanel.Mode.NONE) {
            return;
        }
        long now = System.nanoTime();
        float inv = 1f / guiScale;
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            surface.begin(g, winW, winH);
            began = true;
            surface.syncPreferences(now);
            surface.theme.update(now);
            p.draw(surface.canvas(), nowMs);
        } finally {
            try {
                if (began) {
                    surface.end();
                }
            } finally {
                g.pose().popMatrix();
            }
        }
        surface.afterFrame();
    }

    // the toast's rule (KitSurface.scaleFor times altoHudScale, quarter steps) so the two are always the same size
    static float scale(int guiScale, float hudScale) {
        return ToastPlacement.scale(KitSurface.scaleFor(guiScale), hudScale);
    }

    private static int bosses(Minecraft mc) {
        try {
            if (mc.gui.hud.getBossOverlay() instanceof IBossHealthOverlay b) {
                return b.soprano$events().size();
            }
        } catch (Throwable t) {
            // no bars is the old behaviour, worth a log line and nothing more
            Safe.report("status panel bosses", t);
        }
        return 0;
    }

    // chat closed fades each line out 200 ticks after it came in (ChatComponent's AlphaCalculator.timeBased)
    private static final int CHAT_FADE_TICKS = 200;

    // how tall chat is right now, gui units. open it's the whole focused box. closed it's only the recent lines still
    // up: reserving the whole closed box would push the panel off small windows for a chat that's mostly empty air
    private static float chatTall(Minecraft mc) {
        try {
            Options o = mc.options;
            if (o.chatVisibility().get() == ChatVisiblity.HIDDEN) {
                return 0f;
            }
            ChatComponent chat = mc.gui.hud.getChat();
            double scale = o.chatScale().get();
            if (chat.isChatFocused()) {
                return (float) (ChatComponent.getHeight(o.chatHeightFocused().get()) * scale);
            }
            if (!(chat instanceof IChatComponent c)) {
                return (float) (ChatComponent.getHeight(o.chatHeightUnfocused().get()) * scale);
            }
            int now = mc.gui.hud.getGuiTicks();
            int max = chat.getLinesPerPage();
            int lines = 0;
            // newest first, so the first one past its fade means everything after it is gone too
            for (Object l : c.soprano$trimmed()) {
                if (lines >= max || !(l instanceof GuiMessage.Line line) || now - line.addedTime() >= CHAT_FADE_TICKS) {
                    break;
                }
                lines++;
            }
            int lineH = (int) (9.0 * (o.chatLineSpacing().get() + 1.0));
            return (float) (lines * lineH * scale);
        } catch (Throwable t) {
            Safe.report("status panel chat", t);
            return 0f;
        }
    }

    private static void schedule(Minecraft mc, float scale) {
        if (building) {
            return;
        }
        building = true;
        mc.schedule(() -> {
            try {
                build(scale);
            } catch (Throwable t) {
                // counts like a draw throw: a build that always fails would otherwise reschedule every frame forever
                fail("status panel build", t);
            } finally {
                building = false;
            }
        });
    }

    private static void build(float scale) {
        KitSurface s = new KitSurface(scale, HudAtlases.get());
        StatusPanel p = new StatusPanel(s.theme);
        if (panel != null) {
            // a new hud size mid task is the same panel, not a fresh fade in or a lost idle pill
            p.adopt(panel);
        }
        HudAtlases.prewarm(p.styles());
        surface = s;
        panel = p;
        surfaceScale = scale;
    }

    private static void fail(String where, Throwable t) {
        Safe.report(where, t);
        if (++errors >= MAX_ERRORS) {
            broken = true;
            System.err.println("status panel threw " + errors + " times, turning it off");
        }
    }
}
