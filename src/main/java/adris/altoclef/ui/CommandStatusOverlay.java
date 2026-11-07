package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.ui.gui.hud.StatusPanel;
import adris.altoclef.ui.gui.hud.StatusRows;
import adris.altoclef.ui.gui.mc.StatusPanelHud;
import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// the task tree in the top left. the lines are the same ones altoclef always had (one per task, the chain name on top,
// plumbing hidden, twins merged, StatusRows has that now), the look is the kit's glass panel from gui-mock/hud, drawn by
// StatusPanelHud. this half only reads the task runner and the settings, once a tick
public class CommandStatusOverlay {

    // how long with no task before the timer starts over (the old 10 s)
    private static final long TIMER_RESET_MS = 10_000;

    // laid out once per tick, drawn every frame
    private List<StatusPanel.Row> _rows = List.of();
    private int _keepTop = 1;
    private String _timer;
    private long _layoutTick = -1;
    private boolean _layoutActive;

    private long _timeRunning;
    private long _lastTime;

    // every frame, from AltoClef inside StatusPanelHud.guard. active is the task runner: off means no rows, and the
    // panel shows its idle pill for a moment if it had some
    public void render(AltoClef mod, GuiGraphicsExtractor graphics, boolean active) {
        Minecraft mc = Minecraft.getInstance();
        // off is off: no task strings get built for a panel nobody sees
        if (mc.level == null || !Baritone.settings().altoShowTaskChains.value) {
            StatusPanelHud.render(graphics, null, 1, null, true);
            _layoutTick = -1;
            return;
        }
        long now = System.currentTimeMillis();
        long tick = mc.level.getGameTime();
        if (tick != _layoutTick || active != _layoutActive) {
            _layoutTick = tick;
            _layoutActive = active;
            layout(mod, active, now);
        }
        // f1 hides the hud and f3 lives in the same corner, it was there first
        boolean hidden = mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen();
        StatusPanelHud.render(graphics, shownRows(), _keepTop, _timer, hidden);
    }

    private void layout(AltoClef mod, boolean active, long now) {
        TaskChain chain = active ? mod.getTaskRunner().getCurrentTaskChain() : null;
        List<Task> tasks = chain == null ? List.of() : chain.getTasks();
        boolean detailed = Baritone.settings().altoHudDetailed.value;
        List<StatusRows.TaskLine> lines = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            // the debug strings are only worth building when somebody is going to read them
            lines.add(new StatusRows.TaskLine(task.getHudName(), task.getHudState(),
                    detailed ? name(task) : null, detailed ? task.getDebugState() : null, task.hudPlumbing()));
        }
        String chainName = chain == null ? null : chain.getName();
        layoutLines(chainName, chain == null ? null : hudName(chain), lines, now);
    }

    // the settings half of the line building, apart from the task runner so a test can hand it lines. the developer view
    // (altoHudDetailed) is the old hud exactly: class names, raw states, nothing hidden
    void layoutLines(String chainName, String chainHud, List<StatusRows.TaskLine> lines, long now) {
        Settings s = Baritone.settings();
        if (lines.isEmpty()) {
            if (_lastTime + TIMER_RESET_MS < now) {
                _timeRunning = now;
            }
            _rows = List.of();
            _timer = null;
            return;
        }
        _lastTime = now;
        _timer = s.altoShowTimer.value ? formatElapsed(now - _timeRunning) : null;
        boolean detailed = s.altoHudDetailed.value;
        String header = detailed ? chainName : chainHud;
        _rows = StatusRows.build(header, chainHud, lines, detailed);
        _keepTop = StatusRows.keepTop(header);
    }

    // null is "switched off", which is not the same as "nothing running": off forgets the idle pill too
    List<StatusPanel.Row> shownRows() {
        return Baritone.settings().altoShowTaskChains.value ? _rows : null;
    }

    String timer() {
        return _timer;
    }

    private static String name(Task task) {
        try {
            return task.getDebugName();
        } catch (Throwable t) {
            // a task's debug string is allowed to be sloppy, the hud is not allowed to die from it
            return task.getClass().getSimpleName();
        }
    }

    private static String hudName(TaskChain chain) {
        try {
            return chain.getHudName();
        } catch (Throwable t) {
            return chain.getName();
        }
    }

    static String formatElapsed(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m % 60, s % 60) : String.format("%02d:%02d", m, s % 60);
    }
}
