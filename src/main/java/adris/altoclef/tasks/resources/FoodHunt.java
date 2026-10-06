package adris.altoclef.tasks.resources;

import java.util.List;

// which animal to chase for dinner. plain numbers in and out so a test can poke it without a game (CollectFoodTask has
// the world, this has the opinions). the old rule was "first type in the list that has any loaded animal", so a pig
// 80 blocks away beat a sheep 5 blocks away. get rekt, pig
public final class FoodHunt {
    private FoodHunt() {
    }

    // cooked nutrition (1.21.4) times the average raw drop count, from the loot tables (from memory, not from the jar):
    // pig and cow 1-3, chicken 1, sheep 1-2 mutton, cod and salmon 1. we cook everything, so cooked is what counts
    public enum Kind {
        PIG(8, 2.0, false),
        COW(8, 2.0, false),
        CHICKEN(6, 1.0, false),
        SHEEP(6, 1.5, false),
        COD(5, 1.0, true),
        SALMON(6, 1.0, true);

        final double units;
        final boolean fish;

        Kind(int cookedNutrition, double averageDrops, boolean fish) {
            this.units = cookedNutrition * averageDrops;
            this.fish = fish;
        }
    }

    // what a kill and the walk back cost on top of the walk there, in blocks. a chicken 5 blocks away (6 units over 13)
    // narrowly beats a pig 30 away (16 over 38), and a cow 10 away (16 over 18) beats a chicken 6 away (6 over 14)
    static final double OVERHEAD_BLOCKS = 8;
    // a block up or down is a lot more than a block along: it is pillaring or a long way round, not a straight walk
    static final double VERTICAL_WEIGHT = 2;
    // fish are in water: a boat-less swim, no knockback control, and they dart off. half a score is about right
    static final double FISH_PENALTY = 0.5;
    // the new best has to be this much better before we drop the animal we are already running at, or every pig that
    // wanders a block closer would turn us around
    static final double STICKY_FACTOR = 1.5;
    // sheep are also the wool source for the beds. killing one gives a wool and some mutton, shearing gives wool for
    // free, so while the kit still wants wool a sheep has to win by about this much to be eaten
    static final double WOOL_SHEEP_FACTOR = 1.3;

    // set by the gamer's iron phase. a static because the food task is a different task tree altogether
    private static volatile boolean woolWanted = false;

    public static void setWoolWanted(boolean wanted) {
        woolWanted = wanted;
    }

    public static boolean isWoolWanted() {
        return woolWanted;
    }

    // entity id, what it is, and how far it is by distance() below
    record Candidate(int id, Kind kind, double distance) {
    }

    static double distance(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * VERTICAL_WEIGHT;
    }

    static double score(Kind kind, double distance, boolean wool) {
        double score = kind.units / (distance + OVERHEAD_BLOCKS);
        if (kind.fish) {
            score *= FISH_PENALTY;
        }
        if (wool && kind == Kind.SHEEP) {
            score /= WOOL_SHEEP_FACTOR;
        }
        return score;
    }

    // the best candidate, except that the one we are already chasing (currentId, -1 for none) stays unless the best is
    // STICKY_FACTOR times better. null when there is nobody to chase
    static Candidate choose(List<Candidate> candidates, int currentId, boolean wool) {
        Candidate best = null;
        Candidate current = null;
        double bestScore = 0;
        double currentScore = 0;
        for (Candidate c : candidates) {
            double s = score(c.kind(), c.distance(), wool);
            if (best == null || s > bestScore) {
                best = c;
                bestScore = s;
            }
            if (c.id() == currentId) {
                current = c;
                currentScore = s;
            }
        }
        if (current != null && bestScore <= currentScore * STICKY_FACTOR) {
            return current;
        }
        return best;
    }
}
