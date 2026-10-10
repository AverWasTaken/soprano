package adris.altoclef.trace;

import java.util.function.Consumer;

// baritone's debug lines can fire every tick of a pause, and a line per tick is how a log becomes unreadable. the first
// one is printed as it comes, the repeats are only counted, and one "<text> x<N> over <ticks>t" summary goes out when the
// text changes, when the run has been going for 5 s (so a long pause still says it is going), or when it went quiet.
// pure: the clock and the tick come in as arguments and the lines go out through a callback. synchronized because the
// path calculation threads log too
public final class DebugCollapse {

    public static final long SUMMARY_EVERY_MS = 5_000;
    // a run that stopped repeating for this long is done, the flush says so
    public static final long QUIET_MS = 1_000;

    private String text;
    // repeats nobody has been told about yet
    private int extra;
    // 1 when the group's first occurrence was printed as its own line, 0 for the follow-up group after a 5 s summary
    private int printed;
    private long groupStartMs;
    private long groupStartTick;
    // the newest occurrence of the current text
    private long lastMs;
    private long lastTick;
    // the newest message of any kind, for the stand line which wants "what did baritone last say, and when"
    private String latest;
    private long latestTick = Long.MIN_VALUE;

    public synchronized void offer(String message, long nowMs, long nowTick, Consumer<String> out) {
        latest = message;
        latestTick = nowTick;
        if (message.equals(text)) {
            extra++;
            lastMs = nowMs;
            lastTick = nowTick;
            if (nowMs - groupStartMs >= SUMMARY_EVERY_MS) {
                summarize(out);
                restart(nowMs, nowTick);
            }
            return;
        }
        summarize(out);
        text = message;
        printed = 1;
        groupStartMs = nowMs;
        groupStartTick = nowTick;
        lastMs = nowMs;
        lastTick = nowTick;
        out.accept(message);
    }

    // call every so often (a tick is fine, it is a couple of compares): says the summary of a run that has gone quiet
    public synchronized void flush(long nowMs, Consumer<String> out) {
        if (extra > 0 && nowMs - lastMs >= QUIET_MS) {
            summarize(out);
            // over and done, so the same text turning up later is news again and prints its own first line
            text = null;
        }
    }

    public synchronized String latest() {
        return latest;
    }

    // Long.MIN_VALUE when nothing was ever said
    public synchronized long latestTick() {
        return latestTick;
    }

    // same run, fresh clock, and its first line is already out there
    private void restart(long nowMs, long nowTick) {
        printed = 0;
        groupStartMs = nowMs;
        groupStartTick = nowTick;
    }

    private void summarize(Consumer<String> out) {
        if (text == null || extra <= 0) {
            return;
        }
        out.accept(text + " x" + (printed + extra) + " over " + Math.max(0, lastTick - groupStartTick) + "t");
        extra = 0;
    }
}
