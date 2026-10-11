package adris.altoclef.ui.gui.mc;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.Wording;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// get and mine, both by running the very command you would have typed, so the gui can never do something chat can't
public final class McItems implements ItemsAccess {

    // never minable, whatever the item form says. the destroy time check catches most of these already, this is the
    // belt for the ones that have an item and a hardness of zero
    private static final Set<String> NOT_MINEABLE = Set.of(
            "bedrock", "barrier", "light", "structure_void", "structure_block", "jigsaw", "end_portal_frame",
            "end_portal", "end_gateway", "nether_portal", "moving_piston", "piston_head", "fire", "soul_fire", "water",
            "lava", "air", "cave_air", "void_air", "command_block", "chain_command_block", "repeating_command_block",
            "reinforced_deepslate"
    );

    private final CommandsAccess runner;

    // built once, the registries do not change under a running game
    private volatile List<ItemEntry> gettable;
    private volatile List<ItemEntry> mineable;

    public McItems() {
        this(new McCommands());
    }

    McItems(CommandsAccess runner) {
        this.runner = runner;
    }

    @Override
    public List<ItemEntry> gettable() {
        List<ItemEntry> out = gettable;
        if (out != null) {
            return out;
        }
        try {
            List<String> names = new ArrayList<>(TaskCatalogue.resourceNames());
            names.sort(Comparator.naturalOrder());
            out = new ArrayList<>(names.size());
            for (String name : names) {
                out.add(new ItemEntry(name, Wording.soprano(displayOf(name)),
                        iconOrUnknown(IconResolver.forResource(name)), stackOf(name)));
            }
            gettable = List.copyOf(out);
            return gettable;
        } catch (Throwable t) {
            Safe.report("gui gettable", t);
            return List.of();
        }
    }

