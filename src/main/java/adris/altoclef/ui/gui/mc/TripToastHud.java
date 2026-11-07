package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoSettings;
import adris.altoclef.ui.gui.hud.HudObstacles;
import adris.altoclef.ui.gui.hud.ToastPlacement;
import adris.altoclef.ui.gui.hud.TripToastCard;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.behavior.trip.TripBehavior;
import baritone.behavior.trip.TripToast;
import baritone.utils.accessor.ISubtitleOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

// the corner toast in the game: drawn at the end of the hud extract (MixinAltoClefGui) with its own guard, answered with
// Y / N (the keyboard mixin, through GuiHotkey) or a click while chat is open (MixinChatScreenToast). it is not part of
// altoclef's hud on purpose: that one only draws while the task runner is active and switches itself off for good after
// a throw, and a trip question comes up when neither is true. client thread only
public final class TripToastHud {

    // three throws and the toast stays off for the session. the chat line still asks, so nothing is lost
    private static final int MAX_ERRORS = 3;

    private static int errors;
    private static boolean broken;

    // the atlases come from HudAtlases (shared with the status panel), built on a scheduled task, never in the hud extract
    private static boolean building;
    private static KitSurface surface;
    private static TripToastCard card;
    private static float surfaceScale;

    private static final Anim right = new Anim(0f);
    private static final Anim bottom = new Anim(0f);
    private static long lastNanos;
    private static long labelsAt;
    // answered on this toast already, a second press before the next tick moves the view along would answer twice
    private static int answeredId = -1;

    private TripToastHud() {
    }

    // top of the hud extract, before altoclef's hud draws (the #gamer card says where it is in there)
    public static void beginFrame() {
        HudObstacles.begin();
    }

    // ----- drawing

