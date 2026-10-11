package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Spring;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// the everyday switches have a stable home; searching still finds their full setting rows
final class CommonSettings extends Widget {

    record Spec(String name, String label, Icon icon) { }

    static final List<Spec> SPECS = List.of(
            new Spec("allowBoats", "Boats", Icon.BOAT),
            new Spec("allowParkour", "Parkour", Icon.PARKOUR),
            new Spec("fastMode", "Fast mode", Icon.BOLT),
            new Spec("allowSprint", "Sprint", Icon.RUN),
            new Spec("allowBreak", "Break blocks", Icon.PICKAXE),
            new Spec("allowPlace", "Place blocks", Icon.BLOCK),
            new Spec("autoTool", "Auto tool", Icon.WRENCH),
            new Spec("allowInventory", "Inventory", Icon.BACKPACK),
            new Spec("allowWaterBucketFall", "Water clutch", Icon.WATER),
            new Spec("allowLadderClutch", "Ladder clutch", Icon.LADDER),
            new Spec("smoothLook", "Smooth look", Icon.EYE),
            new Spec("renderPath", "Show path", Icon.GOTO)
    );

    private final ShellContext ctx;
    private final SettingHost host;
    private final List<Tile> tiles = new ArrayList<>();
    private BiConsumer<Float, Float> reveal = (a, b) -> { };

    CommonSettings(Theme theme, ShellContext ctx, SettingHost host) {
        super(theme);
        this.ctx = ctx;
        this.host = host;
    }

    void setReveal(BiConsumer<Float, Float> reveal) {
        this.reveal = reveal;
    }

    void setItems(List<SettingView> items) {
        List<Tile> next = new ArrayList<>();
        for (Spec spec : SPECS) {
            for (SettingView view : items) {
                if (view.name().equals(spec.name()) && view.kind() == Kind.BOOLEAN) {
                    Tile tile = tiles.stream().filter(t -> t.spec.equals(spec)).findFirst().orElse(null);
                    if (tile == null) {
                        tile = new Tile(spec, view);
                    } else {
                        tile.update(view);
                    }
                    next.add(tile);
                    break;
                }
            }
        }
        tiles.clear();
        tiles.addAll(next);
        layout();
    }

    boolean containsSetting(String name) {
        return tiles.stream().anyMatch(t -> t.spec.name().equals(name));
    }

    void update(SettingView view) {
        tiles.stream().filter(t -> t.spec.name().equals(view.name())).forEach(t -> t.update(view));
    }

    int size() {
        return tiles.size();
    }

    int columns(int width) {
        return Math.max(1, Math.min(6, width / theme.px(112)));
    }

    int heightFor(int width) {
        return tiles.isEmpty() ? 0 : theme.px(24) + (tiles.size() + columns(width) - 1) / columns(width) * theme.px(88);
    }

    @Override
    protected void layout() {
        int cols = columns(w);
        int cw = w / cols;
        int gap = theme.px(6);
        for (int i = 0; i < tiles.size(); i++) {
            tiles.get(i).setBounds(x + (i % cols) * cw + gap, y + theme.px(24) + (i / cols) * theme.px(88) + gap,
                    Math.max(0, cw - 2 * gap), theme.px(88) - 2 * gap);
        }
    }

    @Override
    public List<Widget> children() {
        return new ArrayList<>(tiles);
    }

    @Override
    public void draw(Canvas c, Frame f) {
        c.text("Common settings", x + theme.px(6), y + theme.px(16), theme.label, theme.color(Theme.TEXT));
        for (Tile tile : tiles) {
            tile.draw(c, f);
        }
    }

    private void toggle(Spec spec) {
        Tile tile = tiles.stream().filter(t -> t.spec.equals(spec)).findFirst().orElseThrow();
        Result result = host.set(spec.name(), String.valueOf(!Boolean.parseBoolean(tile.view.value())));
        if (!result.ok()) {
            ctx.toast(result);
        }
    }

    private final class Tile extends Button {
        private final Spec spec;
        private SettingView view;
        private final Spring depth;

        Tile(Spec spec, SettingView view) {
            super(CommonSettings.this.theme, spec.label(), Kind.GHOST, spec.icon(), () -> toggle(spec));
            this.spec = spec;
            this.view = view;
            depth = new Spring(Boolean.parseBoolean(view.value()) ? 1f : 0f);
        }

        void update(SettingView view) {
            this.view = view;
            depth.set(Boolean.parseBoolean(view.value()) ? 1f : 0f, now, Theme.ANIM_FAST);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (focused) {
                reveal.accept((float) (y - CommonSettings.this.y), (float) (y + h - CommonSettings.this.y));
            }
        }

        // control centre rules: on is a lit blue tile with a white glyph, off is a quiet card. the spring only
        // blends the two, nothing sinks or rises
        @Override
        public void draw(Canvas c, Frame f) {
            stamp(f);
            if (hovered || (focused && !host.mouseOverRows(f.mouseX(), f.mouseY()))) {
                host.hover(view);
            }
            hoverTo(hoverA, hovered);
            float d = depth.value(now);
            float hv = hoverA.value(now);
            float r = theme.pxf(Theme.CARD_RADIUS);
            int off = Theme.lerp(theme.color(Theme.SURFACE), theme.color(Theme.HOVER), hv);
            int lit = Theme.lerp(theme.color(Theme.ACCENT), theme.color(Theme.ACCENT_HOVER), hv);
            c.fillRoundRect(x, y, w, h, r, Theme.lerp(off, lit, d));
            c.strokeRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.HAIRLINE), 1f - d));
            super.draw(c, f);
        }

        @Override
        protected void drawContent(Canvas c, int col, float hv) {
            boolean on = Boolean.parseBoolean(view.value());
            float d = depth.value(now);
            int glyph = Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.ON_ACCENT), d);
            int name = Theme.lerp(theme.color(Theme.TEXT), theme.color(Theme.ON_ACCENT), d);
            int state = Theme.lerp(theme.color(Theme.TEXT_3), Theme.fade(theme.color(Theme.ON_ACCENT), 0.75f), d);
            int is = theme.px(Theme.ICON_LG);
            c.icon(spec.icon(), x + (w - is) / 2f, y + theme.px(10), is, glyph);
            String label = c.ellipsize(spec.label(), theme.label, Math.max(0, w - theme.px(12)));
            c.text(label, x + (w - c.measure(label, theme.label)) / 2f, y + theme.px(52), theme.label, name);
            String word = on ? "On" : "Off";
            c.text(word, x + (w - c.measure(word, theme.small)) / 2f, y + theme.px(68), theme.small, state);
        }
    }
}
