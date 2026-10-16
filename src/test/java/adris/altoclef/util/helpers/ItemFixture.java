package adris.altoclef.util.helpers;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// bootstrap leaves every item tag empty (the data pack fills those), and the sword/axe/armor checks are tags. this reads
// the vanilla tag files out of the jar and hands them to the registry, the way a world load does. components are set in
// the Item constructor on 1.21.11, so unlike 26.3 there's nothing to bind for those
public final class ItemFixture {
    private static boolean loaded;

    private ItemFixture() {
    }

    // the one place tests boot the game from: version, bootstrap, then the components and tags above
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        load();
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        Map<TagKey<Item>, List<Holder<Item>>> tags = new HashMap<>();
        for (TagKey<Item> tag : List.of(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.HOES,
                ItemTags.HEAD_ARMOR, ItemTags.CHEST_ARMOR, ItemTags.LEG_ARMOR, ItemTags.FOOT_ARMOR)) {
            tags.put(tag, read(tag));
        }
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, tags)).apply();
        loaded = true;
    }

    private static List<Holder<Item>> read(TagKey<Item> tag) {
        String path = "/data/" + tag.location().getNamespace() + "/tags/item/" + tag.location().getPath() + ".json";
        List<Holder<Item>> out = new ArrayList<>();
        try (Reader reader = new InputStreamReader(Item.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            for (JsonElement entry : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                Identifier id = Identifier.parse(entry.getAsString());
                out.add(BuiltInRegistries.ITEM.get(id).orElseThrow());
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read tag " + tag.location(), e);
        }
        return out;
    }
}
