package adris.altoclef.tasks.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// what #branchmine knows how to dig for. plain names and no Item or Block in here: the command table gets built from the title
// screen and in tests that never bootstrapped the game. the task turns the names into the real things once a world is up
public final class BranchOres {
    // the random bedrock layer tops out at y -60, feet at -59 always fit
    public static final int LOWEST_Y = -59;
    public static final int HIGHEST_Y = 318;

    public enum Tier {WOOD, STONE, IRON}

    // defaultY null means no good answer, emeralds live in mountains and that's about the player's world, not a number
    public record Ore(String name, List<String> aliases, String drop, List<String> blocks, Tier tier, Integer defaultY) {
        public boolean answersTo(String word) {
            return name.equals(word) || aliases.contains(word);
        }
    }

    private static final List<Ore> ORES = List.of(
            ore("diamond", List.of(), "diamond", Tier.IRON, -59),
            ore("redstone", List.of(), "redstone", Tier.IRON, -59),
            ore("raw_gold", List.of("gold"), "gold", Tier.IRON, -16),
            ore("lapis_lazuli", List.of("lapis"), "lapis", Tier.STONE, 0),
            ore("raw_iron", List.of("iron"), "iron", Tier.STONE, 16),
            ore("raw_copper", List.of("copper"), "copper", Tier.STONE, 48),
            ore("coal", List.of(), "coal", Tier.WOOD, 44),
            ore("emerald", List.of(), "emerald", Tier.IRON, null)
    );

    private BranchOres() {
    }

    // the catalogue name is the item id for all of these
    private static Ore ore(String name, List<String> aliases, String blockStem, Tier tier, Integer defaultY) {
        return new Ore(name, aliases, name, List.of(blockStem + "_ore", "deepslate_" + blockStem + "_ore"), tier, defaultY);
    }

    public static List<Ore> all() {
        return ORES;
    }

    public static Ore find(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (Ore ore : ORES) {
            if (ore.answersTo(w)) {
                return ore;
            }
        }
        return null;
    }

    // every spelling, for tab completion and the error line
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Ore ore : ORES) {
            out.add(ore.name());
            out.addAll(ore.aliases());
        }
        return out;
    }

    public static int clampY(int y) {
        return Math.max(LOWEST_Y, Math.min(HIGHEST_Y, y));
    }
}
