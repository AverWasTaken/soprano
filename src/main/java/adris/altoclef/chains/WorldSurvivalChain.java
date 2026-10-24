package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.DunkRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.EndermanWater;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame _wasInLavaTimer = new TimerGame(1);
    private boolean _wasAvoidingDrowning;
    private TimerGame _portalStuckTimer = new TimerGame(5);

    private BlockPos _extinguishWaterPosition;
    // the block and face the dunk clicked, the pickup clicks the same ones
    private BlockPos _extinguishHit;
    private Direction _extinguishFace = Direction.UP;
    private int _dunkAimTicks;
    private boolean _dunkGaveUp;
    private boolean _trackingWater;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        // Drowning
        handleDrowning(mod);

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new EscapeFromLavaTask());
            return 100;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Sand or gravel landed on us. it is 1 damage every half second for as long as our eyes are in it, so it goes
        // before anything polite. the task knows to dig rather than step aside when the block is the one we stand in
        // not canBreak: that one says no for anything ever marked unreachable, and being unreachable is not a thing for
        // the block our head is in. only the hard nos (bedrock-hard, the never-break list) stop this
        BlockPos buried = WorldHelper.buriedInFallenBlock(mod);
        if (buried != null && mod.getWorld().getBlockState(buried).getDestroySpeed(mod.getWorld(), buried) >= 0
                && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(buried)) {
            setTask(new DestroyBlockTask(buried));
            return 100;
        }

        // an angry enderman at the bottom of the health bar: water at our feet (see EndermanWater)
        double angryEnderman = nearestAngryEnderman(mod);
        boolean endermanDunk = EndermanWater.dunkNow(mod.getPlayer().getHealth(), angryEnderman,
                mod.getItemStorage().hasItem(Items.WATER_BUCKET), WorldHelper.waterEvaporates(mod.getWorld()), mod.getPlayer().isInWater());

        // Extinguish with water
        // (endBurn would hand the dunk a fresh aim budget every tick, and it is the budget that stops a look that never lands)
        if (!mod.getPlayer().isOnFire() && !endermanDunk) {
            endBurn(mod);
        }
        if (Baritone.settings().altoExtinguishSelfWithWater.value) {
            if (!(_mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE) && !WorldHelper.waterEvaporates(mod.getWorld())) {
                // Extinguish ourselves
                trackWaterWhileBurning(mod);
                float dunk = dunkOrWalk(mod, true);
                if (dunk != Float.NEGATIVE_INFINITY) {
                    return dunk;
                }
            } else if (EndermanWater.keepWater(angryEnderman) && _extinguishWaterPosition != null) {
                // the water stays up while an angry enderman is about, it is the thing it is afraid of
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), _extinguishFace, _extinguishHit, true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        }

        if (endermanDunk) {
            float dunk = dunkOrWalk(mod, false);
            if (dunk != Float.NEGATIVE_INFINITY) {
                return dunk;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal(mod)) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            _portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (_portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        return Float.NEGATIVE_INFINITY;
    }

    // distance to the closest enderman that is angry at us, infinity for none
    private static double nearestAngryEnderman(AltoClef mod) {
        double nearest = Double.POSITIVE_INFINITY;
        try {
            for (Enderman man : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
                if (man.isAlive() && EntityHelper.isAngryAtPlayer(mod, man)) {
                    nearest = Math.min(nearest, mod.getPlayer().distanceTo(man));
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // one tick of stale endermen is fine
        }
        return nearest;
    }

    // the burn is over (or never was): next one gets a fresh aim budget
    private void endBurn(AltoClef mod) {
        _dunkAimTicks = 0;
        _dunkGaveUp = false;
        if (_trackingWater) {
            _trackingWater = false;
            mod.getBlockTracker().stopTracking(Blocks.WATER);
        }
    }

    // the far-water check needs the tracker to know about water before any task is walking there to ask for it
    private void trackWaterWhileBurning(AltoClef mod) {
        if (!_trackingWater) {
            _trackingWater = true;
            mod.getBlockTracker().trackBlock(Blocks.WATER);
        }
    }

    // dunk where we stand if a face puts the water in our cell, and hold still until the look lands (a walk's pathing
    // rewrites the camera every tick, so the old fall-through to a pond never got to click). only when there is no face,
    // or the aim gave up, do we walk, and then only to water that is close. NEGATIVE_INFINITY means nothing to do here
    private float dunkOrWalk(AltoClef mod, boolean walk) {
        BlockPos feet = mod.getPlayer().blockPosition();
        // a click mid jump lands in the cell we were in, not the one we are in by the time it goes through
        boolean airborne = !mod.getPlayer().onGround() && !mod.getPlayer().onClimbable() && !mod.getPlayer().isInWater();
        if (airborne && !_dunkGaveUp) {
            return Float.NEGATIVE_INFINITY;
        }
        if (mod.getItemStorage().hasItem(Items.WATER_BUCKET) && !_dunkGaveUp && WorldHelper.canPlace(mod, feet)) {
            for (DunkRules.Face face : DunkRules.candidates(mod.getWorld(), feet)) {
                Optional<Rotation> reach = LookHelper.getReach(face.hit(), face.face());
                if (reach.isEmpty()) {
                    continue;
                }
                if (!DunkRules.keepAiming(_dunkAimTicks)) {
                    _dunkGaveUp = true;
                    break;
                }
                _dunkAimTicks++;
                setTask(null);
                mod.getClientBaritone().getPathingBehavior().cancelEverything();
                mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                if (aimedAt(mod, face) && mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                    _extinguishWaterPosition = face.lands();
                    _extinguishHit = face.hit();
                    _extinguishFace = face.face();
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                }
                return 90;
            }
        }
        Optional<BlockPos> water = mod.getBlockTracker().getNearestTracking(Blocks.WATER);
        // below the mob defense run (80) and fight (65) so a pond walk never keeps them off the wheel
        if (walk && water.isPresent() && DunkRules.walkWorthIt(mod.getPlayer().position().distanceTo(WorldHelper.toVec3d(water.get())))) {
            // the exact block we measured, not whichever one the closest-block task likes better
            setTask(new GetToBlockTask(water.get()));
            return 60;
        }
        return Float.NEGATIVE_INFINITY;
    }

    // the crosshair is on the block and the face we picked, anything else puts the water somewhere else
    private static boolean aimedAt(AltoClef mod, DunkRules.Face face) {
        HitResult hit = mod.getClientBaritone().getPlayerContext().objectMouseOver();
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                && BucketPour.aimedRight(b.getBlockPos(), b.getDirection(), face.hit(), face.face());
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (Baritone.settings().altoAvoidDrowning.value) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isInWater() && mod.getPlayer().getAirSupply() < mod.getPlayer().getMaxAirSupply()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    _wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (_wasAvoidingDrowning && !avoidedDrowning) {
            _wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE)) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }

    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer(mod)) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof BaseFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal(AltoClef mod) {
        if (!WorldHelper.isInNetherPortal(mod)) return false;
        // nothing running means nobody asked us to be anywhere, so a player standing in a portal gets left alone
        Task userTask = mod.getUserTaskChain().getCurrentTask();
        return userTask != null && !userTask.thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public String getHudName() {
        return "Staying safe";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
    }
}
