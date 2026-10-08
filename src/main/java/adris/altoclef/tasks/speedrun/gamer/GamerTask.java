package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EndPrepPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EyesPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.LocatePhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherRegress;
import adris.altoclef.tasks.speedrun.gamer.phases.OpenPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.PortalPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.ReturnPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.RoomPhase;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherRecoverTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverItemsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// beats the game: a phase state machine over PhaseHandlers with its memory in RunState (see gamer-design.md). this class is
// the part that touches the game: facts, deaths, saving, the hud and the settings for the run. which phase we are in and
// when it gives up is PhaseMachine, which does not know there is a game. nothing in here is static, two runs never share anything
public class GamerTask extends Task {
    // what a full inventory (altoThrowAwayUnusedItems, which the run forces on) must never throw away: the gear and
    // materials the End needs, wool until it is beds, the stuff the next phases still have to smelt, trade and craft with
    private static final Item[] PROTECTED = protectedItems();
    private static final Block[] TRACKED = ArrayUtils.addAll(new Block[]{
            Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL, Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.SPAWNER,
            Blocks.NETHER_PORTAL}, ItemHelper.itemsToBlocks(ItemHelper.BED));
    // the blocks we want to bridge and pillar with, on top of whatever the user listed
    private static final Item[] BUILD_BLOCKS = {Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.END_STONE, Items.COBBLED_DEEPSLATE};

    private static final double SAVE_EVERY_SECONDS = 20;
    // an interrupt (mob defense flickering on and off) saves at most this often, wall clock
    private static final long INTERRUPT_SAVE_MILLIS = 5000;
    private static final double RECOVER_BUDGET_SECONDS = 90;
    // the saved clock is further ahead than the world's own: the world was reset or this is another one
    private static final long CLOCK_SLACK_TICKS = 200;

    private final PhaseMachine machine;
    private final GamerPhase startAt;
    private final Host host = new Host();

    private AltoClef mod;
    private MinecraftFacts facts;
    private GamerConfig cfg;
    private RunState state;
    private Path statePath;
    // false until the first tick with a player loaded the run state. onStart runs again after every interrupt (eating, mob
    // defense) and that is only the task coming back, never a new run
    private boolean begun;
    // machine.now() when another chain took the wheel, -1 while we have it
    private double interruptedAt = -1;
    // set by loadState when the saved run carries on as it was, so the attempt clocks keep their real start
    private boolean resumedAsIs;
    private boolean pushed;
    private BotBehaviour.State level;
    // watches for crafting tables and furnaces we place, so PrepSupport only ever takes back its own (OwnTables)
    private Subscription<BlockPlaceEvent> placeWatch;
    // the engine owns the end portal walk flag: whatever a handler asked for last is put back on every (re)start
    private boolean wantWalkOnPortal;

    private double lastSaveSeconds;
    private long lastWriteMillis;
    private String lastWritten = "";

    // death tracking
    private LocalPlayer lastPlayer;
    private boolean deadSeen;
    private Dimension deathDimension;
    private BlockPos deathPos;
    private long deathGameTime;
    private NetherTripRules.Cause deathCause = NetherTripRules.Cause.OTHER;
    private RecoverItemsTask recover;
    // the walk back into the nether for a pile we left there, alive for as long as state.netherTrip is set
    private NetherRecoverTask netherTrip;
    // deaths on the books when this run was (re)started by hand, so the run wide cap only counts the new ones
    private int deathsAtStart;

    // what the user had before we changed it for the run, to hand back on stop
    private Boolean userBlastFurnace;
    private Boolean userThrowUnused;
    private Boolean userLadderClutch;
    private List<Item> userThrowaway;
    private List<Item> userFuels;

    // hud text is only rebuilt when something in it changed
    private PhaseHandler hudHandler;
    private String hudState;
    private int hudAttempt;
    private String hudDebug = "";
    private String hudText = "";

    public GamerTask() {
        this(null);
    }

