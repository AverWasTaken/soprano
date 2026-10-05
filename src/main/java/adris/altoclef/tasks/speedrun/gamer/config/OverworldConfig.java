package adris.altoclef.tasks.speedrun.gamer.config;

import java.util.ArrayList;
import java.util.List;

// owner: overworld prep worker (GATHER, IRON, PORTAL). add knobs here, not in GamerConfig
public class OverworldConfig {
    // catalogue names ("iron_pickaxe", "bucket"), counts are "hold this many in total"
    public static class KitItem {
        public String item;
        public int count = 1;

        public KitItem() {
        }

        public KitItem(String item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    public enum ArmorPlan {
        // 24 iron: what keeps a bed explosion survivable (see gamer-design.md 0.6)
        FULL_IRON,
        CHEST_HELMET,
        NONE
    }

    public List<KitItem> starterKit = new ArrayList<>(List.of(
            new KitItem("stone_pickaxe", 1), new KitItem("stone_sword", 1), new KitItem("furnace", 1)));
    // armor comes from armorPlan, wool from end.beds
    public List<KitItem> ironKit = new ArrayList<>(List.of(
            new KitItem("iron_pickaxe", 1), new KitItem("iron_sword", 1), new KitItem("bucket", 2),
            new KitItem("flint_and_steel", 1), new KitItem("shield", 1), new KitItem("shears", 1)));
    public ArmorPlan armorPlan = ArmorPlan.FULL_IRON;

    // nutrition points, a cooked steak is 8. enough for an hour or so, the food chain eats on its own
    public int minFoodUnits = 70;
    public int targetFoodUnits = 100;

    // lava casting gets this long before the portal phase mines obsidian instead
    public double castGiveUpMinutes = 9;
    public int obsidianNeeded = 10;
    // ruined portal chests are only looted when we have already seen one this close, never searched for
    public int ruinedPortalLootRadius = 60;
}
