package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.phys.Vec3;

// the world half of DropExpect: is there a drop we want near the spot where the block was / the mob died
public final class DropWatch {
    private DropWatch() {
    }

    public static boolean seen(AltoClef mod, Vec3 spot, double radius, ItemTarget... wanted) {
        if (spot == null || wanted == null || wanted.length == 0 || !mod.getEntityTracker().itemDropped(wanted)) {
            return false;
        }
        return mod.getEntityTracker().getClosestItemDrop(spot, d -> d.isAlive() && d.position().distanceTo(spot) <= radius, wanted).isPresent();
    }
}
