/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.sim;

import baritone.utils.accessor.ILivingEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

// a PlayerSim that starts where the real player is standing right now, with the real attributes
public final class LiveSim {

    private LiveSim() {}

    // why the last of() came back null, for the debug line
    public static volatile String lastRefusal = "";

    // null when the player is in some state the sim does not model (a boat, lava, a potion that touches jumping)
    public static PlayerSim of(LocalPlayer player, CollisionProvider world) {
        // (water is fine: the sim swims, LivingEntity.travelInWater, as long as nothing changes how fast)
        String why = player.isPassenger() ? "riding something" : player.isInLava() ? "in lava"
                : player.isInWater() && player.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY) != 0.0 ? "depth strider"
                : player.isFallFlying() ? "gliding" : player.isSwimming() ? "swimming" : player.getAbilities().flying ? "flying"
                : player.isCrouching() ? "crouching" : player.isUsingItem() ? "using an item" : player.noPhysics ? "no physics"
                : player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION) ? "a potion effect"
                : player.getAttributeValue(Attributes.FRICTION_MODIFIER) != 1.0 || player.getAttributeValue(Attributes.AIR_DRAG_MODIFIER) != 1.0
                || player.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY) != 0.0 ? "a movement attribute" : null;
        if (why != null) {
            lastRefusal = why;
            return null;
        }
        // the attribute carries the sprint modifier while the flag is up, and the sim puts it back on itself
        double speedAttribute = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double walk = player.isSprinting() ? speedAttribute / (1.0 + PlayerSim.SPRINT_MODIFIER) : speedAttribute;
        boolean sprintAllowed = player.getFoodData().getFoodLevel() > 6 && !player.hasEffect(MobEffects.BLINDNESS);
        PlayerSim.Params params = new PlayerSim.Params(player.getBbWidth(), player.getBbHeight(), player.maxUpStep(),
                player.getAttributeValue(Attributes.GRAVITY), player.getAttributeValue(Attributes.JUMP_STRENGTH), walk, sprintAllowed);
        PlayerSim sim = new PlayerSim(params, world);
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        sim.x = pos.x;
        sim.y = pos.y;
        sim.z = pos.z;
        sim.vx = vel.x;
        sim.vy = vel.y;
        sim.vz = vel.z;
        sim.onGround = player.onGround();
        sim.sprinting = player.isSprinting();
        sim.horizontalCollision = player.horizontalCollision;
        sim.minorHorizontalCollision = player.minorHorizontalCollision;
        sim.verticalCollision = player.verticalCollision;
        sim.climbing = player.onClimbable(); // ladders and vines are the sim's now (scaffolding isn't, SafeCollision says so)
        player.mainSupportingBlockPos.ifPresent(sp -> {
            sim.hasSupport = true;
            sim.supportX = sp.getX();
            sim.supportY = sp.getY();
            sim.supportZ = sp.getZ();
        });
        if (player instanceof ILivingEntity living) {
            sim.noJumpDelay = living.getNoJumpDelay();
        }
        return sim;
    }
}
