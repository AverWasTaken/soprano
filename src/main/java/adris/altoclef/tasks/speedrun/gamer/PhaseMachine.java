package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;

import java.util.List;

// the engine without the game: which phase we are in, when it times out, retries, skips, advances, regresses or gives up.
// GamerTask feeds it facts and a host, and the tests feed it fakes and a script. it is also the GamerContext the handlers
// see, so a handler cannot tell the difference. everything is in game seconds taken from the facts
public final class PhaseMachine implements GamerContext {
    // the world around the machine: where the run's memory and config come from, and where words and saves go
    public interface Host {
        GamerConfig cfg();

        RunState state();

        GamerFacts facts();

        void say(String line);

        // write RunState now
        void save();

        void walkOnEndPortal(boolean on);

        // a retry or a phase move is about to enter a handler again: whatever child task the old one left running has to go,
        // Task.tick keeps an equal child and a fresh one would be told "same as before" with all of its blacklists and timers
        default void onPhaseReset() {
        }
    }

    // a place we have not left by this much since the last "progress" counts as standing still (squared, in blocks)
    static final double MOVED_SQ = 36;

    // the same regress (LOCATE>NETHER) may happen this many times in one run, then it is a loop and not a recovery
    static final int MAX_REGRESS_PER_PAIR = 2;

    // a resumed attempt may be this far past its budget (a late autosave) before the saved start is not believed
    static final double RESUME_SLACK_SECONDS = 60;

    private final Host host;
    private final PhaseRules rules;
    private final Watchdog watchdog = new Watchdog();
    private AltoClef mod;
    private String pendingFail;
    // the handler that is between onEnter and onExit right now. exit only counts for that one, so a stuck run that gets its
    // real stop afterwards does not run onExit twice (a handler that pops something in there would pop twice)
    private PhaseHandler entered;
    // the level's game time can read 0 for a few ticks after a dimension change, the machine's clock never goes back
    private double lastNow;
    // when the death recovery last held the clocks, -1 when it is not running
    private double heldAt = -1;

    // the tick's FoodPlan, see food()
    private FoodPlan food;
    private int lastFingerprint;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private Dimension lastDimension;

    public PhaseMachine(List<PhaseHandler> handlers, Host host) {
        this.rules = new PhaseRules(handlers);
        this.host = host;
    }

    public PhaseRules rules() {
        return rules;
    }

    public Watchdog watchdog() {
        return watchdog;
    }

    public PhaseHandler current() {
        return rules.handler(host.state().phase);
    }

    public boolean ended() {
        RunState s = host.state();
        return s.finished || s.stuck;
    }

    public double now() {
        double t = host.facts().gameTime() / 20.0;
        if (t > lastNow) {
            lastNow = t;
        }
        return lastNow;
    }

    // ---- life cycle

    // the run starts in whatever phase the state says, with fresh clocks
    public void begin(AltoClef mod) {
        begin(mod, false);
    }

    // resumed = the saved run carries on where it was (a relog, a #gamer after a restart). the attempt keeps its real
    // start then, or a relog 35 minutes into the nether would get a brand new 40
    public void begin(AltoClef mod, boolean resumed) {
        this.mod = mod;
        heldAt = -1;
        if (resumed && attemptClockSurvives()) {
            resumeClocks();
        } else {
            restartClocks();
        }
        if (!ended()) {
            safeEnter(current());
        }
    }

    // the saved start has to be a real one: unset, or older than the budget plus a minute, means the world ran on without
    // us (a server) and the elapsed time says nothing about how this attempt went. game time only counts while the world
    // is loaded, so a single player relog costs nothing
    private boolean attemptClockSurvives() {
        RunState s = host.state();
        if (s.phaseEnteredGameTime <= 0) {
            return false;
        }
        double minutes = host.cfg().budgets.minutes(s.phase);
        double elapsed = (host.facts().gameTime() - s.phaseEnteredGameTime) / 20.0;
        return minutes <= 0 || elapsed <= minutes * 60 + RESUME_SLACK_SECONDS;
    }

    // the budget clock goes back to the saved start, the stall timer and the position anchors start now
    private void resumeClocks() {
        GamerFacts f = host.facts();
        long entered = Math.min(host.state().phaseEnteredGameTime, f.gameTime());
        host.state().phaseEnteredGameTime = entered;
        resetWatch();
        watchdog.backdate(entered / 20.0);
    }

    // the clocks of the current attempt start over
    public void restartClocks() {
        host.state().phaseEnteredGameTime = host.facts().gameTime();
        resetWatch();
    }

    private void resetWatch() {
        GamerFacts f = host.facts();
        watchdog.reset(now());
        lastFingerprint = f.inventoryFingerprint();
        anchorX = f.x();
        anchorY = f.y();
        anchorZ = f.z();
        lastDimension = f.dimension();
    }

