package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Icon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the first page of the settings tab: the 41 settings people actually touch, in six sections laid out four to a row, most used
// first. travel leads because fast mode and boats are the two switches people go looking for. the sentences are the
// tooltips for these 41, written so a kid can read them; everything else uses the generated javadoc description.
// all the hand written words live here and nowhere else
public final class SettingsHome {

    public record Entry(String name, Icon icon, String sentence) {
    }

    public record Section(String title, List<Entry> entries) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("Travel", List.of(
                    e("fastMode", Icon.BOLT, "Moves like a fast player: diagonal jumps, swimming, boats and other quick tricks all switch on. Takes more risks."),
                    e("allowBoats", Icon.BOAT, "Rows across long stretches of water in a boat. Needs a boat in your inventory."),
                    e("fastModeMinHealth", Icon.WARNING, "With fast mode on it may take a fall that hurts, but never one that leaves you below this. Counted in half hearts, so 12 is six hearts."),
                    e("boatPickup", Icon.BACKPACK, "Breaks the boat and picks it back up on the far side, so you keep it."),
                    e("elytraAutoJump", Icon.LEVEL_UP, "When you start an elytra trip on the ground, it walks to a ledge and jumps off by itself."),
                    e("elytraConserveFireworks", Icon.CLOUD, "Glides down without fireworks when the path goes down. Saves rockets, flies a bit slower."),
                    e("elytraAutoSwap", Icon.ROTATE_RIGHT, "Puts on a fresh elytra from your inventory before the one you wear breaks."),
                    e("allowSwimming", Icon.WATER, "Swims through water like a player instead of walking along the bottom."))),
            new Section("Movement", List.of(
                    e("allowSprint", Icon.RUN, "Runs instead of walking, so it gets there faster. Uses up food quicker."),
                    e("allowParkour", Icon.PARKOUR, "Jumps over holes instead of walking around them. Faster, but sometimes it misses."),
                    e("maxFallHeightNoWater", Icon.LEVEL_DOWN, "How many blocks it will drop down without water. 3 never hurts. More is faster but costs health."),
                    e("freeLook", Icon.PLAYER, "You can move your camera while it walks. Turn it off to watch where it's going."),
                    e("allowWaterBucketFall", Icon.WATER, "Drops from high up and puts water down to land in. Needs a water bucket."),
                    e("allowLadderClutch", Icon.LADDER, "Saves a long fall by placing a ladder or vine on a wall at the last second. Needs one in the hotbar."),
                    e("allowParkourPlace", Icon.BLOCK, "Places a block in mid air to land on when a jump is too wide. Needs parkour on."),
                    e("allowDiagonalDescend", Icon.LEVEL_DOWN, "Drops down diagonally instead of in straight steps. A little faster, a little riskier."))),
            new Section("Mining and building", List.of(
                    e("allowBreak", Icon.PICKAXE, "Digs through blocks that are in the way. Turn it off to leave the world as it is."),
                    e("allowPlace", Icon.BLOCK, "Puts down blocks to build little bridges and stairs."),
                    e("autoTool", Icon.WRENCH, "Picks the right tool from your hotbar, like a pickaxe for stone."),
                    e("allowInventory", Icon.BACKPACK, "Moves tools and blocks from your inventory into your hotbar when it needs them."),
                    e("legitMine", Icon.EYE, "Only mines ores it can actually see, like a normal player. Slower to find them."),
                    e("allowDownward", Icon.LEVEL_DOWN, "Digs the block under its feet. Off, it digs staircases instead of straight shafts."),
                    e("mineScanDroppedItems", Icon.GET, "While mining, it also walks over to pick up dropped items of what it is mining."),
                    e("buildInLayers", Icon.SCHEMATICS, "Builds one layer of a schematic before starting the next one."),
                    // the one odd tile out: the owner asked for this switch on the first page, and crafting sits closest to building
                    e("altoShowInventoryWhenCrafting", Icon.CRAFT, "Opens your inventory while it crafts in the little 2x2 grid, so you can watch. Turn it off to craft without the screen."))),
            new Section("Combat and safety", List.of(
                    e("altoMobDefense", Icon.ENTITY, "Runs from creepers and scary mobs, and pushes mobs away when it's hurt."),
                    e("altoAutoEat", Icon.POWER, "Eats food from your inventory when you get hungry or are in danger."),
                    e("altoForceFieldStrategy", Icon.RING, "How it swings at mobs that get close. Smart hits the closest one now and then. Off never swings."),
                    e("altoDodgeProjectiles", Icon.MOVE, "Steps out of the way of arrows and fireballs. Needs Mob Defense."),
                    e("avoidance", Icon.PEOPLE, "Walks around mobs instead of past them. Can make the game stutter for a moment."),
                    e("altoMinimumFoodAllowed", Icon.BACKPACK, "Keeps at least this much food in the inventory and goes to get more when it runs low. 0 means it never goes looking on its own."),
                    e("altoCommitCombat", Icon.PIN, "Once it starts a fight it keeps going until the mob is dead. Once it runs away, it runs far enough to be safe."),
                    e("mobAvoidanceRadius", Icon.PEOPLE, "How many blocks it stays away from mobs when Avoidance is on."))),
            new Section("Rendering", List.of(
                    e("renderPath", Icon.EYE, "Draws a line on the ground where it plans to walk."),
                    e("renderGoal", Icon.PIN, "Draws a box where it's trying to get to."),
                    e("colorCurrentPath", Icon.EYE, "The colour of the line it's walking on right now."),
                    e("colorNextPath", Icon.EYE, "The colour of the line it will walk on next."))),
            new Section("Chat and HUD", List.of(
                    e("chatControl", Icon.HELP, "Lets you type # commands in chat to control it."),
                    e("prefix", Icon.PUZZLE, "The symbol you type in front of a chat command, like #goto."),
                    e("altoShowTaskChains", Icon.STATS, "Shows what it is doing in the top left corner while a task runs."),
                    e("altoHudScale", Icon.ACCESSIBILITY, "How big the task list in the corner is. 1 is normal, 0.75 is small.")))
    );

    private static final Map<String, Entry> BY_NAME = new HashMap<>();

    static {
        for (Section s : SECTIONS) {
            for (Entry en : s.entries()) {
                BY_NAME.put(en.name(), en);
            }
        }
    }

    private SettingsHome() {
    }

    private static Entry e(String name, Icon icon, String sentence) {
        return new Entry(name, icon, sentence);
    }

    // null for a setting that is not on the first page
    public static Entry entry(String name) {
        return BY_NAME.get(name);
    }
}
