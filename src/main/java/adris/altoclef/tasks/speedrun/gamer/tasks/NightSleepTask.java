package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.NightSleep;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.BedSource;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Refusal;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Stage;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// one night, start to finish: get a bed, get it out of the monsters' reach when it has to be, sleep, take it back, come down.
// the rules (what counts as clear, how high, how long, what mob defense does in which stage) are NightSleep, this is the part
// that walks and clicks. GamerTask owns the budget and decides when to start one. a trip that finds one of our own beds still
// standing (state.nightBed) only takes that back and ends, the next trip is a fresh decision
public class NightSleepTask extends Task {
    public enum Outcome {
        SLEPT, GAVE_UP
    }

    private static final int INF = 1_000_000;
    // a bed we keep clicking that does not put us to sleep in this long said no in a way we did not hear
    private static final int CLICK_PATIENCE_TICKS = 100;
    private static final double TICK_SECONDS = 0.05;

    private final RunState state;
    private final Supplier<OverworldConfig> cfg;
    private final NightSleep.Budget budget;
    private final IntSupplier buildBlocks;
    private final Consumer<String> say;
    private final Runnable save;
    private final BedSource source;
    private final BlockPos found;
    // an old bed of ours is standing: this trip only takes it back
    private final boolean cleanup;

    private Stage stage;
    private long stageSince;
    private boolean begun;
    private boolean finished;
    private Outcome outcome = Outcome.GAVE_UP;
    private String why = "";

    // the cell the pillar began in (the way down ends there), and the top the last lift step aimed for
    private BlockPos ground;
    private BlockPos lastTop;
    private int lifts;
    private int closers;

    // the bed this try: the foot block is the one we write down as ours, the facing is the way the head points from it
    private Direction facing;
    private BlockPos foot;
    private long clickedAt = -1;
    private boolean wasAsleep;
    private long asleepTicks;
    // where PICKUP goes next when the refusal said to go higher
    private boolean liftAfterPickup;
    private int bedsBeforePickup;
    private boolean pickupArmed;

    private AltoClef mod;
    private volatile String overlay;
    private Subscription<GameOverlayEvent> overlayWatch;

    public NightSleepTask(RunState state, Supplier<OverworldConfig> cfg, NightSleep.Budget budget, IntSupplier buildBlocks,
                          Consumer<String> say, Runnable save, BedSource source, BlockPos found) {
        this.state = state;
        this.cfg = cfg;
        this.budget = budget;
        this.buildBlocks = buildBlocks;
        this.say = say;
        this.save = save;
        this.source = source;
        this.found = found;
        this.cleanup = state.nightBed != null;
    }