    // the run is being stopped for real (not just interrupted)
    public void exitCurrent(AltoClef mod) {
        this.mod = mod;
        safeExit(current());
    }

    // ---- progress

    public void progress() {
        watchdog.progress(now());
    }

    // a death recovery has the wheel and never reaches tick(): the attempt did not get any older meanwhile. called every tick
    // the recovery runs, the time since the previous call is handed back to both clocks (the saved phase start too, the
    // nether budget reads that one)
    public void holdClocks() {
        double now = now();
        giveBackHeldTime(now);
        heldAt = now;
    }

    // the recovery lost the wheel for a while: what it held so far is given back, the rest is the caller's to excuse
    public void releaseHold() {
        giveBackHeldTime(now());
    }

    private void giveBackHeldTime(double now) {
        if (heldAt >= 0 && now > heldAt) {
            long ticks = Math.round((now - heldAt) * 20);
            host.state().phaseEnteredGameTime += ticks;
            watchdog.pause(ticks / 20.0);
        }
        heldAt = -1;
    }

    // the stall timer forgives the time another chain had the wheel (mob defense, eating): coming back from an interrupt is
    // not progress, or two chains trading the wheel every few seconds would keep a stuck phase alive forever
    public void excuseStall(double seconds) {
        watchdog.excuse(now(), seconds);
    }

    // inventory changed, dimension changed, or we got about six blocks away from where we last made progress. not "changed
    // chunk": a bot dancing on a chunk border is not getting anywhere
    public void observe() {
        GamerFacts f = host.facts();
        double now = now();
        if (f.inventoryFingerprint() != lastFingerprint) {
            lastFingerprint = f.inventoryFingerprint();
            watchdog.progress(now);
        }
        if (f.dimension() != lastDimension) {
            lastDimension = f.dimension();
            watchdog.progress(now);
        }
        double dx = f.x() - anchorX;
        double dy = f.y() - anchorY;
        double dz = f.z() - anchorZ;
        if (dx * dx + dy * dy + dz * dz >= MOVED_SQ) {
            anchorX = f.x();
            anchorY = f.y();
            anchorZ = f.z();
            watchdog.progress(now);
        }
    }

    // ---- the tick

    // watchdog, then "which phase", then the handler. returns the child task for this tick (null = nothing to do or the
    // run ended)
    public Task tick(AltoClef mod) {
        this.mod = mod;
        food = null;
        giveBackHeldTime(now());
        if (ended()) {
            return null;
        }
        observe();
        checkWatchdog();
        if (ended()) {
            return null;
        }
        applyDecision();
        if (ended()) {
            return null;
        }
        PhaseHandler h = current();
        try {
            return h.tick(mod, this);
        } catch (RuntimeException e) {
            handlerThrew(h, e);
            return null;
        }
    }

    private void checkWatchdog() {
        String failure = pendingFail;
        pendingFail = null;
        if (failure == null) {
            Watchdog.Verdict v = watchdog.check(now(), host.state().phase, host.cfg(), current());
            if (!v.ok()) {
                failure = v.reason();
            }
        }
        if (failure != null) {
            onTimeout(failure);
        }
    }

    private void onTimeout(String reason) {
        PhaseHandler h = current();
        int attempt = attempt();
        Timeout what;
        try {
            what = h.onTimeout(this, attempt, reason);
        } catch (RuntimeException e) {
            Debug.logWarning("onTimeout of " + h.phase() + " threw " + e);
            what = Timeout.STUCK;
        }
        switch (what) {
            case RETRY -> retry(h, attempt, reason);
            case SKIP -> skip(reason);
            default -> stuck(reason);
        }
    }

    private void retry(PhaseHandler h, int attempt, String reason) {
        RunState s = host.state();
        host.say(s.phase.hud() + " is not working (" + reason + "), trying again");
        s.phaseAttempts.put(s.phase.name(), attempt + 1);
        safeExit(h);
        host.onPhaseReset();
        restartClocks();
        safeEnter(h);
        host.save();
    }

    private void skip(String reason) {
        RunState s = host.state();
        // skipping the dragon would be "pretending we won"
        if (s.phase.ordinal() + 1 >= GamerPhase.DONE.ordinal()) {
            stuck(reason);
            return;
        }
        host.say("Skipping " + s.phase.hud().toLowerCase() + " (" + reason + ")");
        moveTo(GamerPhase.values()[s.phase.ordinal() + 1]);
    }

    private void applyDecision() {
        RunState s = host.state();
        PhaseRules.Decision d;
        try {
            d = rules.decide(s.phase, host.facts(), s, host.cfg(), now());
        } catch (RuntimeException e) {
            handlerThrew(current(), e);
            return;
        }
        switch (d.kind()) {
            case ADVANCE -> {
                if (d.to() == GamerPhase.DONE) {
                    finish(false);
                } else {
                    host.say(d.to().hud());
                    moveTo(d.to());
                }
            }
            case REGRESS -> regress(d);
            default -> {
            }
        }
    }