    // startAt = dev jump (#gamer phase x): begin there whatever the saved run says
    public GamerTask(GamerPhase startAt) {
        this.startAt = startAt;
        List<PhaseHandler> handlers = List.of(new GatherPhase(), new IronPhase(), new PortalPhase(), new NetherPhase(),
                new EyesPhase(), new ReturnPhase(), new LocatePhase(), new RoomPhase(), new OpenPhase(), new EndPrepPhase(),
                new DragonPhase());
        machine = new PhaseMachine(handlers, host);
    }

    private static Item[] protectedItems() {
        Item[] own = {
                Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.BUCKET, Items.WATER_BUCKET,
                Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.CRAFTING_TABLE, Items.OBSIDIAN, Items.SHIELD,
                Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.IRON_AXE, Items.DIAMOND_AXE,
                Items.IRON_INGOT, Items.RAW_IRON, Items.GOLD_INGOT, Items.DIAMOND, Items.COAL, Items.CHARCOAL, Items.FLINT,
                Items.SHEARS, Items.CARVED_PUMPKIN, Items.GOLDEN_BOOTS, Items.GOLDEN_HELMET, Items.GOLD_BLOCK, Items.GOLD_NUGGET,
                Items.RAW_GOLD, Items.LADDER};
        Item[] all = own;
        for (Item[] more : new Item[][]{ItemHelper.BED, ItemHelper.WOOL, ItemHelper.IRON_ARMORS, ItemHelper.DIAMOND_ARMORS, ItemHelper.GOLDEN_ARMORS}) {
            all = ArrayUtils.addAll(all, more);
        }
        return all;
    }

    // ---- task plumbing

