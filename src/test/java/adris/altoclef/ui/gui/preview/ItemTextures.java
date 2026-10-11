package adris.altoclef.ui.gui.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

// flat item and block textures read straight out of the minecraft client jar on the test classpath, so the
// preview pngs show pixel art instead of grey tiles. nothing is copied anywhere: this only runs in tests, and
// a texture that is not there (3d models, entity blocks, a jar that is not on the classpath) just means null
final class ItemTextures {

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static final BufferedImage MISSING = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    // where a block's flat face lives, in the order a player would recognise it
    private static final String[] BLOCK_SUFFIXES = {"", "_front", "_top", "_side", "_still"};

    private ItemTextures() {
    }

    static BufferedImage get(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        BufferedImage cached = CACHE.get(itemId);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        String path = itemId.substring(itemId.indexOf(':') + 1);
        BufferedImage found = load("/assets/minecraft/textures/item/" + path + ".png");
        for (int i = 0; found == null && i < BLOCK_SUFFIXES.length; i++) {
            found = load("/assets/minecraft/textures/block/" + path + BLOCK_SUFFIXES[i] + ".png");
        }
        if (found == null && path.endsWith("_spawn_egg")) {
            found = load("/assets/minecraft/textures/item/spawn_egg.png");
        }
        // animated textures are a vertical strip of frames, the first one is the icon
        if (found != null && found.getHeight() > found.getWidth()) {
            found = found.getSubimage(0, 0, found.getWidth(), found.getWidth());
        }
        CACHE.put(itemId, found == null ? MISSING : found);
        return found;
    }

    private static BufferedImage load(String resource) {
        try (InputStream in = ItemTextures.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
