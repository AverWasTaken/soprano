package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import baritone.api.utils.Dimension;
import java.util.Set;
import java.util.function.BooleanSupplier;

// the pure half of "who counts as a foe here, and what sort". the commitment machine is the same in every dimension, this
// is where the overworld, the nether and the end differ: a few species that are somebody else's business, and a few that
// are not a fight. entity types come in as registry paths ("blaze") so the whole table can be tested without minecraft
public final class FoeRules {

    // lands 8 to 13 in one swing, a run at HEAVY_FLEE_HEALTH instead of the usual line
    private static final Set<String> HEAVY = Set.of("wither_skeleton", "hoglin", "zoglin", "piglin_brute", "vindicator",
            "ravager");
    // not a fight at any hp. the warden one shots a full bar, the wither is a boss we never summoned
    private static final Set<String> UNTOUCHABLE = Set.of("warden", "wither");

    private FoeRules() {
    }

    // one hostile as the world half sees it. the three questions that cost something (a raycast, a reachability verdict, the
    // behaviour stack) come in as suppliers and are only asked when the cheap ones did not already say no.
    // excluded: a task is fighting this one on its own terms (the blaze rods, the golem on its pillar, the dragon and whatever
    // stands around it) and does not want a second opinion. angry and canHarm are EntityHelper's answers. slimeSize is 0 for
    // anything that is not a slime
    public record Candidate(String type, int id, double distance, BooleanSupplier excluded, BooleanSupplier angry,
                            BooleanSupplier canHarm, long sinceHit, int slimeSize, boolean ranged, boolean creeper) {
    }

    // the foe this candidate makes in this dimension, null when it is not one
    public static Foe accept(Dimension dimension, Candidate c) {
        if (neverAFoe(dimension, c.type())) return null;
        // a size 1 slime cannot hurt us, it is a bouncing pet
        if (c.slimeSize() == 1) return null;
        if (c.excluded().getAsBoolean() || !c.angry().getAsBoolean()) return null;
        // something that hit us can hurt us, no questions. the rest has to be able to get at us (or shoot)
        if (c.sinceHit() > CombatCommit.HIT_MEMORY && !c.canHarm().getAsBoolean()) return null;
        Kind kind = kindOf(dimension, c.type());
        return new Foe(c.id(), c.distance(), shoots(c.type(), c.ranged(), kind), c.creeper(), c.sinceHit(), kind);
    }

    // does it count from where a shooter stands. a blaze shoots (the warden's sonic boom is handled by its kind). a wither
    // skeleton is a skeleton by class and a swordsman by trade: it only ever hits from where it stands next to us, and as a
    // "shooter" its first swing would never count as contact. classRanged is what the class says (MobReachability.isRanged)
    public static boolean shoots(String type, boolean classRanged, Kind kind) {
        return (classRanged && !type.equals("wither_skeleton")) || kind == Kind.FLYER;
    }

    // may the aura swing at this one. the fight target and what is hitting us in contact (brainSwingsAt), and anything a task
    // asked mob defense to stay out of but did not take off the aura's list: the rod task keeps blazes out of the chain and only
    // takes them off the aura beyond 3.5 blocks, so one next to us is the aura's to hit
    public static boolean auraMaySwingAt(boolean brainSwingsAt, boolean leftToATask) {
        return brainSwingsAt || leftToATask;
    }

    // species that the chain stays out of in this dimension. the ghast cannot be walked up to and its fireballs are the aura's
    // (and NetherPhase's cover), a fight or a run from it has nothing to chase and nowhere better to go. the dragon belongs to
    // DragonPhase from the first crystal to the exit portal, running from it is how the island ends up behind us
    public static boolean neverAFoe(Dimension dimension, String type) {
        return switch (dimension) {
            case NETHER -> type.equals("ghast");
            case END -> type.equals("ender_dragon");
            default -> false;
        };
    }

    // what the dragon tasks (the sword and the beds) fight, or stand among, on their own terms while they are live: the
    // dragon, and the endermen around the crystals. they add this to the behaviour stack so the chain neither fights nor runs
    // from them mid-fight. the type is the registry path
    public static boolean dragonFightOwns(String type) {
        return type.equals("ender_dragon") || type.equals("enderman");
    }

    public static Kind kindOf(Dimension dimension, String type) {
        if (UNTOUCHABLE.contains(type)) return Kind.UNTOUCHABLE;
        if (HEAVY.contains(type)) return Kind.HEAVY;
        // blazes only live in the nether, and there they hover over lava where nothing walks up to them
        if (dimension == Dimension.NETHER && type.equals("blaze")) return Kind.FLYER;
        return Kind.NORMAL;
    }
}