    // runs again every time the task comes back from an interrupt, so it only puts things back that are not there
    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        if (facts == null) {
            facts = new MinecraftFacts(mod);
        }
        if (!pushed) {
            applyBehaviour(mod);
        }
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(wantWalkOnPortal);
        // back from an interrupt: same run, same phase, the stall timer should not blame the chain that had the wheel. the
        // facts are stale in here (only engineTick refreshes them), so the forgiving happens on the first real tick
    }

    // a pause (another chain has the wheel for a moment) keeps the item protection, the tracked blocks and the settings, it
    // is exactly then that a full inventory could lose something. only a real stop lets go of them
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (isInterrupting()) {
            if (begun && interruptedAt < 0) {
                interruptedAt = machine.now();
                // a recovery that got interrupted stops giving time back, the excuse below covers the stall
                machine.releaseHold();
            }
            softSave();
            return;
        }
        endRun(mod);
    }

    // #stop while we were paused: Task.stop does not call onStop for that, this is its replacement
    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        endRun(mod);
    }

    private void endRun(AltoClef mod) {
        try {
            if (state != null && begun) {
                host.save();
                machine.exitCurrent(mod);
            }
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: onStop " + e);
        } finally {
            begun = false;
            interruptedAt = -1;
            // the food task must stop seeing our jobs once we are gone
            AsyncSmelting.clear();
            CookTrip.clear();
            stopWatchingPlacements();
            releaseBehaviour(mod);
        }
    }

    // exceptions from the child the handler handed back are thrown by Task.tick after our onTick returned, outside every
    // net the machine has. same road as a handler that throws, and the broken child goes away so a retry builds a new one
    @Override
    public void tick(AltoClef mod, TaskChain parentChain) {
        try {
            super.tick(mod, parentChain);
        } catch (RuntimeException e) {
            if (!begun || machine.current() == null) {
                throw e;
            }
            if (state.netherTrip != null) {
                // the trip's own child threw, not the phase. the phase machine is not ticking while the trip has the wheel,
                // so a trip left standing would throw again every tick until its six minutes ran out
                host.say("gave up: the trip hit an error (" + e.getClass().getSimpleName() + ")"
                        + (NetherRegress.kitShort(facts, cfg) ? ", rebuilding" : ", carrying on"));
                state.netherTrip = null;
                netherTrip = null;
                host.save();
                dropChild(mod);
                return;
            }
            machine.failFromChild(e);
            dropChild(mod);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GamerTask;
    }

    @Override
    protected String toDebugString() {
        return "Beat the game";
    }

    @Override
    protected String toHudString() {
        return "Beating the game";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (state == null) {
            return false;
        }
        // credits can show up between two of our ticks (the dragon dies, the screen opens), record it now so a task that
        // is finished but never stopped (altoRunsWhenIdle) still leaves a saved DONE behind
        if (begun && !machine.ended() && Minecraft.getInstance().screen instanceof WinScreen) {
            machine.finish(true);
        }
        return machine.ended();
    }

    // ---- behaviour and settings for the run

    private void applyBehaviour(AltoClef mod) {
        // kept so the cobble floor lands in our level and not in whichever child is on top this tick
        level = mod.getBehaviour().push();
        pushed = true;
        mod.getBehaviour().addProtectedItems(PROTECTED);
        mod.getBlockTracker().trackBlock(TRACKED);
        applyRunSettings();
    }

    // the cobble the stone kit still eats stays spoken for in our own level, whatever task level is on top. a craft only
    // reserves its own recipe, so the sword and the furnace got built into the staircase and mined again. the task reserves
    // are slices of this need, BotBehaviour takes the bigger of the two and does not add them. only GATHER and IRON have
    // a stone kit, and only plain cobble: the build blocks the later phases place on purpose are not ours to hold back
    private void updateStoneFloor(AltoClef mod) {
        if (level == null || state == null) {
            return;
        }
        boolean kitPhase = state.phase == GamerPhase.GATHER || state.phase == GamerPhase.IRON;
        int cobble = kitPhase ? KitPlanner.stoneFloor(facts, cfg.overworld) : 0;
        mod.getBehaviour().setReserveFloor(level, cobble > 0 ? Map.of(Items.COBBLESTONE, cobble) : Map.of());
    }

    private void releaseBehaviour(AltoClef mod) {
        adris.altoclef.util.helpers.FuelPolicy.clear();
        try {
            mod.getBlockTracker().stopTracking(TRACKED);
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: stopTracking " + e);
        }
        try {
            wantWalkOnPortal = false;
            mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
            releaseRunSettings();
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: releasing settings " + e);
        }
        if (level != null) {
            // empty floor first: if the pop below lands on a different level the cobble must not stay spoken for
            mod.getBehaviour().setReserveFloor(level, Map.of());
            level = null;
        }
        if (pushed) {
            pushed = false;
            mod.getBehaviour().pop();
        }
    }

    // a blast furnace costs five iron we want for armor, and the build blocks have to be throwaway or a full inventory eats
    // the end stone we bridge with. the user's values come back on stop (put with their own value is "let go" in the registry)
    private void applyRunSettings() {
        Settings s = Baritone.settings();
        if (!SettingsOverrides.isHeld(s.altoUseBlastFurnace)) {
            userBlastFurnace = s.altoUseBlastFurnace.value;
        }
        if (!SettingsOverrides.isHeld(s.altoThrowAwayUnusedItems)) {
            userThrowUnused = s.altoThrowAwayUnusedItems.value;
        }
        if (!SettingsOverrides.isHeld(s.altoThrowawayItems)) {
            userThrowaway = s.altoThrowawayItems.value;
        }
        if (!SettingsOverrides.isHeld(s.allowLadderClutch)) {
            userLadderClutch = s.allowLadderClutch.value;
        }
        if (!SettingsOverrides.isHeld(s.altoSupportedFuels)) {
            userFuels = s.altoSupportedFuels.value;
        }
        // the kit chops spare logs for the cook (cookFuelLogs) and WoodReserve decides which ones may burn, but the default list is
        // coal and charcoal only: the cook gate counted the logs and the smoker refused them with the meat already in it
        SettingsOverrides.put(s.altoSupportedFuels, withWoodFuel(userFuels != null ? userFuels : s.altoSupportedFuels.value));
        SettingsOverrides.put(s.altoUseBlastFurnace, false);
        SettingsOverrides.put(s.altoThrowAwayUnusedItems, true);
        // the iron kit has ladders in it for a reason, a long fall in the nether is what they are for
        SettingsOverrides.put(s.allowLadderClutch, true);
        SettingsOverrides.put(s.altoThrowawayItems, withBuildBlocks(userThrowaway != null ? userThrowaway : s.altoThrowawayItems.value));
    }

    private static List<Item> withBuildBlocks(List<Item> list) {
        List<Item> out = new ArrayList<>(list);
        for (Item item : BUILD_BLOCKS) {
            if (!out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    // logs and planks on top of the user's fuels. FuelPolicy keeps the wood the kit still wants out of the furnace, so this only
    // lets the spare burn. the crimson stems in the log list burn nothing and stay out
    static List<Item> withWoodFuel(List<Item> list) {
        List<Item> out = new ArrayList<>(list);
        for (Item[] group : new Item[][]{ItemHelper.LOG, ItemHelper.PLANKS}) {
            for (Item item : group) {
                if (ItemHelper.isFuel(item) && !out.contains(item)) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    private void releaseRunSettings() {
        Settings s = Baritone.settings();
        if (userFuels != null) {
            SettingsOverrides.put(s.altoSupportedFuels, userFuels);
        }
        if (userBlastFurnace != null) {
            SettingsOverrides.put(s.altoUseBlastFurnace, userBlastFurnace);
        }
        if (userThrowUnused != null) {
            SettingsOverrides.put(s.altoThrowAwayUnusedItems, userThrowUnused);
        }
        if (userLadderClutch != null) {
            SettingsOverrides.put(s.allowLadderClutch, userLadderClutch);
        }
        if (userThrowaway != null) {
            SettingsOverrides.put(s.altoThrowawayItems, userThrowaway);
        }
    }

    // ---- state

    // first tick with a player: the facts are real now, so the clocks and the saved run can be compared with the world
    private void beginRun(AltoClef mod) {
        cfg = GamerConfigs.get();
        // a job from a run before this one is not ours to collect
        AsyncSmelting.clear();
        CookTrip.clear();
        loadState(mod);
        facts.useState(state);
        // so CollectFoodTask can count the meat that is cooking without knowing what a RunState is
        AsyncSmelting.watchJobs(facts::furnaceJobs);
        // and so the smelt task knows a furnace in the tracker is the one we put down (and not a reason to place another)
        // (smokers too, the smoker task asks the same question)
        AsyncSmelting.watchFurnaces(p -> {
            RunState.Pos at = new RunState.Pos(p.getX(), p.getY(), p.getZ());
            return state.placedFurnaces.contains(at) || state.placedSmokers.contains(at);
        });
        deathsAtStart = state.deaths.size();
        lastSaveSeconds = machine.now();
        begun = true;
        watchPlacements();
        machine.begin(mod, resumedAsIs);
    }

    private void watchPlacements() {
        if (placeWatch != null) {
            return;
        }
        placeWatch = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            // the hook publishes every conducting block that appears on the client level, so this is a guess about who put
            // it there: a crafting table or furnace, in the overworld, inside our own placing reach
            LocalPlayer player = Minecraft.getInstance().player;
            if (state == null || !begun || player == null || facts == null || facts.dimension() != Dimension.OVERWORLD
                    || !(evt.blockState.is(Blocks.CRAFTING_TABLE) || evt.blockState.is(Blocks.FURNACE) || evt.blockState.is(Blocks.SMOKER)
                    || isJobBlock(evt.blockState))) {
                return;
            }
            RunState.Pos pos = new RunState.Pos(evt.blockPos.getX(), evt.blockPos.getY(), evt.blockPos.getZ());
            if (evt.blockState.is(Blocks.SMOKER)) {
                // on the list of ours (a village's smoker never is). it comes down through FurnaceWatch once its cook job is
                // collected, and through StationPickup when no job ever got to own it
                if (OwnTables.placedByUs(player.getX(), player.getEyeY(), player.getZ(), pos)) {
                    state.smokerUse.lastPlaceTick = facts.gameTime();
                    state.smokerUse.useNeed = state.currentNeed;
                    if (OwnTables.record(state.placedSmokers, pos)) {
                        Debug.logInternal("smoker recorded at " + pos.x + " " + pos.y + " " + pos.z);
                    }
                }
            } else if (isJobBlock(evt.blockState)) {
                // same guess as the table below. VillageLoot must not take a blast furnace we crafted for a village
                if (OwnTables.placedByUs(player.getX(), player.getEyeY(), player.getZ(), pos)) {
                    OwnTables.record(state.placedJobBlocks, pos);
                }
            } else if (OwnTables.placedByUs(player.getX(), player.getEyeY(), player.getZ(), pos)) {
                // a station that just went down is about to be used by the need that placed it, so the pickup waits for the
                // next need (and a few seconds, for the debounce)
                boolean furnace = evt.blockState.is(Blocks.FURNACE);
                RunState.StationUse use = furnace ? state.furnaceUse : state.tableUse;
                use.lastPlaceTick = facts.gameTime();
                use.useNeed = state.currentNeed;
                if (OwnTables.record(furnace ? state.placedFurnaces : state.placedTables, pos)) {
                    // we had no way to tell which gate kept a pickup from happening, so the start of the story goes in the log
                    Debug.logInternal((furnace ? "furnace" : "table") + " recorded at " + pos.x + " " + pos.y + " " + pos.z);
                }
            }
        });
    }

    private static boolean isJobBlock(BlockState block) {
        return block.is(Blocks.BLAST_FURNACE) || block.is(Blocks.GRINDSTONE) || block.is(Blocks.SMITHING_TABLE);
    }

    private void stopWatchingPlacements() {
        if (placeWatch != null) {
            EventBus.unsubscribe(placeWatch);
            placeWatch = null;
        }
    }

    private void loadState(AltoClef mod) {
        statePath = RunStateStore.resolvePath(mod);
        if (RunStateStore.isSharedFallback(statePath)) {
            host.say("No world folder to save into yet, the run state goes to a file shared by all worlds");
        }
        RunStateStore.Loaded loaded = RunStateStore.load(statePath, RunStateStore.fingerprint());
        state = loaded.state();
        boolean resumed = loaded.resumed();
        resumedAsIs = false;
        if (resumed && state.phaseEnteredGameTime > facts.gameTime() + CLOCK_SLACK_TICKS) {
            // game time only ever goes forward inside one world, so this file was written in another one (or the same one
            // before it was reset). not ours to resume
            host.say("The saved run is from a world that has been reset, starting over");
            String fingerprint = state.fingerprint;
            state = new RunState();
            state.fingerprint = fingerprint;
            resumed = false;
        }
        if (state.startedEpochMs == 0) {
            state.startedEpochMs = System.currentTimeMillis();
        }
        if (state.phase == GamerPhase.STUCK) {
            // never written by us, but a hand edit could, and there is no handler for it
            state.phase = GamerPhase.GATHER;
        }
        if (startAt != null) {
            jumpTo(startAt);
        } else if (resumed) {
            resumeSaved();
        }
    }

    private void jumpTo(GamerPhase phase) {
        state.phase = phase;
        state.stuck = false;
        state.stuckReason = "";
        state.finished = false;
        // a dev jump is the user taking the wheel, a half done trip from before it is not theirs
        state.netherTrip = null;
        netherTrip = null;
        state.phaseAttempts.put(phase.name(), 1);
        state.deathsThisPhase = 0;
        state.regressCounts.clear();
        host.say("Starting at: " + phase.hud());
    }

    private void resumeSaved() {
        if (state.finished || state.phase == GamerPhase.DONE) {
            state.phase = GamerPhase.DONE;
            state.finished = true;
            host.say("This world's run is already finished. #gamer reset starts a new one.");
            return;
        }
        if (state.stuck) {
            host.say("Resuming after getting stuck (" + state.stuckReason + ")");
            state.stuck = false;
            state.stuckReason = "";
            state.deathsThisPhase = 0;
            state.regressCounts.clear();
            state.phaseAttempts.put(state.phase.name(), 1);
        } else {
            host.say("Resuming at: " + state.phase.hud());
            resumedAsIs = true;
        }
        if (state.attemptsOf(state.phase) < 1) {
            state.phaseAttempts.put(state.phase.name(), 1);
        }
    }

    // every ~20 s, and only when something changed: handlers poke the state without telling us
    private void autosave(double now) {
        if (now - lastSaveSeconds < SAVE_EVERY_SECONDS) {
            return;
        }
        lastSaveSeconds = now;
        host.save();
    }

    // an interrupt storm (mob defense on and off, on and off) must not be a file write each
    private void softSave() {
        if (state != null && begun && System.currentTimeMillis() - lastWriteMillis >= INTERRUPT_SAVE_MILLIS) {
            host.save();
        }
    }

    // ---- the tick

    @Override
    protected Task onTick(AltoClef mod) {
        try {
            return engineTick(mod);
        } catch (RuntimeException e) {
            // the engine itself must not take the bridge's error strikes, a bug here ends the run with a saved state
            Debug.logWarning("gamer engine error: " + e);
            e.printStackTrace();
            if (begun) {
                machine.stuck("the engine hit an error (" + e + ")");
            }
            return null;
        }
    }

    private Task engineTick(AltoClef mod) {
        if (!facts.refresh()) {
            // no player yet (an idle command on join): the clocks must not start from zero
            return null;
        }
        if (!begun) {
            beginRun(mod);
        }
        if (machine.ended()) {
            return null;
        }
        cfg = GamerConfigs.get();
        double now = machine.now();
        if (interruptedAt >= 0) {
            // first fresh tick after another chain had the wheel. forgiven, not reset: a reset made two chains trading the
            // wheel every few seconds look like progress forever
            machine.excuseStall(now - interruptedAt);
            interruptedAt = -1;
        }
        state.runTicks++;
        takeLoadedFurnaces();
        // the furnaces read this: wood the kit still wants is not fuel
        WoodReserve.update(facts, cfg.overworld, cfg.end.beds);
        updateStoneFloor(mod);
        if (facts.creditsShown()) {
            machine.finish(true);
            return null;
        }
        trackDeaths(mod);
        if (machine.ended()) {
            return null;
        }
        if (state.netherTrip != null) {
            // same deal as the recovery below: the trip is not the phase's time and not its fault. the task clears the
            // state when it is over, and the machine has the next tick
            if (netherTrip == null) {
                netherTrip = new NetherRecoverTask(state, facts, () -> cfg, host::say, host::save);
            }
            machine.observe();
            machine.holdClocks();
            setDebugState("Going back to the nether for our stuff.", "Getting our stuff back");
            autosave(now);
            return netherTrip;
        }
        netherTrip = null;
        if (recoverStillWanted(now)) {
            // the recovery is not the phase's time and not its fault: both clocks wait for it (a stall is forgiven the same way)
            machine.observe();
            machine.holdClocks();
            setDebugState("Recovering items after a death.", "Getting our stuff back");
            autosave(now);
            return recover;
        }
        Task child = machine.tick(mod);
        if (!machine.ended()) {
            updateHud(machine.current());
        }
        autosave(now);
        return child;
    }

    // the smelt tasks cannot see our state, a furnace they loaded and walked away from is waiting in a queue for us
    private void takeLoadedFurnaces() {
        List<RunState.FurnaceJob> loaded = AsyncSmelting.drain();
        for (RunState.FurnaceJob job : loaded) {
            FurnaceJobs.record(state.furnaceJobs, job);
            if (job.stranded) {
                host.say("Left " + job.count + " " + job.input.replace('_', ' ') + " in the " + job.kind + ", not lit, going back for it");
                continue;
            }
            host.say((job.unitsEach > 0 ? "Cooking in the background (" : "Smelting in the background (") + job.count + " "
                    + job.output.replace('_', ' ') + ", ~" + job.count * FurnaceJobs.ticksPerItem(job.kind) / 20 + "s)");
        }
        if (!loaded.isEmpty()) {
            // lit and cooking: the job is the memory now, the early load is no longer in flight. only if it was the iron
            if (EarlyIronPick.endsLoad(loaded)) {
                state.earlyLoadTick = -1;
            }
            host.save();
        }
    }

    private void updateHud(PhaseHandler h) {
        String sub = h.hudState();
        int attempt = machine.attempt();
        if (h != hudHandler || attempt != hudAttempt || (sub == null ? hudState != null : !sub.equals(hudState))) {
            hudHandler = h;
            hudState = sub;
            hudAttempt = attempt;
            hudDebug = "Phase " + state.phase + ", attempt " + attempt;
            hudText = sub == null ? h.hud() : h.hud() + ": " + sub;
        }
        setDebugState(hudDebug, hudText);
    }

    // ---- deaths

    // respawning swaps the LocalPlayer (and so does changing dimension), so a new instance only means a death when the old
    // one was seen dead. the death screen counts as dead too, the player can be gone before we ever see the zero health
    private void trackDeaths(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) {
            return;
        }
        if (player != lastPlayer) {
            if (lastPlayer != null && deadSeen) {
                onRespawn();
            }
            lastPlayer = player;
            deadSeen = false;
        }
        if (!deadSeen && (player.isDeadOrDying() || Minecraft.getInstance().screen instanceof DeathScreen)) {
            deadSeen = true;
            deathDimension = facts.dimension();
            deathPos = player.blockPosition();
            deathGameTime = facts.gameTime();
            deathCause = causeOf(mod, player);
        }
    }

    // lava eats the pile and the void takes it, so those are the two we write down. the fluid at our feet and the damage
    // source both count: the killing blow can be fire from a lava bath we already climbed out of
    private NetherTripRules.Cause causeOf(AltoClef mod, LocalPlayer player) {
        var level = mod.getWorld();
        DamageSource source = player.getLastDamageSource();
        boolean lava = player.isInLava() || (source != null && source.is(DamageTypes.LAVA))
                || (level.isLoaded(deathPos) && level.getFluidState(deathPos).is(FluidTags.LAVA));
        boolean out = deathPos.getY() < level.getMinY() || (source != null && source.is(DamageTypes.FELL_OUT_OF_WORLD));
        return NetherTripRules.cause(lava, out);
    }

    private void onRespawn() {
        RunState.Death death = new RunState.Death();
        death.dimension = deathDimension.name();
        death.x = deathPos.getX();
        death.y = deathPos.getY();
        death.z = deathPos.getZ();
        death.gameTime = deathGameTime;
        death.phase = state.phase.name();
        death.cause = deathCause.name().toLowerCase(Locale.ROOT);
        state.deaths.add(death);
        state.deathsThisPhase++;
        // dying on the way to a pile is the end of that trip: the second pile is not worth a third life
        boolean wasOnTrip = state.netherTrip != null;
        if (wasOnTrip) {
            host.say("gave up: died on the way, rebuilding");
            state.netherTrip = null;
            netherTrip = null;
        }
        host.save();
        recover = null;
        if (state.deathsThisPhase >= cfg.death.maxPerPhase) {
            machine.stuck("died " + state.deathsThisPhase + " times in this phase");
            return;
        }
        if (state.deaths.size() - deathsAtStart >= cfg.death.maxTotal) {
            machine.stuck("died " + (state.deaths.size() - deathsAtStart) + " times since the run was started");
            return;
        }
        if (shouldRecover()) {
            host.say("Died, going back for our stuff");
            recover = new RecoverItemsTask(deathPos, RECOVER_BUDGET_SECONDS);
        } else if (!wasOnTrip) {
            maybeGoBackToTheNether();
        }
    }

    // a nether death respawns us at home, so the dimension test above says no and the whole kit used to be rebuilt. the pile
    // down there does not age while nobody is around to load it though, so it is worth a trip when the rules say so
    private void maybeGoBackToTheNether() {
        if (!NetherTripRules.applies(deathDimension, facts.dimension())) {
            return;
        }
        String where = deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ();
        String why = NetherTripRules.refuse(deathCause, state.overworldPortal != null,
                cfg.death.netherRecover && cfg.death.netherTripSeconds > 0, false,
                NetherTripRules.tried(state.lastTripPile, deathPos.getX(), deathPos.getZ()),
                !NetherRegress.kitShort(facts, cfg));
        if (why != null) {
            host.say("nether death at " + where + ", not going back: " + why + ", rebuilding");
            return;
        }
        RunState.Pos pile = new RunState.Pos(deathPos.getX(), deathPos.getY(), deathPos.getZ());
        state.netherTrip = new RunState.NetherTrip(pile, NetherTripRules.Stage.BLOCKS.name(), facts.gameTime());
        state.lastTripPile = pile;
        host.save();
        host.say("nether death at " + where + ", going back for it");
    }

    private boolean shouldRecover() {
        if (facts.dimension() != deathDimension) {
            return false;
        }
        double dx = facts.x() - deathPos.getX();
        double dz = facts.z() - deathPos.getZ();
        return dx * dx + dz * dz <= (double) cfg.death.recoverBlocks * cfg.death.recoverBlocks;
    }

    private boolean recoverStillWanted(double now) {
        if (recover == null) {
            return false;
        }
        boolean tooOld = now - deathGameTime / 20.0 > cfg.death.recoverSeconds;
        if (tooOld || facts.dimension() != deathDimension || recover.isFinished(mod)) {
            recover = null;
            return false;
        }
        return true;
    }

    // ---- what the command shows

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (state == null) {
            lines.add("Not started yet.");
            return lines;
        }
        GamerPhase shown = state.stuck ? GamerPhase.STUCK : state.phase;
        lines.add("Phase: " + shown.hud() + " (attempt " + machine.attempt() + ")");
        lines.add("Time in the run: " + minutes(state.runTicks) + ", deaths: " + state.deaths.size());
        lines.addAll(milestones(state));
        return lines;
    }

    static String minutes(long ticks) {
        long seconds = ticks / 20;
        return seconds / 60 + " min " + seconds % 60 + " s";
    }

    // the same list for a saved run that is not running, so it takes just the state
    public static List<String> milestones(RunState s) {
        List<String> out = new ArrayList<>();
        if (s.overworldPortal != null) {
            out.add("Nether portal at " + s.overworldPortal);
        }
        if (!s.fortress.isEmpty()) {
            out.add("Fortress seen at " + s.fortress.get(0));
        }
        if (s.spawner != null) {
            out.add("Blaze spawner at " + s.spawner);
        }
        if (s.strongholdEstimate != null) {
            out.add("Stronghold is near " + s.strongholdEstimate + " (" + s.strongholdRays.size() + " eye throws)");
        }
        if (s.endPortalCenter != null) {
            out.add("End portal at " + s.endPortalCenter + ", " + s.framesFilled + " of 12 frames filled");
        }
        if (s.dragonDead) {
            out.add("The dragon is dead");
        }
        return out;
    }

    // ---- what the machine needs from the game

    private final class Host implements PhaseMachine.Host {
        @Override
        public GamerConfig cfg() {
            return cfg;
        }

        @Override
        public RunState state() {
            return state;
        }

        @Override
        public GamerFacts facts() {
            return facts;
        }

        @Override
        public void say(String line) {
            Debug.logMessage(line);
        }

        // identical json is not written twice, the saves come from phase moves, deaths, stops and a timer
        @Override
        public void save() {
            if (statePath == null) {
                return;
            }
            String json = RunStateStore.toJson(state);
            if (json.equals(lastWritten)) {
                return;
            }
            if (RunStateStore.write(statePath, json)) {
                lastWritten = json;
                lastWriteMillis = System.currentTimeMillis();
            }
        }

        @Override
        public void onPhaseReset() {
            if (mod != null) {
                dropChild(mod);
            }
        }

        @Override
        public void walkOnEndPortal(boolean on) {
            wantWalkOnPortal = on;
            if (mod != null) {
                mod.getExtraBaritoneSettings().canWalkOnEndPortal(on);
            }
        }
    }
}
