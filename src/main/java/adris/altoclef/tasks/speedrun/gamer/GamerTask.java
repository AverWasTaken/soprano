package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EndPrepPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EyesPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.LocatePhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.OpenPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.PortalPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.ReturnPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.RoomPhase;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverItemsTask;
import adris.altoclef.tasksystem.Task;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// beats the game: a phase state machine over PhaseHandlers with its memory in RunState (see gamer-design.md). this class is
// the part that touches the game: facts, deaths, saving, the hud and the settings for the run. which phase we are in and
// when it gives up is PhaseMachine, which does not know there is a game. nothing in here is static, two runs never share anything
public class GamerTask extends Task {
    // the gear the End needs to still be in the bag when we get there, a full inventory must not throw it away
    private static final Item[] PROTECTED = ArrayUtils.addAll(ArrayUtils.addAll(ArrayUtils.addAll(new Item[]{
            Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.BUCKET, Items.WATER_BUCKET,
            Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.CRAFTING_TABLE, Items.OBSIDIAN, Items.SHIELD,
            Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_SWORD}, ItemHelper.BED),
            ItemHelper.IRON_ARMORS), ItemHelper.DIAMOND_ARMORS);
    private static final Block[] TRACKED = ArrayUtils.addAll(new Block[]{
            Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL, Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.SPAWNER,
            Blocks.NETHER_PORTAL}, ItemHelper.itemsToBlocks(ItemHelper.BED));
    // the blocks we want to bridge and pillar with, on top of whatever the user listed
    private static final Item[] BUILD_BLOCKS = {Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.END_STONE, Items.COBBLED_DEEPSLATE};

    private static final double SAVE_EVERY_SECONDS = 20;
    private static final double RECOVER_BUDGET_SECONDS = 90;

    private final PhaseMachine machine;
    private final GamerPhase startAt;
    private final Host host = new Host();

    private AltoClef mod;
    private MinecraftFacts facts;
    private GamerConfig cfg;
    private RunState state;
    private Path statePath;
    // false until the first onStart did its loading. onStart runs again after every interrupt (eating, mob defense), and
    // that is only a "put the overrides back", never a new run
    private boolean started;
    private boolean pushed;

    private double lastSaveSeconds;
    private String lastWritten = "";

    // death tracking
    private LocalPlayer lastPlayer;
    private boolean deadSeen;
    private Dimension deathDimension;
    private BlockPos deathPos;
    private long deathGameTime;
    private RecoverItemsTask recover;

    // what the user had before we changed it for the run, to hand back on stop
    private Boolean userBlastFurnace;
    private Boolean userThrowUnused;
    private List<Item> userThrowaway;

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

    // ---- task plumbing

    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        if (facts == null) {
            facts = new MinecraftFacts(mod);
        }
        applyBehaviour(mod);
        if (started) {
            // back from an interrupt: same run, same phase, the clocks should not blame the chain that had the wheel
            machine.progress();
            return;
        }
        started = true;
        facts.refresh();
        cfg = GamerConfigs.get();
        loadState(mod);
        lastSaveSeconds = machine.now();
        machine.begin(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        try {
            if (state != null) {
                host.save();
                // an interrupt is a pause, the handler keeps its sub steps. only a real stop ends the phase
                if (!isInterrupting() && started) {
                    machine.exitCurrent(mod);
                }
            }
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: onStop " + e);
        } finally {
            releaseBehaviour(mod);
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
        if (!machine.ended() && Minecraft.getInstance().screen instanceof WinScreen) {
            machine.finish(true);
        }
        return machine.ended();
    }

    // ---- behaviour and settings for the run

    private void applyBehaviour(AltoClef mod) {
        mod.getBehaviour().push();
        pushed = true;
        mod.getBehaviour().addProtectedItems(PROTECTED);
        mod.getBlockTracker().trackBlock(TRACKED);
        applyRunSettings();
    }

    private void releaseBehaviour(AltoClef mod) {
        try {
            mod.getBlockTracker().stopTracking(TRACKED);
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: stopTracking " + e);
        }
        try {
            mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
            releaseRunSettings();
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: releasing settings " + e);
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
        SettingsOverrides.put(s.altoUseBlastFurnace, false);
        SettingsOverrides.put(s.altoThrowAwayUnusedItems, true);
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

    private void releaseRunSettings() {
        Settings s = Baritone.settings();
        if (userBlastFurnace != null) {
            SettingsOverrides.put(s.altoUseBlastFurnace, userBlastFurnace);
        }
        if (userThrowUnused != null) {
            SettingsOverrides.put(s.altoThrowAwayUnusedItems, userThrowUnused);
        }
        if (userThrowaway != null) {
            SettingsOverrides.put(s.altoThrowawayItems, userThrowaway);
        }
    }

    // ---- state

    private void loadState(AltoClef mod) {
        statePath = RunStateStore.resolvePath(mod);
        RunStateStore.Loaded loaded = RunStateStore.load(statePath, RunStateStore.fingerprint());
        state = loaded.state();
        if (state.startedEpochMs == 0) {
            state.startedEpochMs = System.currentTimeMillis();
        }
        if (state.phase == GamerPhase.STUCK) {
            // never written by us, but a hand edit could, and there is no handler for it
            state.phase = GamerPhase.GATHER;
        }
        if (startAt != null) {
            jumpTo(startAt);
        } else if (loaded.resumed()) {
            resumeSaved();
        }
    }

    private void jumpTo(GamerPhase phase) {
        state.phase = phase;
        state.stuck = false;
        state.stuckReason = "";
        state.finished = false;
        state.phaseAttempts.put(phase.name(), 1);
        state.deathsThisPhase = 0;
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
            state.phaseAttempts.put(state.phase.name(), 1);
        } else {
            host.say("Resuming at: " + state.phase.hud());
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
        String json = RunStateStore.toJson(state);
        if (!json.equals(lastWritten) && RunStateStore.write(statePath, json)) {
            lastWritten = json;
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
            machine.stuck("the engine hit an error (" + e + ")");
            return null;
        }
    }

    private Task engineTick(AltoClef mod) {
        if (machine.ended() || !facts.refresh()) {
            return null;
        }
        cfg = GamerConfigs.get();
        double now = machine.now();
        state.runTicks++;
        if (facts.creditsShown()) {
            machine.finish(true);
            return null;
        }
        trackDeaths(mod);
        if (machine.ended()) {
            return null;
        }
        if (recoverStillWanted(now)) {
            machine.observe();
            machine.progress();
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
        }
    }

    private void onRespawn() {
        RunState.Death death = new RunState.Death();
        death.dimension = deathDimension.name();
        death.x = deathPos.getX();
        death.y = deathPos.getY();
        death.z = deathPos.getZ();
        death.gameTime = deathGameTime;
        death.phase = state.phase.name();
        state.deaths.add(death);
        state.deathsThisPhase++;
        host.save();
        recover = null;
        if (state.deathsThisPhase >= cfg.death.maxPerPhase) {
            machine.stuck("died " + state.deathsThisPhase + " times in this phase");
            return;
        }
        if (shouldRecover()) {
            host.say("Died, going back for our stuff");
            recover = new RecoverItemsTask(deathPos, RECOVER_BUDGET_SECONDS);
        }
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

        @Override
        public void save() {
            if (statePath == null) {
                return;
            }
            String json = RunStateStore.toJson(state);
            if (RunStateStore.write(statePath, json)) {
                lastWritten = json;
            }
        }

        @Override
        public void walkOnEndPortal(boolean on) {
            mod.getExtraBaritoneSettings().canWalkOnEndPortal(on);
        }
    }
}
