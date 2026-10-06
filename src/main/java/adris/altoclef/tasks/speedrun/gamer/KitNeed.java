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
        return switch (catalogueName) {
            case "iron_ingot", "wool", FOOD, BUILD_BLOCKS -> true;
            default -> false;
        };
    }
}
