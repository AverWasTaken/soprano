package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.speedrun.gamer.end.BedPairSearch;
import adris.altoclef.tasks.speedrun.gamer.end.BedPlaceGate;
import adris.altoclef.tasks.speedrun.gamer.end.BedSafety;
import adris.altoclef.tasks.speedrun.gamer.end.DragonDeadLatch;
import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch;
import adris.altoclef.tasks.speedrun.gamer.end.DragonWaitRules;
import adris.altoclef.tasks.speedrun.gamer.end.HealStall;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the bed fight. crystals that reach the perch go first (a perch with eight heal beams on it eats a bed's worth of hp in a
// few seconds), then it waits on the ring outside the wings and puts one bed per perch on the floor under the head once the
// yaw is frozen (attacking, then flaming). where exactly is BedPairSearch's call
public class KillEnderDragonWithBedsTask extends Task {
    private static final double DRAGON_GONE_SECONDS = 5;
    private static final double HEAL_STALL_SECONDS = 20;
    // the exit portal is a 3x3 ring around the fountain at (0,0), a tracked END_PORTAL block this close to it is the real one
    private static final int EXIT_PORTAL_RADIUS = 8;
    // an enderman this close is on us, so mob defense may deal with it even mid bed
    private static final double HOLD_STAND_MELEE = 3;
    // a pair search is ~40k cheap checks: fine twice a second, silly every tick
    private static final int RESEARCH_TICKS = 10;
    // this long at the stand pressing with no bed showing up and the pair is no good
    private static final int PLACE_TICKS = 20;
    // and this long walking to a stand we never get to
    private static final int WALK_TICKS = 100;
    // and this many bad pairs in one perch, then the perch is a wash
    private static final int PAIR_FAILS = 2;
    private static final int LOG_TICKS = 20;
    private static final int RESULT_TICKS = 20;

    private final Task _whenNotPerchingTask;
    // instance state: this used to be two statics shared by every instance (and Playground), reset only in onStart
    private final DragonDeadLatch _deadLatch = new DragonDeadLatch(DRAGON_GONE_SECONDS);

    // 20 s of no healing between "too hurt to click" and "critical" is a bed fight that is going nowhere
    private final HealStall _healStall = new HealStall(HEAL_STALL_SECONDS);
    private final CrystalStep _crystals = new CrystalStep();
    // the level we pushed, for the hand over on a pause
    private BotBehaviour.State _level;

    private BlockPos _endPortalTop;
    private boolean _perching;
    // walking to the stand, placing or clicking: mob defense does not pull us off for an enderman then
    private volatile boolean _holdStand;
    private AltoClef _mod;

    // this perch: the pair we go for, the bed we put down for it, and whether it is spent
    private boolean _sat;
    private BedPairSearch.Pair _pair;
    private long _searchedAt = -RESEARCH_TICKS;
    private int _placeTicks;
    private int _walkTicks;
    private boolean _unsafeLogged;
    private int _pairFails;
    private boolean _bedDone;
    private BlockPos _bedFoot;
    private Task _standTask;
    private Task _escapeTask;
    private boolean _crystalsWere;
    private boolean _frozenWas;

    // log bookkeeping
    private boolean _clicked;
    private boolean _pendingResult;
    private long _resultAt;
    private float _dragonHpAtClick;
    private float _usHpAtClick;
    private long _lastPerchLog;
    private boolean _perchHadPair;
    private boolean _perchBedDown;
    private boolean _perchClicked;
    private boolean _perchPathed;
    private boolean _perchGap;
    private long _lastTickAt;

    public KillEnderDragonWithBedsTask(IDragonWaiter notPerchingOverride) {
        _whenNotPerchingTask = (Task) notPerchingOverride;
    }

    private static BlockPos locateExitPortalTop(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0))) return null;
        int height = WorldHelper.getGroundHeight(mod, 0, 0, Blocks.BEDROCK);
        if (height != -1) return new BlockPos(0, height, 0);
        return null;
    }

    // a tracked END_PORTAL next to (0,0). the tracker could still hold the stronghold portal from the overworld
    public static boolean exitPortalExists(AltoClef mod) {
        Optional<BlockPos> portal = mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL);
        return portal.isPresent() && Math.abs(portal.get().getX()) <= EXIT_PORTAL_RADIUS && Math.abs(portal.get().getZ()) <= EXIT_PORTAL_RADIUS;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _deadLatch.reset();
        _healStall.reset();
        _perching = false;
        _mod = mod;
        _holdStand = false;
        _sat = false;
        _frozenWas = false;
        _crystalsWere = false;
        _escapeTask = null;
        resetPerch();
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
        // the sword task keeps mob defense out of the dragon fight and so does this one: endermen and the dragon are not a
        // fight or a run while the beds go off
        mod.getBehaviour().releasePaused(this);
        _level = mod.getBehaviour().push();
        mod.getBehaviour().addMobDefenseExclusion(this::ownedOrHeld);
        // and off the aura too, like the sword task: an excluded mob is the aura's otherwise, and a swing turns the head in the
        // middle of aiming a bed
        mod.getBehaviour().addForceFieldExclusion(KillEnderDragonTask::calmDragonFightEntity);
    }

    // no beds left in the inventory and none placed and waiting to be clicked. the caller swaps to the sword when this
    // holds and the dragon is not perched (see isFinished), this task alone would wait forever
    public boolean outOfBeds(AltoClef mod) {
        return bedsLeft(mod) == 0;
    }

    // beds in the bag plus the one on the floor waiting for its click: what the strat choice has to count, or a placed bed
    // reads as none left and the phase drops to the sword
    public int bedsLeft(AltoClef mod) {
        return countBeds(mod.getItemStorage().getItemCount(ItemHelper.BED), _bedFoot != null && isBed(mod, _bedFoot));
    }

    static int countBeds(int inBag, boolean placed) {
        return inBag + (placed ? 1 : 0);
    }

    // stood too hurt to click for a while and nothing is healing us (see HealStall): the phase swaps to the sword
    public boolean healStalled() {
        return _healStall.stalled();
    }

    // even at full health the best floor bed there can be (stand a step down, reach at its limit) kills us in this armor:
    // full iron eats ~20 of it. the phase stays on the sword while this holds, it is armor, waiting does not fix it
    public boolean bedsUnsurvivable(AltoClef mod) {
        return !BedSafety.survives(mod.getPlayer().getMaxHealth(), mod.getPlayer().getAbsorptionAmount(), BedPairSearch.BEST_SELF_DAMAGE,
                mod.getWorld().getDifficulty().getId(), mod.getPlayer().getArmorValue(), mod.getPlayer().getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    public boolean isDragonDead() {
        return _deadLatch.isDead();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        _deadLatch.update(exitPortalExists(mod), dragon.isPresent(),
                mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0)), mod.getWorld().getGameTime() / 20.0);
        _perching = false;
        _holdStand = false;
        if (!_deadLatch.hasSeenDragon() && !_deadLatch.isDead()) {
            setDebugState("Waiting for dragon to spawn.", "Waiting for the dragon");
            return null;
        }
        if (_endPortalTop == null) {
            _endPortalTop = locateExitPortalTop(mod);
            if (_endPortalTop != null) {
                ((IDragonWaiter) _whenNotPerchingTask).setExitPortalTop(_endPortalTop);
            }
        }

        if (_endPortalTop == null) {
            setDebugState("Searching for end portal top.");
            return new GetToXZTask(0, 0);
        }

        if (_deadLatch.isDead()) {
            setDebugState("Waiting for overworld portal to spawn.");
            if (mod.getPlayer().getXRot() != -90) {
                mod.getPlayer().setXRot(-90);
            }
            return null;
        }

        if (dragon.isEmpty()) {
            setDebugState("No dragon found.");
            // not in the latch until it has been gone a while: walk to the middle meanwhile, that is where it ends up anyway
            if (!WorldHelper.inRangeXZ(mod.getPlayer(), _endPortalTop, 0.25)) {
                setDebugState("Going to end portal top at " + _endPortalTop.toString() + ".");
                return new GetToXZTask(_endPortalTop.getX(), _endPortalTop.getZ());
            }
        } else {
            EnderDragon dragonEntity = (EnderDragon) dragon.get();
            EnderDragonPhase<?> phase = dragonEntity.getPhaseManager().getCurrentPhase().getPhase();
            boolean attacking = phase == EnderDragonPhase.SITTING_ATTACKING;
            boolean flaming = phase == EnderDragonPhase.SITTING_FLAMING;
            boolean sitting = BedPlaceGate.perch(phase == EnderDragonPhase.SITTING_SCANNING, attacking, flaming);
            _perching = sitting;
            perchBookkeeping(mod, dragonEntity, sitting);
            newRoar(BedPlaceGate.yawFrozen(attacking, flaming));
            logBed(mod, dragonEntity);

            Task step = stepOutOfTheDragon(mod, dragonEntity, sitting || phase == EnderDragonPhase.LANDING);
            if (step == null) {
                step = crystalStep(mod);
            }
            boolean acting = step == null && BedPlaceGate.yawFrozen(attacking, flaming) && !_bedDone && pickPair(mod, dragonEntity) != null
                    && safeToGo(mod);
            if (sitting) {
                logPerch(mod, dragonEntity, phase, BedPlaceGate.yawFrozen(attacking, flaming));
            }
            // the waiter only lets go of the ring while we are going for a bed
            ((IDragonWaiter) _whenNotPerchingTask).setPerchState(acting);
            if (step != null) {
                mod.getFoodChain().shouldStop(false);
                return step;
            }
            if (acting) {
                _holdStand = true;
                // the walk, the place and the click are a few seconds, a meal in the middle of them loses the perch
                mod.getFoodChain().shouldStop(true);
                return bedCycle(mod, dragonEntity);
            }
        }
        mod.getFoodChain().shouldStop(false);
        setDebugState("Waiting for the dragon to perch", "Waiting for the dragon");
        return _whenNotPerchingTask;
    }

    // inside a wing push or a bite box of a sitting (or landing) dragon: straight away from the body until we are out of all
    // of them. the wings fling whatever stands in them every tick, the ring wait used to stand right in one
    private Task stepOutOfTheDragon(AltoClef mod, EnderDragon dragon, boolean down) {
        if (!down || !inTheDragon(mod, dragon)) {
            _escapeTask = null;
            return null;
        }
        if (_escapeTask == null || _escapeTask.isFinished(mod)) {
            AABB body = dragon.getSubEntities()[2].getBoundingBox();
            DragonWaitRules.Spot out = DragonWaitRules.awayFrom(mod.getPlayer().getX(), mod.getPlayer().getZ(),
                    body.getCenter().x, body.getCenter().z, DragonWaitRules.BED_AIM);
            _escapeTask = new GetToXZTask((int) Math.floor(out.x()), (int) Math.floor(out.z()));
            Debug.logMessage(String.format("bed: in the dragon's wings at %s, stepping out to %.0f %.0f",
                    mod.getPlayer().blockPosition().toShortString(), out.x(), out.z()));
        }
        setDebugState("Stepping out of the dragon's wings", "Getting out of the wings");
        return _escapeTask;
    }

    private static boolean inTheDragon(AltoClef mod, EnderDragon dragon) {
        Parts parts = Parts.of(dragon);
        Vec3 at = mod.getPlayer().position();
        return !DragonPerch.standsClear(DragonPerch.player(at.x, at.y, at.z), parts.pushes, parts.biters, 0);
    }

    // crystals first: one in reach of the perch heals the dragon through every bed. the ones out of reach can wait
    private Task crystalStep(AltoClef mod) {
        double fx = _endPortalTop.getX() + 0.5;
        double fz = _endPortalTop.getZ() + 0.5;
        double perchY = _endPortalTop.getY() + 1;
        Task step = _crystals.tick(mod, e -> e instanceof EndCrystal && DragonPerch.healsPerch(e.getX(), e.getY(), e.getZ(), fx, perchY, fz),
                this::setDebugState);
        boolean on = step != null;
        if (on != _crystalsWere) {
            long left = mod.getEntityTracker().getTrackedEntities(EndCrystal.class).stream()
                    .filter(c -> DragonPerch.healsPerch(c.getX(), c.getY(), c.getZ(), fx, perchY, fz)).count();
            Debug.logMessage(on ? "bed: crystals first, " + left + " reach the perch" : "bed: no crystal reaches the perch, beds now");
            _crystalsWere = on;
        }
        return step;
    }

    // the pair we are going for this perch. kept while it still works (a bed down for it always works, the click is what is
    // left), searched again at most every RESEARCH_TICKS when it stops working
    private BedPairSearch.Pair pickPair(AltoClef mod, EnderDragon dragon) {
        if (_pair != null && pairStillGood(mod, dragon)) {
            return _pair;
        }
        _pair = null;
        _standTask = null;
        if (_bedDone) return null;
        long now = mod.getWorld().getGameTime();
        if (now - _searchedAt < RESEARCH_TICKS) return null;
        _searchedAt = now;
        _placeTicks = 0;
        _walkTicks = 0;
        _pair = BedPairSearch.search(view(mod, dragon), terrain(mod), mod.getPlayer().getX(), mod.getPlayer().getZ());
        if (_pair != null) {
            _perchHadPair = true;
        }
        return _pair;
    }

    // the pair's own self damage against our pool and armor, before the walk in. clicking was checked only with the bed
    // already down, so an unsafe perch burned a bed for nothing. refusing feeds HealStall like a refused click does
    private boolean safeToGo(AltoClef mod) {
        if (_bedFoot != null) return true;
        double health = mod.getPlayer().getHealth();
        double absorption = mod.getPlayer().getAbsorptionAmount();
        int difficulty = mod.getWorld().getDifficulty().getId();
        double armor = mod.getPlayer().getArmorValue();
        double toughness = mod.getPlayer().getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        boolean ok = BedSafety.survives(health, absorption, _pair.selfDamage(), difficulty, armor, toughness);
        _healStall.update(!ok, health + absorption, mod.getWorld().getGameTime() / 20.0);
        if (!ok && !_unsafeLogged) {
            _unsafeLogged = true;
            Debug.logMessage(String.format("bed: no safe pair, the click would cost %.1f of %.1f",
                    BedSafety.afterArmor(BedSafety.scaled(_pair.selfDamage(), difficulty), armor, toughness), health + absorption));
        }
        return ok;
    }

    private boolean pairStillGood(AltoClef mod, EnderDragon dragon) {
        // our bed fills both cells, so the air checks below would throw the pair away the tick it lands
        if (_bedFoot != null || isBed(mod, pos(_pair.foot()))) return true;
        if (_placeTicks > PLACE_TICKS || _walkTicks > WALK_TICKS) {
            _pairFails++;
            Debug.logMessage(String.format("bed: %s, dropping that pair (%d bad)",
                    _placeTicks > PLACE_TICKS ? "no bed after " + PLACE_TICKS + " ticks at the stand" : "never got to the stand", _pairFails));
            if (_pairFails >= PAIR_FAILS) {
                Debug.logMessage("bed: giving up on this perch");
                _bedDone = true;
            }
            return false;
        }
        BedPairSearch.Terrain t = terrain(mod);
        BedPairSearch.Cell h = _pair.head();
        BedPairSearch.Cell f = _pair.foot();
        BedPairSearch.Cell s = _pair.stand();
        if (!t.bedCell(h.x(), h.y(), h.z()) || !t.bedCell(f.x(), f.y(), f.z()) || !t.standable(s.x(), s.y(), s.z())) return false;
        BedPairSearch.Dragon d = view(mod, dragon);
        // the cloud showing up does not move the stand (it keeps out of where the cloud goes from the start), but the walk
        // there must not cross it now
        boolean ok = BedPairSearch.standOk(d, t, h, f, new int[]{_pair.dirX(), _pair.dirZ()}, s, mod.getPlayer().getX(), mod.getPlayer().getZ())
                || atStand(mod);
        if (!ok && d.cloudUp()) {
            Debug.logMessage("bed: the cloud came up before we got to the stand, dropping that pair");
        }
        return ok;
    }

    private boolean atStand(AltoClef mod) {
        BedPairSearch.Cell s = _pair.stand();
        return mod.getPlayer().blockPosition().equals(new BlockPos(s.x(), s.y(), s.z()));
    }

    private Task bedCycle(AltoClef mod, EnderDragon dragon) {
        BedPairSearch.Cell s = _pair.stand();
        BlockPos foot = pos(_pair.foot());
        BlockPos head = pos(_pair.head());
        if (_bedFoot == null && isBed(mod, foot)) {
            _bedFoot = foot;
            _perchBedDown = true;
            Debug.logMessage(String.format("bed: placed foot %s head %s", foot.toShortString(), bedHead(mod, foot).toShortString()));
        }
        if (_bedFoot != null) {
            return click(mod, dragon);
        }
        BlockPos stand = new BlockPos(s.x(), s.y(), s.z());
        if (atStand(mod) && !mod.getPlayer().onGround()) {
            // a hop onto the cell, the place waits for the landing
            return null;
        }
        if (!atStand(mod)) {
            if (_standTask == null || _standTask.isFinished(mod)) {
                _standTask = new GetToBlockTask(stand);
            }
            _perchPathed = true;
            _walkTicks++;
            setDebugState("Walking to the bed stand " + stand.toShortString(), "Getting into position");
            return _standTask;
        }
        _perchPathed = false;
        setDebugState("Placing a bed", "Placing a bed");
        _placeTicks++;
        if (!mod.getSlotHandler().forceEquipItem(ItemHelper.BED, true)) {
            return null;
        }
        // look at the floor under the foot: the bed's foot goes where we click, its head one cell on along our yaw
        LookHelper.lookAt(mod, foot.below(), Direction.UP, true);
        Direction facing = Direction.fromYRot(mod.getPlayer().getYRot());
        boolean aimed = mod.getClientBaritone().getPlayerContext().objectMouseOver() instanceof BlockHitResult b
                && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(foot.below()) && b.getDirection() == Direction.UP;
        if (aimed && BedPlaceGate.headCell(foot, facing).equals(head)
                && BedPairSearch.cellClear(Parts.of(dragon).eaters, _pair.foot()) && BedPairSearch.cellClear(Parts.of(dragon).eaters, _pair.head())) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
        }
        return null;
    }

    // the bed is down: check where its head half really went and blow it, if we live through it
    private Task click(AltoClef mod, EnderDragon dragon) {
        setDebugState("Clicking the bed", "Blowing up the bed");
        if (!mod.getPlayer().onGround()) {
            return null;
        }
        BlockPos headHalf = bedHead(mod, _bedFoot);
        Vec3 boom = Vec3.atCenterOf(headHalf);
        double self = DragonPerch.bedDamage(mod.getPlayer().position().distanceTo(boom));
        double health = mod.getPlayer().getHealth();
        double absorption = mod.getPlayer().getAbsorptionAmount();
        // our own explosion is the likeliest thing to kill us here: with too little health for it, do not click, let the food
        // chain heal and hope the dragon sits long enough
        boolean tooHurt = !BedSafety.canClick(health, absorption, mod.getPlayer().getArmorValue())
                || !BedSafety.survives(health, absorption, self, mod.getWorld().getDifficulty().getId(),
                mod.getPlayer().getArmorValue(), mod.getPlayer().getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        _healStall.update(tooHurt, health + absorption, mod.getWorld().getGameTime() / 20.0);
        if (tooHurt) {
            mod.getFoodChain().shouldStop(false);
            setDebugState("Too hurt for a bed explosion, healing first", "Healing before the bed");
            return null;
        }
        if (!_clicked) {
            _clicked = true;
            _perchClicked = true;
            _pendingResult = true;
            _resultAt = mod.getWorld().getGameTime() + RESULT_TICKS;
            _dragonHpAtClick = dragon.getHealth();
            _usHpAtClick = mod.getPlayer().getHealth();
            boolean wrong = !headHalf.equals(pos(_pair.head()));
            Debug.logMessage(String.format("bed: click head %s%s, dragon hp %.0f, us hp %.1f, self %.1f raw",
                    headHalf.toShortString(), wrong ? " (not where we aimed)" : "", _dragonHpAtClick, _usHpAtClick, self));
        }
        // either half goes off at the head half, and the foot is the one in reach
        return new InteractWithBlockTask(_bedFoot);
    }

    // a new sit wipes the last one's pair and bed, and the end of a sit gets its one log line
    private void perchBookkeeping(AltoClef mod, EnderDragon dragon, boolean sitting) {
        long now = mod.getWorld().getGameTime();
        if (_lastTickAt != 0 && now - _lastTickAt > 3 && _sat) _perchGap = true;
        _lastTickAt = now;
        if (sitting && !_sat) {
            resetPerch();
        }
        if (!sitting && _sat) {
            endPerch(mod);
        }
        _sat = sitting;
        if (_bedFoot != null && !isBed(mod, _bedFoot)) {
            // gone: our click or the dragon's mouth. either way that was this perch's bed
            if (!_clicked) {
                Debug.logMessage("bed: eaten without click at " + _bedFoot.toShortString());
            } else {
                _bedDone = true;
            }
            _bedFoot = null;
            _pair = null;
            _standTask = null;
        }
    }

    // a new roar is a new yaw (scanning turned the head to us again in between), so the old pair and an unclicked bed
    // are aimed at where the head was
    private void newRoar(boolean frozen) {
        if (frozen && !_frozenWas) {
            _pair = null;
            _standTask = null;
            _placeTicks = 0;
            _walkTicks = 0;
            if (!_clicked) _bedFoot = null;
        }
        _frozenWas = frozen;
    }

    private void resetPerch() {
        _pair = null;
        // a bed left from the last perch is on the floor where that head was, it is nothing to us now
        _bedFoot = null;
        _searchedAt = -RESEARCH_TICKS;
        _placeTicks = 0;
        _walkTicks = 0;
        _unsafeLogged = false;
        _pairFails = 0;
        _bedDone = false;
        _standTask = null;
        _clicked = false;
        _perchHadPair = false;
        _perchBedDown = false;
        _perchClicked = false;
        _perchPathed = false;
        _perchGap = false;
    }

    private void endPerch(AltoClef mod) {
        String lost = "";
        if (!_perchClicked) {
            boolean breath = mod.getEntityTracker().entityFound(e -> e.closerThan(mod.getPlayer(), 8), AreaEffectCloud.class);
            boolean man = mod.getEntityTracker().entityFound(e -> KillEnderDragonTask.angryEnderman(e) && e.closerThan(mod.getPlayer(), 16),
                    Enderman.class);
            lost = _perchHadPair ? ", lost to " + BedPlaceGate.lostReason(_perchBedDown, _perchPathed, _perchGap, breath, man) : ", no pair";
        }
        Debug.logMessage(String.format("perch: over, %s, %s%s", _perchBedDown ? "bed down" : "no bed", _perchClicked ? "clicked" : "no click", lost));
    }

    // the result line, a second after the click. the placed and click lines are where those happen
    private void logBed(AltoClef mod, EnderDragon dragon) {
        long now = mod.getWorld().getGameTime();
        if (_pendingResult && now >= _resultAt) {
            _pendingResult = false;
            Debug.logMessage(String.format("bed: result dragon hp %.0f->%.0f, us hp %.1f->%.1f", _dragonHpAtClick, dragon.getHealth(),
                    _usHpAtClick, mod.getPlayer().getHealth()));
        }
    }

    // once a second while sitting: what the perch looks like to us
    private void logPerch(AltoClef mod, EnderDragon dragon, EnderDragonPhase<?> phase, boolean frozen) {
        long now = mod.getWorld().getGameTime();
        if (now - _lastPerchLog < LOG_TICKS) return;
        _lastPerchLog = now;
        Parts parts = Parts.of(dragon);
        BedPairSearch.Dragon d = view(mod, dragon);
        Vec3 at = mod.getPlayer().position();
        boolean inWing = !DragonPerch.standsClear(DragonPerch.player(at.x, at.y, at.z), parts.pushes, List.of(), 0);
        boolean inCloud = Math.hypot(at.x - d.cloudX(), at.z - d.cloudZ()) < DragonPerch.CLOUD_RADIUS;
        String pair = _pair == null ? (frozen ? (_bedDone ? "done" : "no pair") : "waiting")
                : String.format("H %d %d %d F %d %d %d S %d %d %d dmg %.0f self %.0f", _pair.head().x(), _pair.head().y(), _pair.head().z(),
                _pair.foot().x(), _pair.foot().y(), _pair.foot().z(), _pair.stand().x(), _pair.stand().y(), _pair.stand().z(),
                _pair.dragonDamage(), _pair.selfDamage());
        Debug.logMessage(String.format("bed perch: %s, yaw %s %.0f, %s, in wing %b, in cloud %b, crystals healing %b",
                phase, frozen ? "frozen" : "turning", dragon.getYRot(), pair, inWing, inCloud, _crystalsWere));
    }

    // the dragon as BedPairSearch sees it. the cloud's middle is worked out from where the head points, it only exists ~50
    // ticks into the roar but the stand keeps out of it from the start
    private static BedPairSearch.Dragon view(AltoClef mod, EnderDragon dragon) {
        Parts parts = Parts.of(dragon);
        AABB head = dragon.getSubEntities()[0].getBoundingBox();
        AABB neck = dragon.getSubEntities()[1].getBoundingBox();
        double[] cloud = DragonPerch.cloudCentre(dragon.getX(), dragon.getZ(), head.getCenter().x, head.getCenter().z);
        boolean cloudUp = mod.getEntityTracker().entityFound(
                e -> Math.hypot(e.getX() - cloud[0], e.getZ() - cloud[1]) < DragonPerch.CLOUD_RADIUS, AreaEffectCloud.class);
        return new BedPairSearch.Dragon(parts.eaters, parts.pushes, parts.biters, head.getCenter().x, head.minY, head.getCenter().z,
                neck.getCenter().x, neck.minY, neck.getCenter().z, cloud[0], cloud[1], cloudUp);
    }

    // the world for BedPairSearch, one cache per search (the same cells get asked a few hundred times)
    private static BedPairSearch.Terrain terrain(AltoClef mod) {
        Map<BlockPos, Boolean> airCache = new HashMap<>();
        Map<BlockPos, Boolean> solidCache = new HashMap<>();
        return new BedPairSearch.Terrain() {
            private boolean air(int x, int y, int z) {
                return airCache.computeIfAbsent(new BlockPos(x, y, z), p -> WorldHelper.isAir(mod, p));
            }

            private boolean solid(int x, int y, int z) {
                return solidCache.computeIfAbsent(new BlockPos(x, y, z), p -> WorldHelper.isSolid(mod, p));
            }

            @Override
            public boolean bedCell(int x, int y, int z) {
                return air(x, y, z) && solid(x, y - 1, z);
            }

            @Override
            public boolean standable(int x, int y, int z) {
                return air(x, y, z) && air(x, y + 1, z) && solid(x, y - 1, z);
            }
        };
    }

    // the live part boxes in DragonPerch terms. getSubEntities is head, neck, body, three tails, wing1, wing2
    private record Parts(List<DragonPerch.Box> eaters, List<DragonPerch.Box> pushes, List<DragonPerch.Box> biters) {
        static Parts of(EnderDragon dragon) {
            EnderDragonPart[] all = dragon.getSubEntities();
            DragonPerch.Box head = box(all[0].getBoundingBox());
            DragonPerch.Box neck = box(all[1].getBoundingBox());
            DragonPerch.Box body = box(all[2].getBoundingBox());
            return new Parts(List.of(head, neck, body),
                    List.of(DragonPerch.wingPush(box(all[6].getBoundingBox())), DragonPerch.wingPush(box(all[7].getBoundingBox()))),
                    List.of(DragonPerch.bite(head), DragonPerch.bite(neck)));
        }

        private static DragonPerch.Box box(AABB b) {
            return new DragonPerch.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        }
    }

    private static BlockPos pos(BedPairSearch.Cell c) {
        return new BlockPos(c.x(), c.y(), c.z());
    }

    private static boolean isBed(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
    }

    // the head half of the bed at this cell (either half)
    private static BlockPos bedHead(AltoClef mod, BlockPos half) {
        BlockState state = mod.getWorld().getBlockState(half);
        if (!(state.getBlock() instanceof BedBlock)) return half;
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) return half;
        return half.relative(state.getValue(BedBlock.FACING));
    }

    // mob defense skips what the fight owns, and an angry enderman too while we go for a bed (unless it is already on us,
    // then it is a fight like any other)
    private boolean ownedOrHeld(Entity entity) {
        if (KillEnderDragonTask.ownedByTheDragonFight(entity)) return true;
        if (!_holdStand || !(entity instanceof Enderman)) return false;
        return _mod == null || _mod.getPlayer() == null || entity.distanceTo(_mod.getPlayer()) > HOLD_STAND_MELEE;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getFoodChain().shouldStop(false);
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
        // same hand over as the sword task
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), _level);
        } else {
            mod.getBehaviour().releasePaused(this);
        }
        _level = null;
        mod.getBehaviour().pop();
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
    }

    // finished = out of beds and the dragon is not perched, the phase swaps to KillEnderDragonTask then. while perched we
    // stay: the last bed may be on the floor waiting for its click
    @Override
    public boolean isFinished(AltoClef mod) {
        return isActive() && !_perching && outOfBeds(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof KillEnderDragonWithBedsTask;
    }

    @Override
    protected String toHudString() {
        return "Fighting the Ender Dragon with beds";
    }

    @Override
    protected String toDebugString() {
        return "Bedding the Ender Dragon";
    }
}
