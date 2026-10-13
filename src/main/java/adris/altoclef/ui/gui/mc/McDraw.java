package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// the few things both canvases need from GuiGraphics, so McCanvas and FallbackCanvas stay one liners. the pose is
// scaled by 1/guiScale for the whole screen, so every number in here is a framebuffer pixel
final class McDraw {

    // vanilla's font is 9 tall with the baseline 7 down from the top
    private static final float VANILLA_H = 9f;
    private static final float VANILLA_BASE = 7f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Map<String, ItemStack> items = new HashMap<>();
    // x0, y0, x1, y1 of each active clip, already intersected with its parent
    private final ArrayDeque<int[]> clips = new ArrayDeque<>();

    GuiGraphics g;

    void begin(GuiGraphics graphics) {
        this.g = graphics;
        clips.clear();
    }

    // a clip left open by an exception must not follow the player back into the world
    void end() {
        if (!clips.isEmpty()) {
            clips.clear();
            g.flush();
            RenderSystem.disableScissor();
        }
        g = null;
    }

    // snap the edges, not the size, and never let a real hairline round down to nothing
    void fill(float x, float y, float w, float h, int argb) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        if (x1 <= x0) {
            x1 = x0 + 1;
        }
        if (y1 <= y0) {
            y1 = y0 + 1;
        }
        g.fill(x0, y0, x1, y1, argb);
    }

    // not GuiGraphics.enableScissor: it runs the rect through the pose into whole gui units, and our pose is 1/guiScale,
    // so every clip would land on a multiple of the gui scale. RenderSystem takes pixels, bottom up
    void pushClip(float x, float y, float w, float h) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        int[] parent = clips.peek();
        if (parent != null) {
            x0 = Math.max(x0, parent[0]);
            y0 = Math.max(y0, parent[1]);
            x1 = Math.min(x1, parent[2]);
            y1 = Math.min(y1, parent[3]);
        }
        clips.push(new int[]{x0, y0, Math.max(x0, x1), Math.max(y0, y1)});
        apply();
    }

    void popClip() {
        if (clips.isEmpty()) {
            return;
        }
        clips.pop();
        apply();
    }

    private void apply() {
        // whatever is batched was meant for the old clip
        g.flush();
        int[] c = clips.peek();
        if (c == null) {
            RenderSystem.disableScissor();
            return;
        }
        int winH = mc.getWindow().getHeight();
        RenderSystem.enableScissor(c[0], winH - c[3], c[2] - c[0], c[3] - c[1]);
    }

    // false when the id is not a registered item, the caller draws its own stand-in tile. renderItem flushes before
    // and after itself, so the quads batched ahead of it land under the item and the ones after land over it. it is
    // real GL, so a clip we pushed cuts the item too
    boolean item(String id, float x, float y, float size) {
        ItemStack stack = stack(id);
        if (stack.isEmpty()) {
            return false;
        }
        if (size <= 0f) {
            return true;
        }
        g.pose().pushPose();
        g.pose().translate(Math.round(x), Math.round(y), 0f);
        g.pose().scale(size / 16f, size / 16f, 1f);
        try {
            g.renderItem(stack, 0, 0);
        } finally {
            g.pose().popPose();
        }
        // items draw at z 150 with the depth test on, so anything drawn after them (a dropdown, the toast) would sit
        // behind their little cubes. nothing in this screen uses depth, so wipe it
        g.flush();
        RenderSystem.clear(256);
        return true;
    }

    // the face from the tab list's skin. false when we have no skin for the name, or the renderer throws
    boolean face(String name, float x, float y, float size) {
        try {
            if (mc.getConnection() == null || name == null) {
                return false;
            }
            PlayerInfo info = mc.getConnection().getPlayerInfo(name);
            if (info == null) {
                return false;
            }
            PlayerFaceRenderer.draw(g, info.getSkin(), Math.round(x), Math.round(y), Math.max(1, Math.round(size)));
            return true;
        } catch (Throwable t) {
            Safe.report("gui player face", t);
            return false;
        }
    }

    // the letter for an item we cannot draw: "minecraft:oak_log" is O
    static String letterOf(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        String path = id.substring(id.indexOf(':') + 1);
        return path.isEmpty() ? "?" : path.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    private ItemStack stack(String id) {
        ItemStack cached = items.get(id);
        if (cached != null) {
            return cached;
        }
        ItemStack stack = ItemStack.EMPTY;
        Identifier rl = id == null ? null : Identifier.tryParse(id);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
            if (item != Items.AIR) {
                stack = new ItemStack(item);
            }
        }
        items.put(id, stack);
        return stack;
    }

    // vanilla treats alpha under 4 as fully opaque, which is how a fade out ends with a bang
    void vanillaText(String s, float x, float baseline, float size, int argb) {
        if ((argb >>> 24) < 4 || s.isEmpty()) {
            return;
        }
        float scale = size / VANILLA_H;
        g.pose().pushPose();
        g.pose().translate(x, baseline - VANILLA_BASE * scale, 0f);
        g.pose().scale(scale, scale, 1f);
        try {
            g.drawString(mc.font, s, 0, 0, argb, false);
        } finally {
            g.pose().popPose();
        }
    }

    float vanillaWidth(String s, float size) {
        return mc.font.width(s) * size / VANILLA_H;
    }

    float vanillaAscent(float size) {
        return VANILLA_BASE * size / VANILLA_H;
    }

    float vanillaDescent(float size) {
        return (VANILLA_H - VANILLA_BASE) * size / VANILLA_H;
    }
}
