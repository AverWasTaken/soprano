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

    // lava casting gets this long (counted from when the cast starts, after the prep) before the portal phase goes the
    // obsidian way. the whole portal phase has 14 minutes and the prep before the cast eats some of them, 7 leaves the
    // obsidian way a real chunk of the budget (it may need a diamond pickaxe first, that is slow)
    public double castGiveUpMinutes = 7;
    // ruined portal chests are only looted when we have already seen one this close, never searched for
    public int ruinedPortalLootRadius = 60;
    // a chest that is not emptied after this long gets written off (blocked, buried, whatever)
    public double lootChestSeconds = 60;
    // village blacksmith chests (the iron need only): how far away a seen chest may be, and how close to a grindstone,
    // smithing table or blast furnace it has to sit to count as a blacksmith's
    public int villageLootRadius = 48;
    public int villageChestJobRadius = 6;
    // per run, not per phase: at most this many chests and this many seconds spent in them, then we mine like normal
    public int villageLootMaxChests = 3;
    public double villageLootSeconds = 240;

    // casting with no lava in sight for this long, while we already hold a diamond pickaxe, flips to obsidian early.
    // without the pickaxe we keep wandering for a lake until castGiveUpMinutes
    public double noLavaSeconds = 150;
    // throwaway blocks to carry through the portal (pillaring, bridging, plugging lava)
    public int portalBuildBlocks = 32;
    // pick up a crafting table lying around (we hold none) only when it is this close
    public int tableRecoverRadius = 10;
    // ...and gets this long to do it before that table is written off
    public double tablePickupSeconds = 30;
    // a table that was open or placed this recently is in use (a craft is about to happen at it), leave it be
    public double tableUseCooldownSeconds = 30;
    // after taking one back, no second pickup for this long. the backstop against a place/pickup loop
    public double tableRecoverCooldownSeconds = 120;
}
