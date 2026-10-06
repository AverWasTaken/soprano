package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// pure: which way we build the portal, and what we must hold before we start walking away from the overworld
public final class PortalPlanner {
    public enum Method {
        // lava lake + water + buckets, no diamonds needed
        CAST,
        // mine or place obsidian with a diamond pickaxe and build the frame
        OBSIDIAN
    }

    private PortalPlanner() {
    }

    public static Method parse(String name) {
        if (name != null) {
            for (Method m : Method.values()) {
                if (m.name().equalsIgnoreCase(name)) {
                    return m;
                }
            }
        }
        return Method.CAST;
    }

    // castSeconds = how long the cast has been running (not the prep before it). OBSIDIAN is sticky, going back and
    // forth would just burn the budget twice. DefaultGoToDimensionTask never finishes when there is no lava lake, it
    // wanders, so the clock is the only thing that ends a cast
    public static Method decide(Method current, double castSeconds, OverworldConfig cfg, boolean sawLava, boolean hasDiamondPickaxe) {
        if (current == Method.OBSIDIAN) {
            return Method.OBSIDIAN;
        }
        if (castSeconds >= cfg.castGiveUpMinutes * 60) {
            return Method.OBSIDIAN;
        }
        // no lava anywhere and the pickaxe is already in hand: obsidian is the cheap way now
        if (!sawLava && hasDiamondPickaxe && castSeconds >= cfg.noLavaSeconds) {
            return Method.OBSIDIAN;
        }
        return Method.CAST;
    }

    // what to hold before leaving the overworld (Marvion ordering: get it all here, not in the nether). the cast needs
    // two buckets (one becomes water, one lava) and a light, the end needs the water bucket back later
    public static List<KitNeed> gate(GamerFacts f, OverworldConfig cfg) {
        List<KitNeed> out = new ArrayList<>();
        if (f.foodUnits() < cfg.minFoodUnits) {
            out.add(new KitNeed(KitNeed.FOOD, cfg.minFoodUnits));
        }
        int buckets = KitPlanner.have(f, "bucket");
        if (buckets < 2) {
            out.add(new KitNeed("bucket", KitPlanner.held(f, "bucket") + 2 - buckets));
        }
        if (!f.has(Items.WATER_BUCKET)) {
            out.add(new KitNeed("water_bucket", 1));
        }
        if (f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) < 1) {
            out.add(new KitNeed("flint_and_steel", 1));
        }
        int unworn = KitPlanner.toEquip(f, cfg).size();
        if (unworn > 0) {
            out.add(new KitNeed(KitNeed.EQUIP_ARMOR, unworn));
        }
        if (f.buildBlocks() < cfg.portalBuildBlocks) {
            out.add(new KitNeed(KitNeed.BUILD_BLOCKS, cfg.portalBuildBlocks));
        }
        return out;
    }

    // we arrive standing in the other portal, so that is where the nether side of the pair is
    public static void recordArrival(RunState state, GamerFacts f) {
        if (f.dimension() == Dimension.NETHER && state.netherPortal == null) {
            state.netherPortal = new RunState.Pos(f.x(), f.y(), f.z());
        }
    }
}
