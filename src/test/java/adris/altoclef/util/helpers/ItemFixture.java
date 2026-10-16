package adris.altoclef.util.helpers;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
        // every item tag in the jar, not just the tool ones: FuelValues.vanillaBurnTimes walks logs, planks, wool and
        // friends, and an unbound tag throws there
        Map<TagKey<Item>, List<Holder<Item>>> tags = new HashMap<>();
        for (String path : tagPaths()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace(path));
            tags.put(tag, new ArrayList<>(read(tag, new HashMap<>())));
        }
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, tags)).apply();
        loaded = true;
    }

    private static List<String> tagPaths() {
        String prefix = "data/minecraft/tags/item/";
        List<String> out = new ArrayList<>();
        try (ZipFile jar = new ZipFile(new File(Item.class.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            jar.stream().map(ZipEntry::getName).filter(n -> n.startsWith(prefix) && n.endsWith(".json"))
                    .forEach(n -> out.add(n.substring(prefix.length(), n.length() - ".json".length())));
        } catch (Exception e) {
            throw new IllegalStateException("could not list the item tags in the jar", e);
        }
        return out;
    }

    // tags pull in other tags with a #, so resolve those all the way down. optional entries for items that don't exist
    // just get skipped, same as the real loader
    private static Set<Holder<Item>> read(TagKey<Item> tag, Map<Identifier, Set<Holder<Item>>> seen) {
        Set<Holder<Item>> done = seen.get(tag.location());
        if (done != null) {
            return done;
        }
        String path = "/data/" + tag.location().getNamespace() + "/tags/item/" + tag.location().getPath() + ".json";
        Set<Holder<Item>> out = new LinkedHashSet<>();
        seen.put(tag.location(), out);
        try (Reader reader = new InputStreamReader(Item.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            for (JsonElement entry : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                boolean required = true;
                String name;
                if (entry.isJsonObject()) {
                    name = entry.getAsJsonObject().get("id").getAsString();
                    required = !entry.getAsJsonObject().has("required") || entry.getAsJsonObject().get("required").getAsBoolean();
                } else {
                    name = entry.getAsString();
                }
                if (name.startsWith("#")) {
                    out.addAll(read(TagKey.create(Registries.ITEM, Identifier.parse(name.substring(1))), seen));
                    continue;
                }
                Optional<Holder.Reference<Item>> item = BuiltInRegistries.ITEM.get(Identifier.parse(name));
                if (item.isPresent()) {
                    out.add(item.get());
                } else if (required) {
                    throw new IllegalStateException(name + " in tag " + tag.location() + " is not an item");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not read tag " + tag.location(), e);
        }
        return out;
    }
}
