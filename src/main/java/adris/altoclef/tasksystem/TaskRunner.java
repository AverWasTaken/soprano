package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

import java.util.ArrayList;

public class TaskRunner {

    private final ArrayList<TaskChain> _chains = new ArrayList<>();
    private final AltoClef _mod;
    private boolean _active;

    private TaskChain _cachedCurrentTaskChain = null;
    // what each chain said this tick, same order as _chains, NaN for one that was not active
    private float[] _priorities = new float[0];
    private final HeldWheelWatch _heldWatch = new HeldWheelWatch();

    public TaskRunner(AltoClef mod) {
        _mod = mod;
        _active = false;
    }

    public void tick() {
        if (!_active || !AltoClef.inGame()) return;
        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        // second place, for the switch line. found in the same pass: several chains do real work inside getPriority, so
        // it gets asked once per chain per tick and no more
        TaskChain runnerUp = null;
        float runnerUpPriority = Float.NEGATIVE_INFINITY;
        if (_priorities.length != _chains.size()) {
            _priorities = new float[_chains.size()];
        }
        for (int i = 0; i < _chains.size(); i++) {
            TaskChain chain = _chains.get(i);
            if (!chain.isActive()) {
                _priorities[i] = Float.NaN;
                continue;
            }
            float priority = chain.getPriority(_mod);
            _priorities[i] = priority;
            if (priority > maxPriority) {
                runnerUp = maxChain;
                runnerUpPriority = maxPriority;
                maxPriority = priority;
                maxChain = chain;
            } else if (priority > runnerUpPriority) {
                runnerUp = chain;
                runnerUpPriority = priority;
            }
        }
        if (maxChain != _cachedCurrentTaskChain) {
            logSwitch(_cachedCurrentTaskChain, maxChain, maxPriority, runnerUp, runnerUpPriority);
        }
        if (_cachedCurrentTaskChain != null && maxChain != _cachedCurrentTaskChain) {
            _cachedCurrentTaskChain.onInterrupt(_mod, maxChain);
        }
        _cachedCurrentTaskChain = maxChain;
        if (maxChain != null) {
            maxChain.tick(_mod);
        }
        // the chain's task list is filled while it ticks, so empty after the tick means it won the wheel and ran nothing
        boolean ranSomething = maxChain != null && !maxChain.getTasks().isEmpty();
        if (_heldWatch.shouldLog(maxChain, ranSomething, System.currentTimeMillis())) {
            Debug.logWarning(maxChain + " holds the wheel at " + maxPriority + " with nothing running (" + maxChain.getHeldTaskDebug() + ")");
        }
    }

    // two chains have an empty onInterrupt, so the wheel changing hands would never say a word otherwise. the loser's
    // number is whatever it asked for this tick ("off" when it was not active to ask)
    private void logSwitch(TaskChain from, TaskChain to, float toPriority, TaskChain next, float nextPriority) {
        String fromPart = "none";
        if (from != null) {
            int at = _chains.indexOf(from);
            float p = at >= 0 && at < _priorities.length ? _priorities[at] : Float.NaN;
            fromPart = from + ":" + (Float.isNaN(p) ? "off" : Float.toString(p));
        }
        String toPart = to == null ? "none" : to + ":" + toPriority;
        String nextPart = next == null ? "none" : next + ":" + nextPriority;
        Debug.logInternal("switch chain from=" + fromPart + " to=" + toPart + " next=" + nextPart);
    }

    public void addTaskChain(TaskChain chain) {
        _chains.add(chain);
    }

    public boolean isActive() {
        return _active;
    }

    public void enable() {
        if (_active) {
            return;
        }
        // active before the setup, not after: if the setup dies halfway (a scope applied, a state pushed) disable() has
        // to know there is something to undo, otherwise the overrides sit there with nobody to take them back
        _active = true;
        try {
            _mod.onTaskRunnerEnable();
        } catch (Throwable t) {
            try {
                disable();
            } catch (Throwable ignored) {
                // already unwinding from the first one
            }
            throw t;
        }
    }

    public void disable() {
        boolean wasActive = _active;
        Throwable failure = null;
        try {
            if (wasActive) {
                try {
                    _mod.getBehaviour().pop();
                } catch (Throwable t) {
                    failure = t;
                }
            }
            // one chain throwing out of its stop must not keep the rest from stopping
            for (TaskChain chain : _chains) {
                try {
                    chain.stop(_mod);
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }
        } finally {
            _active = false;
            // last, so nothing a chain does while stopping can leak settings back out after we put baritone's own back.
            // finally, so nothing that threw above can keep the user's settings from coming back
            if (wasActive) {
                _mod.onTaskRunnerDisabled();
            }
        }

        Debug.logMessage("Stopped");
        // everything is restored by now, the caller still gets to hear about it (the bridge counts these)
        if (failure instanceof RuntimeException re) {
            throw re;
        } else if (failure instanceof Error e) {
            throw e;
        } else if (failure != null) {
            throw new RuntimeException(failure);
        }
    }

    public TaskChain getCurrentTaskChain() {
        return _cachedCurrentTaskChain;
    }

    // Kinda jank ngl
    public AltoClef getMod() {
        return _mod;
    }
}
