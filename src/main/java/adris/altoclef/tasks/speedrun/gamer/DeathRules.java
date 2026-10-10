package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// the pure half of "was that new player instance a death". a respawn swaps the LocalPlayer and so does a portal, so the swap
// alone says nothing. three things can say it did: the death packet (the stash), our own tick catching the player dying, or
// the old instance, which keeps its zero health after the swap. a dimension change is not a signal either way: a nether death
// respawns in the overworld, and a portal is a swap with all three quiet
public final class DeathRules {

    private DeathRules() {
    }

    // where the record of the death comes from, best first
    public enum Source {
        // the death packet: the earliest sample, taken before the body could slide or the damage source expire
        STASH,
        // our own tick saw the player dying and wrote it down
        TICK,
        // nobody saw it, the old instance is dead though
        OLD_INSTANCE,
        // a portal
        NONE
    }

    public static Source source(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        if (stashed) {
            return Source.STASH;
        }
        if (tickSawDeath) {
            return Source.TICK;
        }
        return oldInstanceDead ? Source.OLD_INSTANCE : Source.NONE;
    }

    public static boolean seen(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        return source(tickSawDeath, stashed, oldInstanceDead) != Source.NONE;
    }

    // ---- is the pile worth the walk

    // deaths this close together are the same spot: the same cave, the same ravine
    public static final double SAME_SPOT_BLOCKS = 48;
    // a bag with only a pick in it is worth a short walk and not a long one, the wood and cobble are minutes away anywhere
    public static final double NEAR_BLOCKS = 300;
    // an iron pick is three ingots, less than that is not a pile
    public static final int IRON_WORTH = 3;

    private static final Item[] IRON_OR_BETTER = {
            Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_SHOVEL, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
            Items.DIAMOND_SWORD, Items.DIAMOND_SHOVEL, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
    };
    private static final Item[] PICKS = {Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};

    // what the bag held the last time we saw it alive, which is what the pile is made of. it is read every tick and kept: the
    // body that shows up after the respawn has an empty bag, and the old one is not guaranteed to still have its slots
    public record Worth(int iron, int gear, boolean pick, int food) {

        // iron to smelt, iron tools or armor, or enough food to skip a hunt: the pile is the point of the trip
        public boolean rich(int foodLine) {
            return iron >= IRON_WORTH || gear > 0 || food >= foodLine;
        }

        public boolean worthIt(double blocks, int foodLine) {
            return rich(foodLine) || (pick && blocks <= NEAR_BLOCKS);
        }

        // what was in it, in a few words for the log
        public String words() {
            List<String> parts = new ArrayList<>();
            if (iron > 0) {
                parts.add(iron + " iron");
            }
            if (gear > 0) {
                parts.add(gear + " iron or better " + (gear == 1 ? "piece" : "pieces"));
            }
            if (pick) {
                parts.add("a pick");
            }
            if (food > 0) {
                parts.add(food + " food");
            }
            return parts.isEmpty() ? "nothing much" : String.join(", ", parts);
        }
    }

    public static Worth worth(GamerFacts f) {
        int gear = 0;
        for (Item item : IRON_OR_BETTER) {
            if (f.count(item) > 0 || f.armorEquipped(item)) {
                gear++;
            }
        }
        return new Worth(f.count(Items.RAW_IRON, Items.IRON_INGOT), gear, f.count(PICKS) > 0, f.foodUnits());
    }

    // earlier deaths of this run (same dimension) within SAME_SPOT_BLOCKS of this one
    public static int killedHere(List<RunState.Death> before, Dimension dimension, double x, double z) {
        int n = 0;
        for (RunState.Death d : before) {
            if (d.dimension == null || !d.dimension.equals(dimension.name())) {
                continue;
            }
            double dx = d.x - x;
            double dz = d.z - z;
            if (dx * dx + dz * dz <= SAME_SPOT_BLOCKS * SAME_SPOT_BLOCKS) {
                n++;
            }
        }
        return n;
    }

    // null = go back for it, else why not, in the words the log gets. a null `worth` is a bag we never got to see: go.
    // a spot that has killed us before is not walked into again however rich the pile is, that is how the same zombies get you
    // twice. (a death on the way to an older pile ends that trip too, GamerTask says so, but the new pile is judged on its own)
    public static String refuse(boolean sameDimension, double blocks, int reach, Worth worth, int foodLine, int killedHere) {
        if (!sameDimension) {
            return "it is in another dimension";
        }
        if (killedHere > 0) {
            return "this spot has killed us " + (killedHere + 1) + " times";
        }
        if (blocks > reach) {
            return Math.round(blocks) + " blocks is further than the " + reach + " we walk";
        }
        if (worth != null && !worth.worthIt(blocks, foodLine)) {
            return "the bag was nearly empty (" + worth.words() + ")";
        }
        return null;
    }

    public static String going(double blocks, Worth worth) {
        return "death: going back for the pile, " + Math.round(blocks) + " blocks" + (worth == null ? "" : ", " + worth.words());
    }

    public static String leaving(String why) {
        return "death: leaving the pile, " + why;
    }
}
