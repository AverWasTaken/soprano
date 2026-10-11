package adris.altoclef.ui.gui.facade;

import java.util.List;

// what the get tab can ask for. "get" is altoclef's TaskCatalogue (it crafts, smelts and mines its way there),
// "mine" is baritone's mine process (blocks only, no count means keep going)
public interface ItemsAccess {

    // name is what the command takes ("oak_log"), iconItemId is a registry id for the icon ("minecraft:oak_log"),
    // maxStack is the stack size shift-click steps by
    record ItemEntry(String name, String iconItemId, int maxStack) {
    }

    record BasketLine(String name, int count) {
    }

    List<ItemEntry> gettable();

    List<ItemEntry> mineable();

    Result get(List<BasketLine> lines);

    // count 0 means "no limit"
    Result mine(List<BasketLine> lines);
}
