package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.AtlasPage;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.DataBufferInt;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

// the cpu side atlases (they are kept between opens, rasterising inter is the slow part) and the gpu side of each page.
// textures only live while the screen is open: release() on removed, they are re-uploaded on demand next time. if
// anything in here throws, ok stays false and the screen draws with vanilla's font and plain fills instead
final class McAtlases {

    private static McAtlases instance;

    private static final class Slot {
        final DynamicTexture texture;
        final ResourceLocation location;
        int version = -1;

        Slot(DynamicTexture texture, ResourceLocation location) {
            this.texture = texture;
            this.location = location;
        }
    }

    final boolean ok;
    final AwtFonts fonts;
    final GlyphAtlas glyphs;
    final ShapeAtlas shapes;

    private final Map<Integer, Slot> slots = new HashMap<>();
    // hand drawn pictures (the placement map), one texture each, keyed by the picture itself
    private final Map<PixelImage, Slot> images = new IdentityHashMap<>();
    // a picture has no id of its own, so each one gets the next number for its texture name
    private int pictures;
    private AtlasPage lastPage;
    private Slot lastSlot;

    private McAtlases(AwtFonts fonts, GlyphAtlas glyphs, ShapeAtlas shapes) {
        this.ok = fonts != null;
        this.fonts = fonts;
        this.glyphs = glyphs;
        this.shapes = shapes;
    }

    // a failed build is not cached as a success, the next open gets to try again
    static McAtlases get() {
        if (instance == null || !instance.ok) {
            instance = build();
        }
        return instance;
    }

    private static McAtlases build() {
        try {
            AwtFonts fonts = AwtFonts.load();
            return new McAtlases(fonts, new GlyphAtlas(fonts), new ShapeAtlas());
        } catch (Throwable t) {
            Safe.report("gui atlases", t);
            return new McAtlases(null, null, null);
        }
    }

    // rasterise the plain ascii once up front so the first frames are not one texture upload per new word
    void prewarm(Theme theme) {
        if (!ok) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) {
            sb.append(c);
        }
        String ascii = sb.toString();
        TextStyle[] styles = {theme.title, theme.label, theme.body, theme.small, theme.smallLabel};
        for (TextStyle s : styles) {
            try {
                glyphs.shape(ascii, s);
                glyphs.shape("0123456789", s.asTabular());
            } catch (Throwable t) {
                Safe.report("gui prewarm", t);
                return;
            }
        }
    }

    // the texture to bind for this page, with the latest pixels in it
    ResourceLocation texture(AtlasPage page) {
        return ensureUploaded(page).location;
    }

    private Slot ensureUploaded(AtlasPage page) {
        // text is thousands of blits from the same page in a row
        if (page == lastPage && lastSlot.version == page.version()) {
            return lastSlot;
        }
        Slot slot = slots.get(page.id());
        if (slot == null) {
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("soprano", "gui/atlas_" + page.id());
            DynamicTexture tex = new DynamicTexture(new NativeImage(page.width(), page.height(), true));
            mc.getTextureManager().register(rl, tex);
            // nearest, the atlas is pixel exact and a linear sample would smear glyphs into their neighbours
            tex.setFilter(false, false);
            slot = new Slot(tex, rl);
            slots.put(page.id(), slot);
        }
        int version = page.version();
        if (slot.version != version) {
            copy(page, slot.texture);
            slot.version = version;
        }
        lastPage = page;
        lastSlot = slot;
        return slot;
    }

    // pages are white with coverage in alpha, so whether setPixel wants argb or abgr makes no difference to them
    private static void copy(AtlasPage page, DynamicTexture texture) {
        NativeImage image = texture.getPixels();
        int[] argb = ((DataBufferInt) page.image().getRaster().getDataBuffer()).getData();
        copy(argb, page.width(), page.height(), image);
        texture.upload();
    }

    // setPixel takes argb (setPixelABGR is the other one), which is what the map colours are
    private static void copy(int[] argb, int w, int h, NativeImage image) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                image.setPixel(x, y, argb[row + x]);
            }
        }
    }

    // what to bind for a picture, uploaded on first sight and again whenever its version moved
    ResourceLocation texture(PixelImage picture) {
        Slot slot = images.get(picture);
        if (slot == null) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("soprano", "gui/picture_" + pictures++);
            NativeImage pixels = new NativeImage(picture.width(), picture.height(), true);
            DynamicTexture tex = picture.isSmooth() ? new SmoothTexture(pixels) : new DynamicTexture(pixels);
            Minecraft.getInstance().getTextureManager().register(rl, tex);
            if (picture.isSmooth()) {
                // a smooth picture gets drawn a bit off its baked size (a dock tile mid magnification), nearest would
                // make its round corners crawl. clamped so the linear sample doesn't pull in the opposite edge
                tex.setFilter(true, false);
                tex.setClamp(true);
            } else {
                // nearest like the pages, the map is drawn at its own size
                tex.setFilter(false, false);
            }
            slot = new Slot(tex, rl);
            images.put(picture, slot);
        }
        int version = picture.version();
        if (slot.version != version) {
            copy(picture.pixels(), picture.width(), picture.height(), slot.texture.getPixels());
            slot.texture.upload();
            slot.version = version;
        }
        return slot.location;
    }

    static void releaseAll() {
        if (instance != null) {
            instance.release();
        }
    }

    private void release() {
        Minecraft mc = Minecraft.getInstance();
        for (Slot slot : slots.values()) {
            try {
                mc.getTextureManager().release(slot.location);
            } catch (Throwable t) {
                Safe.report("gui texture release", t);
            }
        }
        for (Slot slot : images.values()) {
            try {
                mc.getTextureManager().release(slot.location);
            } catch (Throwable t) {
                Safe.report("gui texture release", t);
            }
        }
        slots.clear();
        images.clear();
        lastPage = null;
        lastSlot = null;
    }

    // guiTextured sets nearest on its texture before every batch it draws, so a linear filter set once at upload
    // lasts exactly zero frames. this one just says no
    private static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(NativeImage pixels) {
            super(pixels);
        }

        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, false);
        }
    }

    // one picture that is done for good (a dock tile baked at a size the window no longer has). released a couple of
    // frames late: this frame may already have queued a blit of it, and a released name draws as the missing texture
    static void release(PixelImage picture) {
        if (instance == null || picture == null) {
            return;
        }
        Slot slot = instance.images.remove(picture);
        if (slot != null) {
            Minecraft mc = Minecraft.getInstance();
            mc.schedule(() -> mc.schedule(() -> {
                try {
                    mc.getTextureManager().release(slot.location);
                } catch (Throwable t) {
                    Safe.report("gui texture release", t);
                }
            }));
        }
    }
}
