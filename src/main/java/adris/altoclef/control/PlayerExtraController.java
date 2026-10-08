package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    // continueDestroyBlock runs every tick we are mining, so a position nobody refreshed for this long is a block that
    // already broke. the cancel event only fires after three stopDestroyBlock calls and a finished break may never get them,
    // so the flag stayed up: a table placed where we had just mined a log failed its first interact check (it saw a
    // "break" making no progress) and got blacklisted and wandered off from
    private static final long BREAK_STALE_MS = 500;
    private volatile long _blockBreakStamp;

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
        _blockBreakStamp = System.currentTimeMillis();
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null && System.currentTimeMillis() - _blockBreakStamp <= BREAK_STALE_MS;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return _mod.getPlayer().closerThan(entity, Baritone.settings().altoEntityReachRange.value);
    }

    public void attack(Entity entity) {
        if (inRange(entity)) {
            // every deliberate swing comes through here: the one we hit is angry at us now, neutral or not
            _mod.getEntityTracker().noteProvoked(entity);
            _mod.getController().attack(_mod.getPlayer(), entity);
            _mod.getPlayer().swing(InteractionHand.MAIN_HAND);
        }
    }

    // one machine for the whole bot. the kill task and the force field both swing at the same mob in the same tick, and
    // two machines would each want their own jump
    private final CritTiming _crit = new CritTiming();

    private long gameTime() {
        return _mod.getWorld().getGameTime();
    }

    // mid jump, with somebody still steering it. the kill tasks use this to not call a hop "off the ground, go approach"
    public boolean critInFlight() {
        return _crit.inFlight(gameTime());
    }

    // ticks until the swing is at full strength, 0 or less when it is. the scale already knows the weapon's delay
    private double ticksToFull() {
        LocalPlayer player = _mod.getPlayer();
        return (1 - player.getAttackStrengthScale(0)) * player.getCurrentItemAttackStrengthDelay();
    }

    // a melee caller normally sits out the ticks where the cooldown is not full. a crit has to start jumping before that
    // (see CritTiming.LEAD_TICKS) and has to be steered all the way down, so those ticks are worth a call. false whenever
    // baritone is walking, then nobody jumps and the old "wait for full" rules are the whole story
    public boolean wantsCritTick() {
        if (!Baritone.settings().altoJumpCrits.value || _mod.getClientBaritone().getPathingBehavior().isPathing()) {
            return false;
        }
        return critInFlight() || ticksToFull() <= CritTiming.LEAD_TICKS;
    }

    // every melee swing at a mob goes through here so the crit timing lives in one place. returns whether a swing or a
    // jump went out this tick. with altoJumpCrits off (or a target that can't crit) it is exactly the old rule: hit when
    // the cooldown is full and we are on the ground, falling, or in water
    public boolean melee(Entity entity) {
        LocalPlayer player = _mod.getPlayer();
        if (!(entity instanceof LivingEntity target) || !Baritone.settings().altoJumpCrits.value) {
            _crit.reset();
            return plainSwing(player, entity);
        }
        switch (_crit.step(gameTime(), sample(player, target))) {
            case JUMP:
                // vanilla throws the crit away for a sprinting hit, and the jump itself would boost us forward. the stop
                // packet goes out with this tick's movement, long before we are falling
                player.setSprinting(false);
                _mod.getInputControls().tryPress(Input.JUMP);
                return true;
            case SWING:
                if (player.getAttackStrengthScale(0) < 1) {
                    return false;
                }
                player.setSprinting(false);
                attack(entity);
                return true;
            case WAIT:
                return false;
            default:
                return plainSwing(player, entity);
        }
    }

    private boolean plainSwing(LocalPlayer player, Entity entity) {
        if (player.getAttackStrengthScale(0) >= 1 && (player.onGround() || player.getDeltaMovement().y() < 0 || player.isInWater())) {
            attack(entity);
            return true;
        }
        return false;
    }

    private CritTiming.Sample sample(LocalPlayer player, LivingEntity target) {
        CritTiming.Sample s = new CritTiming.Sample();
        s.inReach = inRange(target);
        s.onGround = player.onGround();
        s.falling = player.getDeltaMovement().y() < 0 && player.fallDistance > 0;
        s.ticksToFull = ticksToFull();
        // armor and sharpness would make a normal hit weaker or stronger than this. wrong in the safe direction either
        // way: a hit that "kills" on paper but doesn't just gets the next swing, a crit we skip is half a heart
        s.normalHitKills = target.getHealth() + target.getAbsorptionAmount() <= ItemHelper.getAttackDamage(player.getMainHandItem().getItem());
        s.inFluid = player.isInWater() || player.isInLava() || player.isUnderWater() || player.isSwimming() || player.isFallFlying();
        s.onClimbable = player.onClimbable();
        s.blind = player.hasEffect(MobEffects.BLINDNESS);
        s.riding = player.isPassenger();
        s.usingItem = player.isUsingItem();
        // the key being down counts too: the shield goes up one player tick after the press
        s.shielding = player.isBlocking() || _mod.getInputControls().isHeldDown(Input.CLICK_RIGHT);
        s.pathing = _mod.getClientBaritone().getPathingBehavior().isPathing();
        Level level = _mod.getWorld();
        BlockPos feet = player.blockPosition();
        // the jump is 1.25 up and we are 1.8 tall, so the cell two above our feet is the one a ceiling bonks us with
        s.lowHeadroom = !passable(level, feet.above(2));
        s.unsafeFloor = !floorAround(level, feet);
        return s;
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    // a hit knocks us back, and a knockback next to a ledge is how a hop turns into a swim in lava. all four sides need
    // ground within two blocks down and no lava on the way to it
    private static boolean floorAround(Level level, BlockPos feet) {
        return floorUnder(level, feet.north()) && floorUnder(level, feet.south())
                && floorUnder(level, feet.east()) && floorUnder(level, feet.west());
    }

    private static boolean floorUnder(Level level, BlockPos column) {
        for (int down = 0; down <= 3; down++) {
            BlockPos pos = column.below(down);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (down > 0 && !state.getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