    @Override
    public List<ItemEntry> mineable() {
        List<ItemEntry> out = mineable;
        if (out != null) {
            return out;
        }
        try {
            List<ItemEntry> built = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (!isMineable(block)) {
                    continue;
                }
                String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
                built.add(new ItemEntry(path, Wording.soprano(nameOf(block.asItem(), path)),
                        iconOrUnknown(IconResolver.forBlock(block)),
                        Math.max(1, block.asItem().getDefaultMaxStackSize())));
            }
            built.sort(Comparator.comparing(ItemEntry::name));
            mineable = List.copyOf(built);
            return mineable;
        } catch (Throwable t) {
            Safe.report("gui mineable", t);
            return List.of();
        }
    }

    static boolean isMineable(Block block) {
        if (block.asItem() == Items.AIR) {
            return false;
        }
        // -1 is the game's way of saying unbreakable
        if (block.defaultDestroyTime() < 0) {
            return false;
        }
        return !NOT_MINEABLE.contains(BuiltInRegistries.BLOCK.getKey(block).getPath());
    }

    // an item speaks for a catalogue name when it is the item of that name, or the only one in it. "planks" is a
    // whole group, so it reads "Planks" and not "Oak Planks"
    private static String displayOf(String catalogueName) {
        Item[] matches = TaskCatalogue.getItemMatches(catalogueName);
        for (Item item : matches) {
            if (BuiltInRegistries.ITEM.getKey(item).getPath().equals(catalogueName)) {
                return nameOf(item, catalogueName);
            }
        }
        return matches.length == 1 ? nameOf(matches[0], catalogueName) : ItemEntry.titleCase(catalogueName);
    }

    private static String nameOf(Item item, String fallbackId) {
        try {
            String name = item.getName().getString();
            return name.isEmpty() ? ItemEntry.titleCase(fallbackId) : name;
        } catch (Throwable t) {
            Safe.report("gui item name", t);
            return ItemEntry.titleCase(fallbackId);
        }
    }

    private static int stackOf(String catalogueName) {
        Item[] matches = TaskCatalogue.getItemMatches(catalogueName);
        return matches.length == 0 ? 64 : Math.max(1, matches[0].getDefaultMaxStackSize());
    }

    // the canvas needs something to draw, a barrier is the game's own "no such thing"
    private static String iconOrUnknown(String id) {
        return id == null ? "minecraft:barrier" : id;
    }

    @Override
    public Result get(List<BasketLine> lines) {
        try {
            String bad = check(lines);
            if (bad != null) {
                return Result.fail(bad);
            }
            String line = getLine(lines);
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(sentence(r.message()), line);
            }
            return Result.ok("Getting " + describe(lines), line);
        } catch (Throwable t) {
            Safe.report("gui get", t);
            return Result.fail("Could not start that.");
        }
    }

    @Override
    public Result mine(List<BasketLine> lines) {
        try {
            if (lines == null || lines.isEmpty()) {
                return Result.fail("Add a block first.");
            }
            for (BasketLine l : lines) {
                if (!cleanName(l.name())) {
                    return Result.fail("\"" + l.name() + "\" is not a block name.");
                }
            }
            String line = mineLine(lines);
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(sentence(r.message()), line);
            }
            return Result.ok(mineMessage(lines), line);
        } catch (Throwable t) {
            Safe.report("gui mine", t);
            return Result.fail("Could not start that.");
        }
    }

    // "get oak_log 64 iron_ingot 3": the same name twice adds up in the parser, so it can go in as is
    static String getLine(List<BasketLine> lines) {
        StringBuilder sb = new StringBuilder("get");
        for (BasketLine l : lines) {
            sb.append(' ').append(l.name().trim().toLowerCase(Locale.ROOT)).append(' ').append(l.count());
        }
        return sb.toString();
    }

    // "mine 64 iron_ore coal_ore": baritone takes one count for the whole lot, the first line's. 0 is no limit
    static String mineLine(List<BasketLine> lines) {
        StringBuilder sb = new StringBuilder("mine");
        int count = lines.get(0).count();
        if (count > 0) {
            sb.append(' ').append(count);
        }
        for (String name : mineNames(lines)) {
            sb.append(' ').append(name);
        }
        return sb.toString();
    }

    private static Set<String> mineNames(List<BasketLine> lines) {
        Set<String> names = new LinkedHashSet<>();
        for (BasketLine l : lines) {
            names.add(l.name().trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String mineMessage(List<BasketLine> lines) {
        String names = String.join(", ", mineNames(lines));
        int count = lines.get(0).count();
        // baritone counts the drops of every listed block together
        return count > 0 ? "Mining " + names + " until you have " + count + " in total" : "Mining " + names;
    }

    // null when the basket can go out as it is
    private static String check(List<BasketLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "Add something to the basket first.";
        }
        for (BasketLine l : lines) {
            if (!cleanName(l.name())) {
                return "\"" + l.name() + "\" is not an item name.";
            }
            if (l.count() < 1) {
                return "Pick at least 1 of " + l.name() + ".";
            }
        }
        return null;
    }

    // one word, nothing the item list parser would split on
    private static boolean cleanName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (Character.isWhitespace(c) || c == ',' || c == '[' || c == ']') {
                return false;
            }
        }
        return true;
    }

    // "64 oak_log", "64 oak_log and 3 iron_ingot", "64 oak_log, 3 iron_ingot and 2 more"
    static String describe(List<BasketLine> lines) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (BasketLine l : lines) {
            merged.merge(l.name().trim().toLowerCase(Locale.ROOT), l.count(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        merged.forEach((name, count) -> parts.add(count + " " + name));
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (parts.size() == 2) {
            return parts.get(0) + " and " + parts.get(1);
        }
        int more = parts.size() - 2;
        return parts.get(0) + ", " + parts.get(1) + " and " + more + " more";
    }

    // command exceptions are lowercase ("no task for that"), toasts are sentences
    static String sentence(String message) {
        if (message == null || message.isEmpty()) {
            return "That did not work.";
        }
        // soprano first: "altoclef is not up yet" has to become a sentence about soprano
        String said = Wording.soprano(message);
        char first = said.charAt(0);
        return Character.isLowerCase(first) ? Character.toUpperCase(first) + said.substring(1) : said;
    }
}
