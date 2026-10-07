package adris.altoclef.tasks.speedrun.gamer;

// "hold this many of this catalogue item in total", already net of what we own so a phase can hand it straight to the
// catalogue. the three special names are not catalogue items, KitRunner turns them into their own tasks
public record KitNeed(String catalogueName, int count) {
    public static final String FOOD = "food";
    public static final String BUILD_BLOCKS = "build_blocks";
    public static final String EQUIP_ARMOR = "equip_armor";

    public boolean isSpecial() {
        return FOOD.equals(catalogueName) || BUILD_BLOCKS.equals(catalogueName) || EQUIP_ARMOR.equals(catalogueName);
    }

    // true for needs that are about walking around and mining instead of standing at a crafting table
    public boolean isGathering() {
        return isGatheringName(catalogueName);
    }

    // a catalogue item we make at a table (stone pickaxe, bucket, shears...): the table has to stay put until it is done
    public boolean isCraft() {
        return isCraftName(catalogueName);
    }

    public static boolean isGatheringName(String name) {
        return switch (name == null ? "" : name) {
            // flint, logs, planks and coal are the filler while iron cooks (SmeltFiller), none of them is made at a table.
            // cobblestone is the one trip's worth of stone the gather mines before the stone crafts, it is mined not crafted
            case "iron_ingot", "wool", FOOD, BUILD_BLOCKS, "flint", "log", "planks", "coal", "cobblestone" -> true;
            default -> false;
        };
    }

    public static boolean isCraftName(String name) {
        return name != null && !isGatheringName(name) && !EQUIP_ARMOR.equals(name);
    }
}
