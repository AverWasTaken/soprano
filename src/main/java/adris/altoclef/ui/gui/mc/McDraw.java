package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// todo 26.3: nothing in here draws yet, every drawing body is a stub (see the todos), the logic around them is kept.
// the few things both canvases need from GuiGraphicsExtractor, so McCanvas and FallbackCanvas stay one liners. the pose is
// scaled by 1/guiScale for the whole screen, so every number in here is a framebuffer pixel
final class McDraw {

    // vanilla's font is 9 tall with the baseline 7 down from the top
    private static final float VANILLA_H = 9f;
    private static final float VANILLA_BASE = 7f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Map<String, ItemStack> items = new HashMap<>();
    // x0, y0, x1, y1 of each active clip, already intersected with its parent
    private final ArrayDeque<int[]> clips = new ArrayDeque<>();

    GuiGraphicsExtractor g;

    void begin(GuiGraphicsExtractor graphics) {
        this.g = graphics;
        clips.clear();
    }

    // a clip left open by an exception must not follow the player back into the world
    void end() {
        if (!clips.isEmpty()) {
            clips.clear();
            // todo 26.3: g.disableScissor() here, GuiGraphics.flush and RenderSystem.disableScissor are gone
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
        // todo 26.3: g.fill(x0, y0, x1, y1, argb) has the same shape, held back until GuiScreen sets the 1/guiScale pose
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
        // todo 26.3: RenderSystem.enableScissor/disableScissor and GuiGraphics.flush are gone. the extractor has
        // enableScissor/disableScissor(int...) but in gui units through the pose, so a pixel exact clip needs a plan
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
        // todo 26.3: g.item(stack, x, y) under a Matrix3x2fStack pushMatrix/translate/scale. renderItem, the 3d pose and
        // RenderSystem.clear(256) are gone, the depth wipe after it is probably moot now
        return true;
    }

    // the face from the tab list's skin. false when we have no skin for the name, or the renderer throws
    boolean face(String name, float x, float y, float size) {
        try {
            if (mc.getConnection() == null || name == null) {
                return false;
            }
            // todo 26.3: PlayerFaceRenderer is PlayerFaceExtractor now, draw mc.getConnection().getPlayerInfo(name).getSkin()
            // through it. false makes the canvas draw its stand-in
            return false;
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
        // todo 26.3: g.text(mc.font, s, 0, 0, argb, false) under a Matrix3x2fStack translate(x, baseline - VANILLA_BASE *
        // scale) and scale(size / VANILLA_H). drawString and the 3d pose are gone
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