    // how many subtitle rows are up. the list is last frame's when subtitles draw deferred (a screen over the game), close
    // enough, the toast glides anyway
    public static int subtitleRows(Object overlay) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.options.showSubtitles().get() || !(overlay instanceof ISubtitleOverlay subs)) {
                return 0;
            }
            return subs.soprano$audible().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void render(GuiGraphicsExtractor g, int subtitleRows) {
        guard("trip toast render", () -> draw(g, subtitleRows));
    }

    // nothing gets out of here into the hud extract, and nothing in here touches AltoClefBridge's hudBroken
    static void guard(String where, Runnable r) {
        if (broken) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            fail(where, t);
        }
    }

    private static void draw(GuiGraphicsExtractor g, int subtitleRows) {
        Minecraft mc = Minecraft.getInstance();
        TripToast.View view = currentView();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
            view = null;
        }
        if (card == null && view == null) {
            return;
        }
        Window win = mc.getWindow();
        int guiScale = Math.max(1, win.getGuiScale());
        float scale = ToastPlacement.scale(KitSurface.scaleFor(guiScale), AltoSettings.hudScale());
        if (surface == null || surfaceScale != scale) {
            // the old one (if any) keeps drawing until the new one is ready
            schedule(mc, scale);
            if (surface == null) {
                return;
            }
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        // a rebind only happens in the controls screen, twice a second is plenty to notice it and saves two
        // translated strings a frame
        if (labelsAt == 0 || now - labelsAt > 500_000_000L) {
            labelsAt = now;
            card.setKeys(SopranoKeys.label(true), SopranoKeys.label(false));
        }
        boolean fresh = card.show(view, now);
        if (!card.visible()) {
            return;
        }
        if (fresh && view.kind() != TripToast.Kind.COLLECTING) {
            // collecting comes straight after the click that said yes, a whoosh on top of the click is one sound too many
            playIn(mc);
        }
        int winW = win.getWidth();
        int winH = win.getHeight();
        TripToast.View shape = view != null ? view : card.view();
        if (view != null) {
            float[] at = ToastPlacement.place(winW, winH, guiScale, card.width(), card.heightFor(view), card.theme().pxf(TripToastCard.MARGIN),
                    subtitleRows, chatOpen(mc), HudObstacles.card());
            glide(right, at[0], now, fresh);
            glide(bottom, at[1], now, fresh);
        }
        if (shape != null) {
            card.place(shape, winW, winH, right.value(now), bottom.value(now));
        }
        float mx = KitSurface.pointerX();
        float my = KitSurface.pointerY();
        // the pointer only means something with chat open, the rest of the time it's the crosshair's leftover
        if (chatOpen(mc)) {
            card.mouseMove(mx, my);
        } else {
            card.clearHover();
        }
        float inv = 1f / guiScale;
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            surface.begin(g, winW, winH);
            began = true;
            surface.syncPreferences(now);
            surface.theme.update(now);
            card.draw(surface.canvas(), new Frame(now, dt, mx, my));
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

    // a fresh toast lands where it belongs, after that it slides when the subtitles or chat push it about
    private static void glide(Anim a, float target, long now, boolean snap) {
        if (snap) {
            a.snap(target);
        } else if (a.target() != target) {
            a.set(target, now, Theme.ANIM);
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
                fail("trip toast build", t);
            } finally {
                building = false;
            }
        });
    }

    private static void build(float scale) {
        KitSurface s = new KitSurface(scale, HudAtlases.get());
        TripToastCard c = new TripToastCard(s.theme);
        if (card != null) {
            // a resize or a new hud size mid question is the same toast, not a new one sliding in with a second whoosh
            c.adopt(card);
        }
        HudAtlases.prewarm(c.styles());
        surface = s;
        card = c;
        surfaceScale = scale;
    }

    private static void playIn(Minecraft mc) {
        try {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1f, 1f));
        } catch (Throwable t) {
            Safe.report("trip toast sound", t);
        }
    }

    private static boolean chatOpen(Minecraft mc) {
        return mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen;
    }

    // ----- input

    // GuiHotkey.onKey asks this first. true means it was ours: answered, and nobody else sees the press
    public static boolean onKey(KeyEvent event, int action) {
        if (broken) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            TripToast.View view = currentView();
            // on screen means this very toast got drawn, not the last one still fading out
            boolean ready = mc.gui.screen() == null && mc.player != null && !mc.gui.hud.isHidden() && card != null
                    && view != null && card.view() != null && card.view().id() == view.id();
            TripToastCard.Hit hit = keyHit(view, ready, action, SopranoKeys.isYes(event), SopranoKeys.isNo(event));
            if (hit == null) {
                return false;
            }
            answer(view, hit);
            return true;
        } catch (Throwable t) {
            fail("trip toast key", t);
            return false;
        }
    }

    // only a press, only while it's asking (or collecting, for Stop), only with nothing open over the game, and only
    // once the toast is actually on screen. anything else is the key doing whatever it does normally
    static TripToastCard.Hit keyHit(TripToast.View view, boolean ready, int action, boolean yes, boolean no) {
        if (view == null || !ready || action != InputConstants.PRESS || view.id() == answeredId) {
            return null;
        }
        if (view.asking()) {
            return yes ? TripToastCard.Hit.YES : no ? TripToastCard.Hit.NO : null;
        }
        if (view.collecting()) {
            return no ? TripToastCard.Hit.STOP : null;
        }
        return null;
    }

    // MixinChatScreenToast. the event is in gui units, the card in framebuffer px
    public static boolean onChatClick(MouseButtonEvent event) {
        if (broken || card == null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        try {
            int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
            float px = ToastPlacement.toDevice(event.x(), guiScale);
            float py = ToastPlacement.toDevice(event.y(), guiScale);
            TripToast.View view = currentView();
            if (view == null || card.view() == null || view.id() != card.view().id()) {
                return false;
            }
            TripToastCard.Hit hit = card.hit(px, py);
            if (hit != null && view.id() != answeredId) {
                answer(view, hit);
                return true;
            }
            // a click on the card's glass is still ours, it shouldn't fall through to a chat link underneath
            return card.contains(px, py);
        } catch (Throwable t) {
            fail("trip toast click", t);
            return false;
        }
    }

    // the same command the chat line's [Yes] and [No] run, through the same command manager
    static String command(TripToastCard.Hit hit, long tripId) {
        String word = switch (hit) {
            case YES -> "yes";
            case NO -> "no";
            case STOP -> "stop";
        };
        return "gather " + word + " " + tripId;
    }

    private static void answer(TripToast.View view, TripToastCard.Hit hit) {
        answeredId = view.id();
        Minecraft mc = Minecraft.getInstance();
        MenuSounds.click(mc.getSoundManager());
        Baritone b = (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        b.getCommandManager().execute(command(hit, view.tripId()));
        // the answer already changed the trip, let the toast catch up now and not on the next tick
        TripBehavior trip = b.getTripBehavior();
        trip.toast().sync(trip.current());
    }

    private static TripToast.View currentView() {
        try {
            TripBehavior trip = ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getTripBehavior();
            TripToast.View v = trip.toast().view(System.currentTimeMillis());
            return v.kind() == TripToast.Kind.NONE ? null : v;
        } catch (Throwable t) {
            // no baritone yet (main menu), nothing to show
            return null;
        }
    }

    private static void fail(String where, Throwable t) {
        Safe.report(where, t);
        if (++errors >= MAX_ERRORS) {
            broken = true;
            System.err.println("trip toast threw " + errors + " times, turning it off. the chat line still asks");
        }
    }
}
