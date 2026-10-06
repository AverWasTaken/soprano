package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

import java.util.ArrayList;

public class TaskRunner {

    private final ArrayList<TaskChain> _chains = new ArrayList<>();
    private final AltoClef _mod;
    private boolean _active;

    private TaskChain _cachedCurrentTaskChain = null;

    public TaskRunner(AltoClef mod) {
        _mod = mod;
        _active = false;
    }

    public void tick() {
        if (!_active || !AltoClef.inGame()) return;
        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : _chains) {
            if (!chain.isActive()) continue;
            float priority = chain.getPriority(_mod);
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        if (_cachedCurrentTaskChain != null && maxChain != _cachedCurrentTaskChain) {
            _cachedCurrentTaskChain.onInterrupt(_mod, maxChain);
        }
        _cachedCurrentTaskChain = maxChain;
        if (maxChain != null) {
            maxChain.tick(_mod);
        }
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
