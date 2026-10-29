package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;

// the "is there anything to hunt around here" question, asked from outside the food task (the planner wants it before it
// decides to start one). same edible test the hunt uses, so the answer never says yes to something the hunt would refuse
public final class PreyScan {
    private PreyScan() {
    }

    // not a baby (no meat), not carrying a passenger (chicken jockey, the blacklist in the food task only knows the closest one)
    static boolean edible(Entity entity) {
        return entity instanceof LivingEntity living && !living.isBaby() && !entity.isVehicle();
    }

    // an edible, unbanned animal loaded within `radius` blocks (FoodHunt.distance, so a cow up a cliff counts for more than a cow along)
    public static boolean near(AltoClef mod, double radius) {
        var me = mod.getPlayer().position();
        for (Class<?> kind : new Class<?>[]{Pig.class, Cow.class, Chicken.class, Sheep.class, Rabbit.class}) {
            var closest = mod.getEntityTracker().getClosestEntity(PreyScan::edible, kind);
            if (closest.isPresent() && mod.getEntityTracker().isEntityReachable(closest.get())) {
                var at = closest.get().position();
                if (FoodHunt.distance(at.x - me.x, at.y - me.y, at.z - me.z) <= radius) {
                    return true;
                }
            }
        }
        return false;
    }
}
