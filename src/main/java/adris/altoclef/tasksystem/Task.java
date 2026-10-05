package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.ui.HudText;

import java.util.function.Predicate;

public abstract class Task {

    private String _oldDebugState = "";
    private String _debugState = "";
    private String _hudState = "";

    private Task _sub = null;

    private boolean _first = true;

    private boolean _stopped = false;

    private boolean _active = false;

    public void tick(AltoClef mod, TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        if (_first) {
            Debug.logInternal("Task START: " + this);
            _active = true;
            onStart(mod);
            _first = false;
            _stopped = false;
        }
        if (_stopped) return;

        Task newSub = onTick(mod);
        // Debug state print
        if (!_oldDebugState.equals(_debugState)) {
            Debug.logInternal(toString());
            _oldDebugState = _debugState;
        }
        // We have a sub task
        if (newSub != null) {
            if (!newSub.isEqual(_sub)) {
                if (canBeInterrupted(mod, _sub, newSub)) {
                    // Our sub task is new
                    if (_sub != null) {
                        // Our previous sub must be interrupted.
                        _sub.stop(mod, newSub);
                    }

                    _sub = newSub;
                }
            }

            // Run our child
            _sub.tick(mod, parentChain);
        } else {
            // We are null
            if (_sub != null && canBeInterrupted(mod, _sub, null)) {
                // Our previous sub must be interrupted.
                _sub.stop(mod);
                _sub = null;
            }
        }
    }

    public void reset() {
        _first = true;
        _active = false;
        _stopped = false;
    }

    public void stop(AltoClef mod) {
        stop(mod, null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.stop(mod, interruptTask);
        }

        _first = true;
        _active = false;
        _stopped = true;
    }

    /**
     * Lets the task know it's execution has been "suspended"
     * <p>
     * STILL RUNS `onStop`
     * <p>
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     */
    public void interrupt(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.interrupt(mod, interruptTask);
        }

        _first = true;
    }

    protected void setDebugState(String state) {
        setDebugState(state, "");
    }

    // the hud state is tied to the debug state on purpose: a task that sets a plain debug state after a friendly
    // one would otherwise leave the friendly one on screen while doing something else entirely
    protected void setDebugState(String state, String hudState) {
        if (state == null) {
            state = "";
        }
        _debugState = state;
        _hudState = hudState == null ? "" : hudState;
    }

    // what the task hud shows instead of toDebugString. plain words a player understands: no class names, no
    // toString dumps, no brackets, no "x Infinity". the default is the humanized class name, which beats the debug
    // string for the long tail. never called directly by the hud, see getHudName
    protected String toHudString() {
        return HudText.humanizeClassName(getClass());
    }

    // plumbing (slot shuffling, cursor freeing, "do to closest" wrappers) only shows on the hud when it is the leaf,
    // that is when it's the thing actually happening right now
    protected boolean isHudPlumbing() {
        return false;
    }

    public final String getHudName() {
        try {
            String s = toHudString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Throwable t) {
            // a label that throws is a bug in the label, not a reason to lose the hud
        }
        return HudText.humanizeClassName(getClass());
    }

    public final String getHudState() {
        return _hudState;
    }

    public final boolean hudPlumbing() {
        try {
            return isHudPlumbing();
        } catch (Throwable t) {
            return false;
        }
    }

    // Virtual
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean stopped() {
        return _stopped;
    }

    protected abstract void onStart(AltoClef mod);

    protected abstract Task onTick(AltoClef mod);

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + _debugState;
    }

    // the hud draws the name and the state in different colors, so it wants them apart
    public String getDebugName() {
        return toDebugString();
    }

    public String getDebugState() {
        return _debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t._sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    // a force held longer than this is assumed to be stuck, so it can't deadlock the whole tree
    private static final long FORCE_TIMEOUT_MS = 10_000;
    private long _forcedSinceMs = -1;
    private boolean _forceTimeoutLogged = false;

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    boolean canBeInterrupted(AltoClef mod, Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // Our task can declare that is FORCES itself to be active NOW.
        // this is an exists-walk: ANY forcing node down the chain holds the swap, not just the first node.
        // it used to return true at the first node that wasn't forcing, so nested forces were never honoured
        boolean forced = subTask.thisOrChildSatisfies(task ->
                task instanceof ITaskCanForce canForce && canForce.shouldForce(mod, toInterruptWith));
        if (!forced) {
            _forcedSinceMs = -1;
            _forceTimeoutLogged = false;
            return true;
        }
        long now = System.currentTimeMillis();
        if (_forcedSinceMs < 0) {
            _forcedSinceMs = now;
        }
        if (now - _forcedSinceMs > FORCE_TIMEOUT_MS) {
            // a stuck shouldForce must not freeze the tree forever (log once, not 20 times a second)
            if (!_forceTimeoutLogged) {
                _forceTimeoutLogged = true;
                Debug.logInternal("Task force held for over " + FORCE_TIMEOUT_MS / 1000 + "s, ignoring it: " + subTask);
            }
            return true;
        }
        return false;
    }
}
