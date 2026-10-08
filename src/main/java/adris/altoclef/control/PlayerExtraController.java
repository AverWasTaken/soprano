package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    // the server asks for the strength with 0.5 of a tick of partial, so that is the number that decides full damage and the
    // crit. asking with 0 here was a half tick behind the thing we are trying to line up with
    private static final float SERVER_PARTIAL = 0.5f;

    // a swing now would be at full strength, as the server will see it
    public boolean attackReady() {
        return _mod.getPlayer().getAttackStrengthScale(SERVER_PARTIAL) >= 1;
    }

    // ticks until the swing is at full strength, 0 or less when it is. the scale already knows the weapon's delay
    private double ticksToFull() {
        LocalPlayer player = _mod.getPlayer();
        return (1 - player.getAttackStrengthScale(SERVER_PARTIAL)) * player.getCurrentItemAttackStrengthDelay();
    }

    // a melee caller normally sits out the ticks where the cooldown is not full. a crit has to start jumping before that
    // (see CritTiming.LEAD_TICKS) and has to be steered all the way down, so those ticks are worth a call. false whenever
    // baritone is walking, then nobody jumps and the old "wait for full" rules are the whole story
    public boolean wantsCritTick() {
        if (!Baritone.settings().altoJumpCrits.value || _mod.getClientBaritone().getPathingBehavior().isPathing()) {
            return false;
        }
        // mid hop we still want the call (it is how the hop gets cancelled), a new one under pressure we don't: that is
        // the stretch where the kill task used to sit out the cooldown while a crowd hit it. same question CritTiming asks
        if (critInFlight()) {
            return true;
        }
        return !underPressure() && ticksToFull() <= CritTiming.LEAD_TICKS;
    }

    private boolean underPressure() {
        return CritTiming.underPressure(_mod.getMobDefenseChain().meleeNear(_mod), _mod.getPlayer().getHealth());
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
        double gap = reachGap(player, target);
        CritTiming.Sample sample = sample(player, target, gap);
        CritTiming.Step step = _crit.step(gameTime(), sample);
        logStep(step, sample);
        switch (step) {
            case JUMP:
                // vanilla throws the crit away for a sprinting hit, and the jump itself would boost us forward. the stop
                // packet goes out with this tick's movement, long before we are falling
                player.setSprinting(false);
                _mod.getInputControls().tryPress(Input.JUMP);
                walkUp(gap);
                return true;
            case APPROACH:
                player.setSprinting(false);
                walkUp(gap);
                return false;
            case SWING:
                if (!attackReady()) {
                    return false;
                }
                player.setSprinting(false);
                attack(entity);
                return true;
            case WAIT:
                // mid hop. jumping straight up while the mob stands 2 blocks off is how the fall ends with nothing to hit,
                // so keep closing (no sprint, that is the crit killer)
                player.setSprinting(false);
                walkUp(gap);
                return false;
            default:
                return plainSwing(player, entity);
        }
    }

    // what the machine said, once per change and not per tick, so the play log shows which veto is still keeping the bot
    // from crit hopping. WAIT is the boring middle of every hop and stays out of it
    private String _lastCritLog = "";

    private void logStep(CritTiming.Step step, CritTiming.Sample s) {
        if (step == CritTiming.Step.WAIT) {
            return;
        }
        String line = step.name();
        if (step == CritTiming.Step.PLAIN || step == CritTiming.Step.APPROACH) {
            String veto = CritTiming.hopVeto(s);
            line += veto == null ? " (nothing vetoes: mob too far or leaving, or a plain hit is owed after a give up)" :" (" + veto + ")";
        }
        if (s.sprinting && step != CritTiming.Step.PLAIN) {
            line += " [sprinting]";
        }
        if (!line.equals(_lastCritLog)) {
            _lastCritLog = line;
            Debug.logInternal("crit: " + line + ", to full " + String.format("%.1f", s.ticksToFull) + ", near " + s.meleeNear
                    + ", hp " + s.health + (s.pathing ? ", pathing" : ""));
        }
    }

    // tapping forward is enough, the kill task already turned us to face the mob this tick. tryPress lets go by itself, so
    // nothing is left held down if the task changes its mind. no point shoving into a mob we are already touching
    private void walkUp(double gap) {
        if (gap > WALK_UP_GAP) {
            _mod.getInputControls().tryPress(Input.MOVE_FORWARD);
        }
    }

    private static final double WALK_UP_GAP = 1.2;

    // eye to the nearest point of the target's box, which is the distance vanilla compares to the interaction range
    private static double reachGap(LocalPlayer player, Entity target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // how fast the target is going away from us along the flat line between us, blocks per tick (negative when it is
    // coming at us). from the position change, a remote entity's deltaMovement on the client is mostly a lie
    private static double retreatSpeed(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) {
            return 0;
        }
        return ((target.getX() - target.xo) * dx + (target.getZ() - target.zo) * dz) / len;
    }

    // the interaction range attribute (3 for a plain player, creative and tools can change it)
    private static double reachOf(LocalPlayer player) {
        double reach = player.entityInteractionRange();
        return reach > 0 ? reach : 3.0;
    }

    // a hop is on, or about to be, and the back-off in the kill tasks should keep its hands to itself. this asks the real
    // hop question for this mob with the pathing veto left out: the path in the way is usually the back-off itself, and
    // asking wantsCritTick (which says no to any path) is how the back-off used to veto the hop that would have cancelled it
    public boolean hopWantsToStayClose(Entity entity) {
        if (!Baritone.settings().altoJumpCrits.value) {
            return false;
        }
        if (critInFlight()) {
            return true;
        }
        if (!(entity instanceof LivingEntity target)) {
            return false;
        }
        LocalPlayer player = _mod.getPlayer();
        CritTiming.Sample s = sample(player, target, reachGap(player, target));
        s.pathing = false;
        return CritTiming.wantsHop(s);
    }

    private boolean plainSwing(LocalPlayer player, Entity entity) {
        if (attackReady() && (player.onGround() || player.getDeltaMovement().y() < 0 || player.isInWater())) {
            attack(entity);
            return true;
        }
        return false;
    }

    private CritTiming.Sample sample(LocalPlayer player, LivingEntity target, double gap) {
        CritTiming.Sample s = new CritTiming.Sample();
        s.inReach = inRange(target);
        s.reachGap = gap;
        s.reach = reachOf(player);
        s.retreatSpeed = retreatSpeed(player, target);
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
        s.sprinting = player.isSprinting();
        s.meleeNear = _mod.getMobDefenseChain().meleeNear(_mod);
        s.health = player.getHealth();
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
