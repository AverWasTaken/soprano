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

package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.RadialMenu;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.effortless.BuildMode;
import baritone.effortless.EffortlessJob;
import baritone.effortless.EffortlessManager;
import baritone.effortless.ShapeKind;
import baritone.effortless.ShapeSpec;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Effortless Soprano's radial: hold the key, flick to a shape and let go to build with it, or tap the key and click
// around (shapes and their options) with it open, then esc or the key again. the hub is none, build mode off. nothing
// pauses, the world keeps going behind it
public final class EffortlessRadialScreen extends Screen {

    // let go sooner than this after opening and it was a tap: the menu stays up for clicking
    private static final long TAP_NANOS = 250_000_000L;

    static final ShapeKind[] SHAPES = {
            ShapeKind.SINGLE, ShapeKind.LINE, ShapeKind.WALL, ShapeKind.FLOOR, ShapeKind.CUBOID, ShapeKind.STAIRS,
            ShapeKind.ROOF, ShapeKind.PYRAMID, ShapeKind.SPHERE, ShapeKind.CYLINDER, ShapeKind.CIRCLE
    };

    private final Minecraft mc = Minecraft.getInstance();
    private final McDraw draw = new McDraw();
    private final long openedAt = System.nanoTime();
    private McAtlases atlases;
    private McCanvas atlasCanvas;
    private FallbackCanvas plainCanvas;
    private Canvas canvas;
    private Theme theme;
    private RadialMenu menu;
    private boolean held = true;
    private boolean closing;

    private EffortlessRadialScreen() {
        super(Component.literal("soprano effortless"));
        build();
    }