    // null once the trip is over. GamerTask hands this to mob defense every tick
    public Stage stage() {
        return finished ? null : stage;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean cleanupOnly() {
        return cleanup;
    }

    // ---- plumbing

    @Override
    protected void onStart(AltoClef mod) {
        overlay = null;
        overlayWatch = EventBus.subscribe(GameOverlayEvent.class, evt -> overlay = evt.message);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (overlayWatch != null) {
            EventBus.unsubscribe(overlayWatch);
            overlayWatch = null;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished;
    }

    @Override
    protected String toDebugString() {
        return "Sleeping through the night (" + stage + ")";
    }

    @Override
    protected String toHudString() {
        return "Sleeping through the night";
    }

    private void enter(Stage next, long now, String line) {
        stage = next;
        stageSince = now;
        Debug.logInternal("night: " + next + (line.isEmpty() ? "" : ", " + line));
    }

    private double secondsIn(long now) {
        return (now - stageSince) * TICK_SECONDS;
    }

    // ---- the tick

    @Override
    protected Task onTick(AltoClef mod) {
        this.mod = mod;
        if (finished) {
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        if (!cleanup) {
            budget.spend(TICK_SECONDS);
        }
        if (!begun) {
            begun = true;
            begin(mod, now);
            if (finished || stage == null) {
                return null;
            }
        }
        if (NightSleep.shouldAbandon(player.getHealth())) {
            // normal defense has the wheel from this tick on. a bed left standing is taken back on a later trip
            if (player.isSleeping()) {
                getUp(player);
            }
            fail("low health", now);
            finish(now);
            return null;
        }
        if (!cleanup && budget.seconds() >= cfg.get().nightSeconds
                && stage != Stage.PICKUP && stage != Stage.DESCEND && stage != Stage.SLEEP) {
            fail("out of time for tonight", now);
            return null;
        }
        return switch (stage) {
            case CRAFT -> craft(mod, now);
            case WALK -> walk(mod, now);
            case LIFT -> lift(mod, now);
            case BUILD -> build(mod, now);
            case SLEEP -> sleep(mod, now);
            case PICKUP -> pickup(mod, now);
            case DESCEND -> descend(mod, now);
        };
    }

    private void begin(AltoClef mod, long now) {
        stageSince = now;
        if (cleanup) {
            enter(Stage.PICKUP, now, "an old bed of ours is still standing");
            return;
        }
        say.accept("Night is here, going to bed");
        switch (source) {
            case FOUND -> enter(Stage.WALK, now, "a bed at " + found.toShortString());
            case BAG -> afterBed(mod, now);
            case CRAFT -> enter(Stage.CRAFT, now, "making one");
            default -> {
                fail("no bed", now);
            }
        }
    }

    // ---- getting the bed

    private int bedsInBag(AltoClef mod) {
        return mod.getItemStorage().getItemCount(ItemHelper.BED);
    }

    public static int bestWoolStack(AltoClef mod) {
        int best = 0;
        for (Item wool : ItemHelper.WOOL) {
            best = Math.max(best, mod.getItemStorage().getItemCount(wool));
        }
        return best;
    }

    private Task craft(AltoClef mod, long now) {
        if (bedsInBag(mod) > 0) {
            afterBed(mod, now);
            return null;
        }
        // the catalogue would hunt a sheep for missing wool, and this round it does not get to
        if (NightSleep.bedSource(false, 0, bestWoolStack(mod), mod.getItemStorage().getItemCount(ItemHelper.PLANKS)) != BedSource.CRAFT) {
            fail("the wool or the planks are gone", now);
            return null;
        }
        if (NightSleep.timedOut(Stage.CRAFT, 0, secondsIn(now))) {
            fail("the bed took too long to craft", now);
            return null;
        }
        setDebugState("Crafting a bed.");
        return TaskCatalogue.getItemTask("bed", 1);
    }

    private Task walk(AltoClef mod, long now) {
        BlockPos bed = found;
        if (!(mod.getWorld().getBlockState(bed).getBlock() instanceof BedBlock)) {
            fail("the bed we were walking to is gone", now);
            return null;
        }
        if (NightSleep.timedOut(Stage.WALK, 0, secondsIn(now))) {
            fail("could not get to the bed", now);
            return null;
        }
        foot = WorldHelper.getBedFoot(mod, bed);
        BlockPos head = WorldHelper.getBedHead(mod, bed);
        if (monstersBlock(mod, head)) {
            // a bed that is already standing cannot be lifted, ours can: this one is not worth it
            fail("monsters by the bed", now);
            return null;
        }
        if (bed.closerToCenterThan(mod.getPlayer().position(), 2.5)) {
            enter(Stage.SLEEP, now, "at the bed");
            return null;
        }
        setDebugState("Walking to the bed.");
        return new GetToBlockTask(bed);
    }

    // a bed in hand: pillar first if something is in the way, otherwise put it down here
    private void afterBed(AltoClef mod, long now) {
        BlockPos feet = mod.getPlayer().blockPosition();
        if (ground == null) {
            ground = feet;
        }
        int raise = raiseAt(mod, feet);
        if (raise < 0) {
            fail("the monsters are too high to get above", now);
        } else if (raise > 0) {
            if (roomToRaise(mod, feet, raise, now)) {
                lastTop = null;
                enter(Stage.LIFT, now, "need " + raise + " blocks up");
            }
        } else {
            facing = null;
            enter(Stage.BUILD, now, "clear where we stand");
        }
    }

    // ---- monsters

    private static List<NightSleep.Mob> mobsAround(AltoClef mod, BlockPos at) {
        double r = NightSleep.COMING_RANGE;
        AABB box = new AABB(at.getX() + 0.5 - r, at.getY() - 24, at.getZ() + 0.5 - r, at.getX() + 0.5 + r, at.getY() + 24, at.getZ() + 0.5 + r);
        // 1.21.2 made isPreventingPlayerRest want a ServerLevel we do not have. every monster says true except zombified piglins,
        // who want to be angry at us
        List<Monster> near = mod.getWorld().getEntitiesOfClass(Monster.class, box,
                m -> m.isAlive() && (!(m instanceof ZombifiedPiglin) || EntityHelper.isAngryAtPlayer(mod, m)));
        List<NightSleep.Mob> out = new ArrayList<>(near.size());
        for (Monster m : near) {
            AABB b = m.getBoundingBox();
            out.add(new NightSleep.Mob(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        return out;
    }

    // the game's own check at the click, for a head block that is already there
    public static boolean monstersBlock(AltoClef mod, BlockPos head) {
        return NightSleep.blocked(head.getX(), head.getY(), head.getZ(), mobsAround(mod, head));
    }

    // blocks to go up from the cell we stand in, for a bed whose head will end up two cells off at this height
    private int raiseAt(AltoClef mod, BlockPos feet) {
        OverworldConfig c = cfg.get();
        return NightSleep.blocksToRaise(feet.getX(), feet.getY(), feet.getZ(), mobsAround(mod, feet), c.nightClearMargin, c.nightMaxPillar);
    }

    // the column has to be open, dry and we need the blocks. a no is a failed try
    private boolean roomToRaise(AltoClef mod, BlockPos feet, int raise, long now) {
        ClientLevel world = mod.getWorld();
        if (mod.getPlayer().isInWater() || mod.getPlayer().isInLava() || !world.getFluidState(feet.below()).isEmpty()) {
            fail("standing in liquid", now);
            return false;
        }
        int open = 0;
        for (int i = 1; i <= raise + 2; i++) {
            BlockPos up = feet.above(i);
            BlockState s = world.getBlockState(up);
            if (!s.getCollisionShape(world, up).isEmpty() || !s.getFluidState().isEmpty()) {
                break;
            }
            open++;
        }
        if (NightSleep.roofAt(raise, open)) {
            fail("a roof is in the way of the pillar", now);
            return false;
        }
        int have = buildBlocks.getAsInt();
        int need = NightSleep.blocksNeeded(raise);
        if (have < need) {
            fail("not enough blocks for the pillar (" + have + " of " + need + ")", now);
            return false;
        }
        return true;
    }

    // ---- the pillar

    private Task lift(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        BlockPos feet = player.blockPosition();
        int raise = raiseAt(mod, feet);
        if (raise < 0) {
            fail("the monsters are too high to get above", now);
            return null;
        }
        boolean standing = player.onGround() && world.getBlockState(feet.below()).isFaceSturdy(world, feet.below(), Direction.UP);
        if (raise == 0 && standing) {
            facing = null;
            enter(Stage.BUILD, now, "high enough at y=" + feet.getY());
            return null;
        }
        int planned = Math.max(1, (lastTop == null ? feet.above(Math.max(raise, 1)) : lastTop).getY() - (ground == null ? feet.getY() : ground.getY()));
        if (NightSleep.timedOut(Stage.LIFT, planned, secondsIn(now))) {
            fail("the pillar went nowhere", now);
            return null;
        }
        if (buildBlocks.getAsInt() < 1) {
            fail("out of blocks on the way up", now);
            return null;
        }
        // mid jump the count can read zero for a tick before the block is down, keep going where we were going
        BlockPos top = raise == 0 && lastTop != null ? lastTop : feet.above(Math.max(raise, 1));
        lastTop = top;
        setDebugState("Pillaring up to get away from the monsters.", "Getting above the monsters");
        return new PillarHereTask(top);
    }

    // ---- the bed

    private Task build(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        BlockPos stand = player.blockPosition();
        if (NightSleep.timedOut(Stage.BUILD, 0, secondsIn(now))) {
            fail("the bed would not go down", now);
            return null;
        }
        // monsters came while we were building and we have not been up yet: start over from here, higher
        if (foot == null || !(world.getBlockState(foot).getBlock() instanceof BedBlock)) {
            int raise = raiseAt(mod, stand);
            if (raise > 0 && lifts < 1) {
                lifts++;
                if (roomToRaise(mod, stand, raise, now)) {
                    lastTop = null;
                    enter(Stage.LIFT, now, "monsters showed up, need " + raise + " blocks");
                }
                return null;
            }
        }
        if (facing == null) {
            facing = bestFacing(mod, stand);
            if (facing == null) {
                fail("no room for a bed here", now);
                return null;
            }
        }
        BlockPos footCell = stand.relative(facing);
        BlockPos headCell = footCell.relative(facing);
        if (world.getBlockState(footCell).getBlock() instanceof BedBlock) {
            foot = footCell;
            state.nightBed = new RunState.Pos(footCell.getX(), footCell.getY(), footCell.getZ());
            save.run();
            clickedAt = -1;
            enter(Stage.SLEEP, now, "bed is down at " + footCell.toShortString());
            return null;
        }
        for (BlockPos cell : new BlockPos[]{footCell, headCell, footCell.above(), headCell.above()}) {
            if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                setDebugState("Clearing room for the bed.");
                return new DestroyBlockTask(cell);
            }
        }
        // the game has no support rule for a bed (the head can hang in the air), we only need something to click the foot onto
        BlockPos support = footCell.below();
        if (!world.getBlockState(support).isFaceSturdy(world, support, Direction.UP)) {
            if (buildBlocks.getAsInt() < 1) {
                fail("no blocks left to hold the bed up", now);
                return null;
            }
            setDebugState("Putting something under the bed.");
            return new PlaceBlockTask(support, new Block[]{}, true, false);
        }
        setDebugState("Putting the bed down.", "Putting the bed down");
        return new InteractWithBlockTask(new ItemTarget("bed", 1), Direction.UP, support, false);
    }

    // the direction with the least work: cells to clear, a support to place. null when none can be done at all
    private Direction bestFacing(AltoClef mod, BlockPos stand) {
        Direction best = null;
        int bestCost = INF;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int cost = siteCost(mod, stand, d);
            if (cost < bestCost) {
                bestCost = cost;
                best = d;
            }
        }
        return best;
    }

    // the foot, the head and the air over both have to be free (the game refuses an obstructed bed), liquid is a no
    private int siteCost(AltoClef mod, BlockPos stand, Direction d) {
        ClientLevel world = mod.getWorld();
        BlockPos footCell = stand.relative(d);
        BlockPos headCell = footCell.relative(d);
        int cost = 0;
        for (BlockPos cell : new BlockPos[]{footCell, headCell, footCell.above(), headCell.above()}) {
            BlockState s = world.getBlockState(cell);
            if (!s.getFluidState().isEmpty()) {
                return INF;
            }
            if (!s.getCollisionShape(world, cell).isEmpty()) {
                if (!WorldHelper.canBreak(mod, cell) || WorldHelper.tooSlowToBreak(mod, cell)) {
                    return INF;
                }
                cost += 2;
            }
        }
        BlockPos support = footCell.below();
        if (!world.getBlockState(support).isFaceSturdy(world, support, Direction.UP)) {
            if (buildBlocks.getAsInt() < 1) {
                return INF;
            }
            cost += 3;
        }
        return cost;
    }

    // ---- sleeping

    private Task sleep(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        boolean asleep = player.isSleeping() || Minecraft.getInstance().screen instanceof InBedChatScreen;
        if (asleep) {
            wasAsleep = true;
            asleepTicks++;
            setDebugState("Sleeping.", "Sleeping");
            if (asleepTicks > cfg.get().nightSleepSeconds * 20) {
                // the night is not passing (somebody else is awake), no point lying here
                getUp(player);
                fail("the night did not pass", now);
            }
            return null;
        }
        if (wasAsleep) {
            // woken: by the morning, or by something that hit us
            if (NightSleep.sleptThrough(asleepTicks, world.isDay())) {
                budget.sleptOk();
                outcome = Outcome.SLEPT;
                say.accept("Slept through the night");
                wrapUp(mod, now);
            } else {
                fail("woken before the night passed", now);
            }
            return null;
        }
        if (foot == null || !(world.getBlockState(foot).getBlock() instanceof BedBlock)) {
            fail("the bed is gone", now);
            return null;
        }
        BlockPos head = WorldHelper.getBedHead(mod, foot);
        Refusal refusal = NightSleep.refusal(overlay);
        if (refusal != Refusal.NONE) {
            overlay = null;
            return refused(mod, refusal, now);
        }
        if (monstersBlock(mod, head)) {
            // the game would say no. ask the same question first and skip the click
            return refused(mod, Refusal.MONSTERS, now);
        }
        if (clickedAt < 0) {
            clickedAt = now;
        } else if (now - clickedAt > CLICK_PATIENCE_TICKS) {
            fail("the bed would not take us", now);
            return null;
        }
        setDebugState("Getting into bed.", "Going to bed");
        return new InteractWithBlockTask(head);
    }

    private Task refused(AltoClef mod, Refusal refusal, long now) {
        NightSleep.Next next = NightSleep.afterRefusal(refusal, lifts, closers);
        Debug.logInternal("night: refused (" + refusal + "), " + next);
        if (next == NightSleep.Next.CLOSER) {
            closers++;
            clickedAt = -1;
            return new GetToBlockTask(foot);
        }
        if (next == NightSleep.Next.HIGHER && state.nightBed != null) {
            // our own bed comes up, then the whole plan again from higher up
            lifts++;
            clickedAt = -1;
            liftAfterPickup = true;
            enter(Stage.PICKUP, now, "monsters by the bed, going higher");
            return null;
        }
        fail("the bed said no (" + refusal.name().toLowerCase() + ")", now);
        return null;
    }

    private void getUp(LocalPlayer player) {
        if (player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        }
    }

    // ---- afterwards

    private Task pickup(AltoClef mod, long now) {
        ClientLevel world = mod.getWorld();
        RunState.Pos at = state.nightBed;
        if (at == null) {
            afterPickup(mod, now);
            return null;
        }
        BlockPos bed = new BlockPos(at.x, at.y, at.z);
        boolean standing = world.getBlockState(bed).getBlock() instanceof BedBlock;
        if (!pickupArmed) {
            pickupArmed = true;
            bedsBeforePickup = bedsInBag(mod);
        }
        if (NightSleep.timedOut(Stage.PICKUP, 0, secondsIn(now))) {
            // our own try gets one more go on a later trip (nightBed stays set), a second time round it is not worth chasing
            Debug.logInternal("night: could not take the bed back, leaving it at " + bed.toShortString());
            if (cleanup) {
                state.nightBed = null;
                save.run();
            }
            afterPickup(mod, now);
            return null;
        }
        if (standing) {
            if (!bed.closerToCenterThan(mod.getPlayer().position(), 4)) {
                if (!bed.closerToCenterThan(mod.getPlayer().position(), cfg.get().nightBedRadius)) {
                    // too far to walk back for, and not ours to keep a claim on any more
                    state.nightBed = null;
                    save.run();
                    afterPickup(mod, now);
                    return null;
                }
                setDebugState("Going back for the bed.");
                return new GetToBlockTask(bed);
            }
            setDebugState("Taking the bed back.", "Taking the bed back");
            return new DestroyBlockTask(bed);
        }
        if (bedsInBag(mod) > bedsBeforePickup) {
            state.nightBed = null;
            save.run();
            afterPickup(mod, now);
            return null;
        }
        // broken, the item is lying where it stood
        setDebugState("Picking the bed up.");
        return new PickupDroppedItemTask(new ItemTarget(ItemHelper.BED, bedsBeforePickup + 1), false);
    }

    private void afterPickup(AltoClef mod, long now) {
        pickupArmed = false;
        foot = null;
        if (liftAfterPickup) {
            liftAfterPickup = false;
            if (state.nightBed == null) {
                afterBed(mod, now);
            } else {
                // it would not come up, a second bed beside it is not the answer
                fail("could not take the bed back to move it", now);
            }
            return;
        }
        wrapUp(mod, now);
    }

    // the sleep is over or the try is: the bed comes back first, then the way down
    private void wrapUp(AltoClef mod, long now) {
        if (state.nightBed != null && stage != Stage.PICKUP) {
            enter(Stage.PICKUP, now, "taking the bed back");
            return;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        if (ground != null && feet.getY() > ground.getY() + 1) {
            enter(Stage.DESCEND, now, "back down to y=" + ground.getY());
            return;
        }
        finish(now);
    }

    private Task descend(AltoClef mod, long now) {
        BlockPos feet = mod.getPlayer().blockPosition();
        int blocks = ground == null ? 0 : Math.max(0, feet.getY() - ground.getY());
        if (ground == null || feet.getY() <= ground.getY() + 1 || NightSleep.timedOut(Stage.DESCEND, blocks, secondsIn(now))) {
            finish(now);
            return null;
        }
        setDebugState("Coming down off the pillar.", "Coming down");
        return new NightDescendTask(ground);
    }

    // ---- ways out

    // this try is over and did not work: the cooldown starts, the bed (if we put one down) comes back, the pillar (if we built
    // one) is walked down. low health skips all of that, see onTick
    private void fail(String reason, long now) {
        why = reason;
        outcome = Outcome.GAVE_UP;
        if (!cleanup) {
            budget.failed(now * TICK_SECONDS, cfg.get().nightCooldownSeconds);
            Debug.logInternal("night: gave up this try, " + reason);
        }
        if (mod == null || mod.getPlayer() == null) {
            finish(now);
            return;
        }
        wrapUp(mod, now);
    }

    private void finish(long now) {
        if (finished) {
            return;
        }
        finished = true;
        if (outcome == Outcome.GAVE_UP && !why.isEmpty() && !cleanup) {
            say.accept("Not sleeping tonight: " + why);
        }
        Debug.logInternal("night: over, " + outcome + (why.isEmpty() ? "" : " (" + why + ")"));
    }
}
