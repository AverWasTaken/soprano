package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.update.WhatsNewPanel;
import baritone.utils.update.Snapshot;
import baritone.utils.update.Updater;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

// the release notes over the title screen's panorama. vanilla draws the background (panorama, blur, the dim), the kit
// draws the card. back and esc go to the title screen that opened it, update starts the download and goes back too so
// the card's progress bar is what you watch
public final class WhatsNewScreen extends Screen {

    private final Minecraft mc = Minecraft.getInstance();
    private final Screen parent;
    private final Snapshot snapshot;
    private final int guiScale;
    private KitSurface surface;
    private WhatsNewPanel panel;
    private boolean ok;
    private long lastNanos;

    public WhatsNewScreen(Screen parent, Snapshot snapshot) {
        super(Component.literal("What's new in Soprano"));
        this.parent = parent;
        this.snapshot = snapshot;
        this.guiScale = Math.max(1, (int) mc.getWindow().getGuiScale());
        try {
            surface = new KitSurface(KitSurface.scaleFor(guiScale));
            panel = new WhatsNewPanel(surface.theme, snapshot, new WhatsNewPanel.Actions() {
                @Override
                public void update() {
                    Updater.get().download();
                    back();
                }

                @Override
                public void openPage(String url) {
                    ConfirmLinkScreen.confirmLinkNow(WhatsNewScreen.this, URI.create(url), true);
                }

                @Override
                public void back() {
                    onClose();
                }
            });
            ok = true;
        } catch (Throwable t) {
            Safe.report("whats new build", t);
        }
    }

    @Override
    public void onClose() {
        mc.setScreen(parent);
    }

    // the kit would not come up: nothing to read here, so back to the title screen. from the tick and not from the
    // render, swapping screens in the middle of a frame is asking for it
    @Override
    public void tick() {
        if (!ok) {
            onClose();
        }
    }

    @Override
    public void removed() {
        McAtlases.releaseAll();
    }

    @Override
    public void resize(Minecraft minecraft, int w, int h) {
        this.width = w;
        this.height = h;
    }

    // ----- drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // render doesn't go through super, so the background (panorama, blur, the dim) is asked for here
        renderBackground(g, mouseX, mouseY, partial);
        if (!ok) {
            return;
        }
        Window win = mc.getWindow();
        int winW = win.getWidth();
        int winH = win.getHeight();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        float inv = 1f / guiScale;
        g.pose().pushPose();
        g.pose().scale(inv, inv, 1f);
        boolean began = false;
        try {
            surface.begin(g, winW, winH);
            began = true;
            surface.syncPreferences(now);
            surface.theme.update(now);
            if (panel.w() != winW || panel.h() != winH) {
                panel.setBounds(0, 0, winW, winH);
            }
            float mx = KitSurface.pointerX();
            float my = KitSurface.pointerY();
            panel.mouseMove(mx, my);
            panel.draw(surface.canvas(), new Frame(now, dt, mx, my));
        } catch (Throwable t) {
            Safe.report("whats new render", t);
        } finally {
            try {
                if (began) {
                    surface.end();
                }
            } catch (Throwable t) {
                Safe.report("whats new render end", t);
            }
            g.pose().popPose();
        }
        surface.afterFrame();
    }

    // ----- input, in framebuffer pixels like the gui. always consumed, there are no vanilla widgets on this screen

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (ok) {
            Safe.run("whats new mouse down", () -> panel.mouseDown(KitSurface.pointerX(), KitSurface.pointerY(),
                    button, GuiScreen.mods()));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (ok) {
            Safe.run("whats new mouse up", () -> panel.mouseUp(KitSurface.pointerX(), KitSurface.pointerY(),
                    button, GuiScreen.mods()));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (ok) {
            Safe.run("whats new scroll", () -> panel.scroll(KitSurface.pointerX(), KitSurface.pointerY(), (float) dy));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (!ok) {
            return true;
        }
        Safe.run("whats new key", () -> {
            if (key == Keys.ESCAPE) {
                onClose();
                return;
            }
            panel.keyDown(key, mods & 15);
        });
        return true;
    }
}