    // from the keyboard mixin, the radial key going down with nothing open
    public static boolean open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.gui.overlay() != null) {
            return false;
        }
        mc.gui.setScreen(new EffortlessRadialScreen());
        return true;
    }

    private static EffortlessManager manager() {
        return ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getEffortlessManager();
    }

    static Icon icon(ShapeKind k) {
        return switch (k) {
            case SINGLE -> Icon.SHAPE_SINGLE;
            case LINE -> Icon.SHAPE_LINE;
            case FLOOR -> Icon.SHAPE_FLOOR;
            case WALL -> Icon.SHAPE_WALL;
            case CUBOID -> Icon.SHAPE_CUBOID;
            case CIRCLE -> Icon.SHAPE_CIRCLE;
            case CYLINDER -> Icon.SHAPE_CYLINDER;
            case SPHERE -> Icon.SHAPE_SPHERE;
            case STAIRS -> Icon.SHAPE_STAIRS;
            case PYRAMID -> Icon.SHAPE_PYRAMID;
            case ROOF -> Icon.SHAPE_ROOF;
        };
    }

    private void build() {
        McAtlases at = McAtlases.get();
        // a notch bigger than the click gui: it's up for a second at a time and you aim at it with a flick
        float scale = Theme.scaleFor(mc.getWindow().getHeight()) * 1.25f;
        Settings s = Baritone.settings();
        Anim.enabled = s.guiAnimations.value;
        if (at != null && at.ok) {
            atlases = at;
            atlasCanvas = new McCanvas(at, draw);
            canvas = atlasCanvas;
        } else {
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
        }
        theme = new Theme(scale, canvas);
        theme.setDark(s.guiDarkMode.value);
        canvas.setTheme(theme);
        if (atlases != null) {
            atlases.prewarm(theme);
        }
        List<RadialMenu.Entry> entries = new ArrayList<>();
        for (ShapeKind k : SHAPES) {
            entries.add(new RadialMenu.Entry(icon(k), k.title));
        }
        menu = new RadialMenu(theme, entries, i -> rebuildRows());
        menu.setSelected(BuildMode.active() ? indexOf(manager().spec().kind()) : RadialMenu.HUB);
        rebuildRows();
    }

    private static int indexOf(ShapeKind k) {
        for (int i = 0; i < SHAPES.length; i++) {
            if (SHAPES[i] == k) {
                return i;
            }
        }
        return RadialMenu.HUB;
    }

    private ShapeKind selectedKind() {
        int i = menu.selected();
        return i >= 0 && i < SHAPES.length ? SHAPES[i] : null;
    }

    // ----- the option rows, for whatever shape is picked

    private void rebuildRows() {
        ShapeKind kind = selectedKind();
        List<RadialMenu.Row> rows = new ArrayList<>();
        if (kind == null) {
            menu.setRows(rows);
            return;
        }
        EffortlessManager m = manager();
        ShapeSpec spec = m.spec().withKind(kind);
        m.spec(spec);
        List<ShapeSpec.Fill> fills = fills(kind);
        if (fills.size() > 1) {
            List<String> names = new ArrayList<>();
            for (ShapeSpec.Fill f : fills) {
                names.add(fillName(kind, f));
            }
            int at = Math.max(0, fills.indexOf(spec.fill()));
            rows.add(new RadialMenu.Row("Shape", names, at, i -> {
                m.spec(m.spec().withFill(fills.get(i)));
                rebuildRows();
            }));
        }
        if (kind == ShapeKind.SPHERE) {
            rows.add(new RadialMenu.Row("Sphere", List.of("Full", "Dome"), spec.half() ? 1 : 0, i -> {
                m.spec(m.spec().withHalf(i == 1));
                rebuildRows();
            }));
        }
        List<ShapeSpec.Axis> axes = axes(kind);
        if (!axes.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ShapeSpec.Axis a : axes) {
                names.add(a == ShapeSpec.Axis.FREE ? "Free" : a.name());
            }
            int at = Math.max(0, axes.indexOf(spec.axis()));
            rows.add(new RadialMenu.Row(kind == ShapeKind.CIRCLE || kind == ShapeKind.CYLINDER ? "Facing" : "Direction",
                    names, at, i -> {
                        m.spec(m.spec().withAxis(axes.get(i)));
                        rebuildRows();
                    }));
        }
        if (kind != ShapeKind.SINGLE) {
            rows.add(new RadialMenu.Row("Thickness", List.of("1", "2", "3", "4"), Math.min(3, spec.thickness() - 1), i -> {
                m.spec(m.spec().withThickness(i + 1));
                rebuildRows();
            }));
        }
        EffortlessJob.FillMode[] modes = EffortlessJob.FillMode.values();
        rows.add(new RadialMenu.Row("Right click places", List.of("Into empty", "Over anything", "Replace hand"),
                BuildMode.placeMode().ordinal(), i -> {
                    BuildMode.placeMode(modes[i]);
                    rebuildRows();
                }));
        BuildMode.PaletteSource[] sources = BuildMode.PaletteSource.values();
        rows.add(new RadialMenu.Row("With", List.of("Hand", "Offhand", "Hotbar mix"), BuildMode.palette().ordinal(), i -> {
            BuildMode.palette(sources[i]);
            rebuildRows();
        }));
        if (BuildMode.palette() == BuildMode.PaletteSource.HOTBAR) {
            mixRows(rows);
        }
        baritone.api.utils.EffortlessMaterials[] from = baritone.api.utils.EffortlessMaterials.values();
        rows.add(new RadialMenu.Row("Blocks from", List.of("Inventory", "Storage", "Gather"), BuildMode.materials().ordinal(), i -> {
            BuildMode.materials(from[i]);
            rebuildRows();
        }));
        BuildMode.BreakFilter[] filters = BuildMode.BreakFilter.values();
        rows.add(new RadialMenu.Row("Left click breaks", List.of("All", "Hand block", "Offhand block", "All but hand"),
                BuildMode.breakFilter().ordinal(), i -> {
                    BuildMode.breakFilter(filters[i]);
                    rebuildRows();
                }));
        rows.add(new RadialMenu.Row("Containers", List.of("Break", "Leave"), BuildMode.skipContainers() ? 1 : 0, i -> {
            BuildMode.skipContainers(i == 1);
            rebuildRows();
        }));
        menu.setRows(rows);
    }

    // one line per hotbar block: its share, a weight (off leaves it out) and whether it turns at random. the hologram
    // shows the exact pick per cell, so changing a weight or rerolling shows straight away in the world
    private void mixRows(List<RadialMenu.Row> rows) {
        rows.add(RadialMenu.Row.heading("Hotbar mix"));
        boolean any = false;
        for (int slot = 0; slot < 9; slot++) {
            var block = BuildMode.slotBlock(slot);
            if (block.isEmpty()) {
                continue;
            }
            any = true;
            int at = slot;
            String name = block.get().getBlock().getName().getString();
            if (name.length() > 18) {
                name = name.substring(0, 17) + "\u2026";
            }
            int share = BuildMode.share(slot);
            String label = (slot + 1) + "  " + name + (share > 0 ? "  " + share + "%" : "");
            boolean turns = baritone.effortless.Palette.canTurn(block.get());
            rows.add(new RadialMenu.Row(label, List.of("Off", "1", "2", "3", "4", "5"), Math.min(5, BuildMode.slotWeight(slot)),
                    i -> {
                        BuildMode.slotWeight(at, i);
                        rebuildRows();
                    }, turns ? "Turn" : null, BuildMode.slotTurn(slot), () -> {
                        BuildMode.slotTurn(at, !BuildMode.slotTurn(at));
                        rebuildRows();
                    }));
        }
        if (!any) {
            rows.add(RadialMenu.Row.heading("Put some blocks on the hotbar"));
            return;
        }
        rows.add(new RadialMenu.Row("Pattern", List.of("Reroll"), -1, i -> {
            BuildMode.reroll();
            rebuildRows();
        }));
    }

    static List<ShapeSpec.Fill> fills(ShapeKind k) {
        return switch (k) {
            case CUBOID -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.HOLLOW, ShapeSpec.Fill.WALLS, ShapeSpec.Fill.FRAME);
            case CYLINDER -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.HOLLOW, ShapeSpec.Fill.WALLS);
            case FLOOR, CIRCLE, SPHERE, PYRAMID, ROOF, STAIRS -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.HOLLOW);
            case WALL -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.FRAME);
            default -> List.of();
        };
    }

    static String fillName(ShapeKind k, ShapeSpec.Fill f) {
        return switch (f) {
            case FILLED -> k == ShapeKind.STAIRS ? "Solid" : "Filled";
            case HOLLOW -> switch (k) {
                case FLOOR -> "Outline";
                case CIRCLE -> "Ring";
                case STAIRS -> "Steps only";
                default -> "Hollow";
            };
            case WALLS -> k == ShapeKind.CYLINDER ? "Tube" : "Walls";
            case FRAME -> k == ShapeKind.WALL ? "Border" : "Edges";
        };
    }

    static List<ShapeSpec.Axis> axes(ShapeKind k) {
        return switch (k) {
            case LINE -> List.of(ShapeSpec.Axis.FREE, ShapeSpec.Axis.X, ShapeSpec.Axis.Y, ShapeSpec.Axis.Z);
            case WALL -> List.of(ShapeSpec.Axis.FREE, ShapeSpec.Axis.X, ShapeSpec.Axis.Z);
            case CIRCLE, CYLINDER -> List.of(ShapeSpec.Axis.Y, ShapeSpec.Axis.X, ShapeSpec.Axis.Z);
            default -> List.of();
        };
    }

    // ----- closing: whatever is picked is what build mode does

    private void apply() {
        if (closing) {
            return;
        }
        closing = true;
        BuildMode.choose(selectedKind());
        if (mc.gui.screen() == this) {
            mc.gui.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void removed() {
        McAtlases.releaseAll();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
    }

    @Override
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    // ----- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Window win = mc.getWindow();
        int winW = win.getWidth();
        int winH = win.getHeight();
        float inv = 1f / Math.max(1, win.getGuiScale());
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            if (atlasCanvas != null) {
                atlasCanvas.begin(g, winW, winH);
            } else {
                plainCanvas.begin(g, winW, winH);
            }
            began = true;
            long now = System.nanoTime();
            theme.update(now);
            if (menu.w() != winW || menu.h() != winH) {
                menu.setBounds(0, 0, winW, winH);
            }
            float px = KitSurface.pointerX();
            float py = KitSurface.pointerY();
            menu.mouseMove(px, py);
            menu.draw(canvas, new Frame(now, 0f, px, py));
        } catch (Throwable t) {
            Safe.report("effortless radial render", t);
        } finally {
            try {
                if (began) {
                    if (atlasCanvas != null) {
                        atlasCanvas.end();
                    } else {
                        plainCanvas.end();
                    }
                }
            } catch (Throwable t) {
                Safe.report("effortless radial render end", t);
            }
            g.pose().popMatrix();
        }
        if (atlasCanvas != null && atlasCanvas.broken) {
            atlasCanvas = null;
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
            canvas.setTheme(theme);
        }
    }

    // ----- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Safe.run("effortless radial click", () -> {
            int button = McKeys.buttonToKit(event.button());
            menu.mouseDown(KitSurface.pointerX(), KitSurface.pointerY(), button, McKeys.mods(event));
        });
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        Safe.run("effortless radial key", () -> {
            int key = McKeys.toKit(event.key());
            if (key == Keys.ESCAPE || (!held && SopranoKeys.isRadial(event))) {
                apply();
            }
        });
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Safe.run("effortless radial key up", () -> {
            if (!held || !SopranoKeys.isRadial(event)) {
                return;
            }
            held = false;
            if (System.nanoTime() - openedAt < TAP_NANOS) {
                // a tap: stay up for clicking
                return;
            }
            // a hold: whatever the pointer is on when you let go is the pick
            int h = menu.hover();
            if (h != RadialMenu.NOTHING) {
                menu.setSelected(h);
            }
            apply();
        });
        return true;
    }
}
