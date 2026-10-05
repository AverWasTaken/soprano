package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;

import java.util.Optional;

// "we are in the overworld in a nether phase and the kit is gone": a death in the Nether respawns us at home with an
// empty bag, and walking back into the Nether like that is how the next death happens. pure, shared by NETHER and EYES
final class NetherRegress {
    static Optional<GamerPhase> fromOverworld(GamerFacts f, GamerConfig cfg) {
        if (f.dimension() != Dimension.OVERWORLD) {
            return Optional.empty();
        }
        if (KitPlanner.have(f, "stone_pickaxe") < 1) {
            return Optional.of(GamerPhase.GATHER);
        }
        if (!KitPlanner.essentialsMet(f, cfg.overworld) || armorShort(f, cfg)) {
            return Optional.of(GamerPhase.IRON);
        }
        return Optional.empty();
    }

    // only the full iron plan has a number to hold it to, a lighter plan chose its own armor
    private static boolean armorShort(GamerFacts f, GamerConfig cfg) {
        return cfg.overworld.armorPlan == OverworldConfig.ArmorPlan.FULL_IRON && f.armorPoints() < cfg.end.bedMinArmor;
    }

    private NetherRegress() {
    }
}
