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

// Effortless Soprano's radial: the shapes on a ring, what you build with on the left, the picked shape's options on the
// right. with effortlessRadialHold it's up while the key is held and letting go picks what's under the pointer; without,
// a press opens it, clicks pick and set, and the key again (or esc) closes it. the hub is none, build mode off.
// nothing pauses, the world keeps going behind it
public final class EffortlessRadialScreen extends Screen {

    // in hold mode, let go sooner than this after opening and it was a tap: the menu stays up for clicking anyway
    private static final long TAP_NANOS = 250_000_000L;

    // Effortless Structure's order round the ring: one block, the straight ones, the diagonal ones, the round ones,
    // the pointed ones
    public static final ShapeKind[] SHAPES = {
            ShapeKind.SINGLE, ShapeKind.LINE, ShapeKind.WALL, ShapeKind.FLOOR, ShapeKind.CUBE, ShapeKind.DIAGONAL_LINE,
            ShapeKind.DIAGONAL_WALL, ShapeKind.SLOPE_FLOOR, ShapeKind.CIRCLE, ShapeKind.CYLINDER, ShapeKind.SPHERE,
            ShapeKind.DOME, ShapeKind.PYRAMID, ShapeKind.CONE
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

    public static Icon icon(ShapeKind k) {
        return switch (k) {
            case SINGLE -> Icon.SHAPE_SINGLE;
            case LINE -> Icon.SHAPE_LINE;
            case WALL -> Icon.SHAPE_WALL;
            case FLOOR -> Icon.SHAPE_FLOOR;
            case CUBE -> Icon.SHAPE_CUBOID;
            case DIAGONAL_LINE -> Icon.SHAPE_DIAG_LINE;
            case DIAGONAL_WALL -> Icon.SHAPE_DIAG_WALL;
            case SLOPE_FLOOR -> Icon.SHAPE_STAIRS;
            case CIRCLE -> Icon.SHAPE_CIRCLE;
            case CYLINDER -> Icon.SHAPE_CYLINDER;
            case SPHERE -> Icon.SHAPE_SPHERE;
            case DOME -> Icon.SHAPE_DOME;
            case PYRAMID -> Icon.SHAPE_PYRAMID;
            case CONE -> Icon.SHAPE_CONE;
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
        menu = new RadialMenu(theme, entries, i -> rebuildPanels());
        menu.setSelected(BuildMode.active() ? indexOf(manager().spec().kind()) : RadialMenu.HUB);
        held = Baritone.settings().effortlessRadialHold.value;
        rebuildPanels();
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

    // ----- the two panels

    private void rebuildPanels() {
        ShapeKind kind = selectedKind();
        List<RadialMenu.Item> left = new ArrayList<>();
        List<RadialMenu.Item> right = new ArrayList<>();
        if (kind != null) {
            EffortlessManager m = manager();
            m.spec(m.spec().withKind(kind));
            blocksPanel(left);
            shapePanel(right, m.spec());
            clicksPanel(right);
        }
        menu.setPanels(left, right);
    }

    // what goes down and where it comes from. the same for every shape, so it lives on its own side
    private void blocksPanel(List<RadialMenu.Item> out) {
        out.add(new RadialMenu.Title("Blocks", "Used for every shape"));
        BuildMode.PaletteSource[] sources = BuildMode.PaletteSource.values();
        out.add(new RadialMenu.Choice("Place with", List.of("Held block", "Hotbar mix"), BuildMode.palette().ordinal(), i -> {
            BuildMode.palette(sources[i]);
            rebuildPanels();
        }));
        if (BuildMode.palette() == BuildMode.PaletteSource.HOTBAR) {
            mix(out);
        }
        baritone.api.utils.EffortlessMaterials[] from = baritone.api.utils.EffortlessMaterials.values();
        out.add(new RadialMenu.Choice("Take blocks from", List.of("Inventory", "Storage", "Gather"), BuildMode.materials().ordinal(), i -> {
            BuildMode.materials(from[i]);
            rebuildPanels();
        }));
    }

    // one line per hotbar block: its share, a - n + weight (off leaves it out) and whether it turns at random. the
    // hologram shows the exact pick per cell, so a change shows straight away in the world
    private void mix(List<RadialMenu.Item> out) {
        boolean any = false;
        for (int slot = 0; slot < 9; slot++) {
            var block = BuildMode.slotBlock(slot);
            if (block.isEmpty()) {
                continue;
            }
            any = true;
            int at = slot;
            int share = BuildMode.share(slot);
            boolean turns = baritone.effortless.Palette.canTurn(block.get());
            out.add(new RadialMenu.Stepper((slot + 1) + "  " + block.get().getBlock().getName().getString(),
                    share > 0 ? share + "%" : null, BuildMode.slotWeight(slot), 0, 9, w -> {
                BuildMode.slotWeight(at, w);
                rebuildPanels();
            }, turns ? "Turn" : null, BuildMode.slotTurn(slot), () -> {
                BuildMode.slotTurn(at, !BuildMode.slotTurn(at));
                rebuildPanels();
            }));
        }
        if (!any) {
            out.add(new RadialMenu.Note("Put some blocks on your hotbar to mix them"));
            return;
        }
        out.add(new RadialMenu.Action("Reroll the pattern", () -> {
            BuildMode.reroll();
            rebuildPanels();
        }));
    }

    // the shape's name, how it's clicked out, and only the options it has, as Effortless Structure offers them
    private void shapePanel(List<RadialMenu.Item> out, ShapeSpec spec) {
        EffortlessManager m = manager();
        ShapeKind kind = spec.kind();
        out.add(new RadialMenu.Title(kind.title, clicksHint(spec)));
        if (kind == ShapeKind.LINE) {
            ShapeSpec.Direction[] ds = ShapeSpec.Direction.values();
            out.add(new RadialMenu.Choice("Direction", List.of("All", "Floor", "X", "Y", "Z"), spec.direction().ordinal(), i -> {
                m.spec(m.spec().withDirection(ds[i]));
                rebuildPanels();
            }));
        }
        if (kind.round()) {
            out.add(new RadialMenu.Choice("Start from", List.of("Corner", "Middle"), spec.centered() ? 1 : 0, i -> {
                m.spec(m.spec().withCentered(i == 1));
                rebuildPanels();
            }));
        }
        List<ShapeSpec.Fill> fills = fills(kind);
        if (fills.size() > 1) {
            List<String> names = new ArrayList<>();
            for (ShapeSpec.Fill f : fills) {
                names.add(fillName(f));
            }
            out.add(new RadialMenu.Choice("Filling", names, Math.max(0, fills.indexOf(spec.fill())), i -> {
                m.spec(m.spec().withFill(fills.get(i)));
                rebuildPanels();
            }));
        }
        if (kind.faces()) {
            ShapeSpec.Facing[] fs = ShapeSpec.Facing.values();
            out.add(new RadialMenu.Choice("Facing", List.of("Both", "Vertical", "Horizontal"), spec.facing().ordinal(), i -> {
                m.spec(m.spec().withFacing(fs[i]));
                rebuildPanels();
            }));
        }
        if (kind == ShapeKind.SLOPE_FLOOR) {
            out.add(new RadialMenu.Choice("Raised edge", List.of("Short", "Long"), spec.longEdge() ? 1 : 0, i -> {
                m.spec(m.spec().withLongEdge(i == 1));
                rebuildPanels();
            }));
        }
        if (kind.lengths()) {
            out.add(new RadialMenu.Choice("Plane length", List.of("Variable", "Equal"), spec.equal() ? 1 : 0, i -> {
                m.spec(m.spec().withEqual(i == 1));
                rebuildPanels();
            }));
        }
    }

    // what the two mouse buttons touch
    private void clicksPanel(List<RadialMenu.Item> out) {
        out.add(new RadialMenu.Title("Clicks", "Hold a block in your offhand to match it"));
        BuildMode.PlaceMode[] modes = BuildMode.PlaceMode.values();
        out.add(new RadialMenu.Choice("Right click fills", List.of("Empty", "Existing", "Both"), BuildMode.placeMode().ordinal(), i -> {
            BuildMode.placeMode(modes[i]);
            rebuildPanels();
        }));
        BuildMode.Match[] matches = BuildMode.Match.values();
        if (BuildMode.placeMode() != BuildMode.PlaceMode.EMPTY) {
            out.add(new RadialMenu.Choice("Existing blocks", List.of("Any", "Offhand's", "Not offhand's"),
                    BuildMode.placeMatch().ordinal(), i -> {
                        BuildMode.placeMatch(matches[i]);
                        rebuildPanels();
                    }));
        }
        out.add(new RadialMenu.Choice("Left click breaks", List.of("All", "Offhand's", "Not offhand's"),
                BuildMode.breakMatch().ordinal(), i -> {
                    BuildMode.breakMatch(matches[i]);
                    rebuildPanels();
                }));
        out.add(new RadialMenu.Choice("Containers", List.of("Break", "Leave"), BuildMode.skipContainers() ? 1 : 0, i -> {
            BuildMode.skipContainers(i == 1);
            rebuildPanels();
        }));
    }

    // "3 clicks: a corner, the far corner, then the height"
    static String clicksHint(ShapeSpec spec) {
        ShapeKind kind = spec.kind();
        int n = kind.clicks(spec);
        String first = kind.round() && spec.centered() ? "the middle" : "a corner";
        String how = switch (kind) {
            case SINGLE -> "the block you aim at";
            case LINE, DIAGONAL_LINE -> "the start, then the end";
            case WALL, FLOOR -> "a corner, then the far one";
            case CIRCLE -> first + ", then the rim";
            case DIAGONAL_WALL -> "the start, the end, then the height";
            case CUBE, CYLINDER, SPHERE -> first + ", the far corner, then how far out";
            default -> first + ", the far corner, then the height";
        };
        return n + (n == 1 ? " click: " : " clicks: ") + how;
    }

    static List<ShapeSpec.Fill> fills(ShapeKind k) {
        return switch (k) {
            case CUBE -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.HOLLOW, ShapeSpec.Fill.SKELETON);
            case WALL, FLOOR, CIRCLE, CYLINDER, SPHERE, DOME, PYRAMID -> List.of(ShapeSpec.Fill.FILLED, ShapeSpec.Fill.HOLLOW);
            default -> List.of();
        };
    }

    static String fillName(ShapeSpec.Fill f) {
        return switch (f) {
            case FILLED -> "Filled";
            case HOLLOW -> "Hollow";
            case SKELETON -> "Skeleton";
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
            // the key again closes it once it's been let go (press mode, or a tap in hold mode)
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
            // let go after holding: whatever the pointer is on is the pick, off the ring keeps what's picked
            int h = menu.hover();
            if (h != RadialMenu.NOTHING) {
                menu.setSelected(h);
            }
            apply();
        });
        return true;
    }
}