    // every move resets the attempts and the clocks, so a phase pair that keeps sending us back and forth would never trip a
    // budget. the count per pair is saved with the run, the third time is a loop
    private void regress(PhaseRules.Decision d) {
        RunState s = host.state();
        String pair = s.phase.name() + ">" + d.to().name();
        int times = s.regressCounts.getOrDefault(pair, 0);
        if (times >= MAX_REGRESS_PER_PAIR) {
            stuck("phase ping pong (" + pair.toLowerCase() + " " + (times + 1) + " times)");
            return;
        }
        s.regressCounts.put(pair, times + 1);
        host.say(d.to().hud() + " again (" + d.reason() + ")");
        moveTo(d.to());
    }

    // leave the current handler and enter another one, the common part of advance / regress / skip
    private void moveTo(GamerPhase target) {
        RunState s = host.state();
        safeExit(current());
        if (target == GamerPhase.NETHER && target.ordinal() < s.phase.ordinal()) {
            s.netherRevisits++;
        }
        s.phase = target;
        s.phaseAttempts.put(target.name(), 1);
        s.deathsThisPhase = 0;
        pendingFail = null;
        host.onPhaseReset();
        restartClocks();
        safeEnter(current());
        host.save();
    }

    public void finish(boolean credits) {
        RunState s = host.state();
        if (ended()) {
            return;
        }
        safeExit(current());
        s.phase = GamerPhase.DONE;
        s.finished = true;
        s.dragonDead = true;
        host.save();
        host.say(credits ? "Beat the game. Credits are rolling." : "Beat the game.");
    }

    public void stuck(String reason) {
        RunState s = host.state();
        if (ended()) {
            return;
        }
        safeExit(current());
        s.stuck = true;
        s.stuckReason = reason;
        host.save();
        host.say("Gave up at " + s.phase.hud().toLowerCase() + ": " + reason + ". State saved, #gamer resumes."
                + restartHint(s.phase));
    }

    // a run that died its way to STUCK in the End comes back to the End with the same deaths on the books, the gear check is
    // what it needs again
    private static String restartHint(GamerPhase phase) {
        return phase == GamerPhase.DRAGON ? " (#gamer phase end_prep redoes the gear check first)" : "";
    }

    private void safeEnter(PhaseHandler h) {
        if (h == null) {
            return;
        }
        entered = h;
        try {
            h.onEnter(mod, this);
        } catch (RuntimeException e) {
            handlerThrew(h, e);
        }
    }

    private void safeExit(PhaseHandler h) {
        if (h == null || entered != h) {
            return;
        }
        entered = null;
        try {
            h.onExit(mod, this);
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: onExit of " + h.phase() + " threw " + e);
        }
    }

    // a handler that throws is a failed attempt like any other: the same retry / skip / stuck decision, never a rethrow
    // into the bridge (five of those in a minute and altoclef gives up for the session)
    private void handlerThrew(PhaseHandler h, RuntimeException e) {
        Debug.logWarning(h.phase() + " phase hit an error: " + e);
        e.printStackTrace();
        pendingFail = "error in the " + h.phase().name().toLowerCase() + " phase (" + e.getClass().getSimpleName() + ")";
    }

    // an exception that came out of the child task the handler handed back, which Task.tick runs after our onTick returned.
    // same road as a handler that throws: a failed attempt, retried or stuck
    public void failFromChild(RuntimeException e) {
        PhaseHandler h = current();
        if (h == null) {
            throw e;
        }
        handlerThrew(h, e);
    }

    // ---- the handlers' view

    @Override
    public GamerConfig cfg() {
        return host.cfg();
    }

    @Override
    public RunState state() {
        return host.state();
    }

    @Override
    public GamerFacts facts() {
        return host.facts();
    }

    // built on the first ask of a tick and dropped when the next one starts: every gate in a tick reads the same food count
    @Override
    public FoodPlan food() {
        if (food == null) {
            food = FoodPlan.of(host.facts(), host.cfg());
        }
        return food;
    }

    @Override
    public int attempt() {
        RunState s = host.state();
        return Math.max(1, s.attemptsOf(s.phase));
    }

    @Override
    public double secondsInPhase() {
        return watchdog.secondsInAttempt(now());
    }

    @Override
    public void save() {
        host.save();
    }

    @Override
    public void progress(String what) {
        progress();
    }

    @Override
    public void fail(String reason) {
        pendingFail = reason;
    }

    @Override
    public void log(String line) {
        host.say(line);
    }

    @Override
    public void walkOnEndPortal(boolean on) {
        host.walkOnEndPortal(on);
    }
}
