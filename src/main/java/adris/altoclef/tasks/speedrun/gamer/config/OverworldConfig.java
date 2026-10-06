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
            new KitItem("flint_and_steel", 1), new KitItem("shield", 1), new KitItem("shears", 1),
            // sticks only, no iron. for ladder clutching on the long falls in the nether (one ladder gets picked back up)
            new KitItem("ladder", 3)));
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
    // village beds, while we are short of beds: how far away a seen bed may be, and how close to a job block, a bell or
    // another bed it has to sit to count as a village's. a bed is a bed, no wool and no shears needed for it
    public int villageBedRadius = 48;
    public int villageBedEvidenceRadius = 16;
    // per run: this long in total spent on beds, and this long on any one of them before it is written off
    public double villageBedSeconds = 120;
    public double villageBedEachSeconds = 20;

    // casting with no lava in sight for this long, while we already hold a diamond pickaxe, flips to obsidian early.
    // without the pickaxe we keep wandering for a lake until castGiveUpMinutes
    public double noLavaSeconds = 150;
    // throwaway blocks to carry through the portal (pillaring, bridging, plugging lava)
    public int portalBuildBlocks = 32;
    // pick up a crafting table or furnace of ours (we hold none) when the run moves on to another need, but only when it
    // is this close. only spots we recorded are candidates and tablePickupSeconds bounds the trip, so it can be generous,
    // the 10 it was lost a table to a few seconds of sprinting after a pig
    public int tableRecoverRadius = 24;
    // ...and gets this long to do it before that station is written off
    public double tablePickupSeconds = 30;
    // a station that was open or placed this recently is in use, leave it be. just a debounce now (the need boundary does
    // the real deciding), it used to be 30 and the bot was long out of range by the time it ran out
    public double tableUseCooldownSeconds = 3;
    // after taking one back, no second pickup of that kind for this long. the backstop against a place/pickup loop
    public double tableRecoverCooldownSeconds = 120;

    // smelting in the background (see SmeltFiller): the iron goes in the furnace and the bot does other things while it cooks.
    // it stays this close to the furnace (horizontal), less if the server's simulation distance is smaller, because a
    // furnace in a chunk that does not tick does not cook
    public int furnaceLeashBlocks = 64;
    // within this of "done" counts as done: a boundary goes back for it, and at the furnace we wait instead of leaving
    public double furnaceWaitSeconds = 10;
    // a job older than this is from a world that ran on without us (a relog days later), not worth a walk
    public double furnaceStaleSeconds = 1800;
    // pulled back to the furnace this many times by the leash and we stop wandering and wait there. what the filler wants
    // is out of range, so more trips would be the same trip
    public int furnaceMaxPullbacks = 3;
    // planks to stock for beds while waiting (3 a bed, the shield and sticks come on top of this)
    public int smeltBedPlanks = 12;
    // stock-up for when everything else is done and the iron is still cooking. one entry each: "food" = units over
    // targetFoodUnits, "wool_beds" = extra beds worth of wool, "build_blocks" = over portalBuildBlocks, "log" = logs to hold
    public List<KitItem> smeltExtras = new ArrayList<>(List.of(
            new KitItem("food", 30), new KitItem("wool_beds", 3), new KitItem("build_blocks", 32), new KitItem("log", 8)));

    // iron golem hunt (the iron need only): 100 hp, 3 to 5 ingots, and it cannot hit a player whose feet are above its
    // head, so we stack a pillar next to it and poke it from there. see GolemRules for the vanilla numbers
    public int golemHuntRadius = 32;
    // pillar blocks we must carry (the golem hunt eats 3 to 5), and the health we want before picking this fight
    public int golemMinBlocks = 5;
    public double golemMinHealth = 14;
    // feet this far above the golem's head. golem is 2.7 tall and we stand on whole blocks so 0.3 means "3 blocks up"
    public double golemSafeMargin = 0.3;
    // never stack more than this (a golem down a hole is not worth a tower)
    public int golemMaxPillar = 5;
    // reach is checked from the eye with the vanilla 3.0, anything the golem walks off to for longer than this is a lost cause
    public double golemOutOfReachSeconds = 5;
    // the fight itself, the walk up to it, and the pillar, each with its own clock
    public double golemFightSeconds = 45;
    public double golemApproachSeconds = 40;
    public double golemPillarSeconds = 20;
    // below this we stop swinging and just sit on the pillar until it is safe to leave
    public double golemAbortHealth = 8;
    // once we have hit it, we stay up this long after the last hit (anger lasts 20 to 39 s) before walking down
    public double golemCalmSeconds = 45;
    // other monsters this close and the hunt does not start (or ends)
    public int golemHostileRadius = 16;
    // fights per run, a golem that got away twice is not going to be easier the third time
    public int golemMaxAttempts = 2;
}
