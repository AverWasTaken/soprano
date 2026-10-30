/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the client thread's time goes, a game tick at a time. Everything the bot does on the client thread is wrapped in
 * a section (by listener and event, and finer ones inside the movements); a tick that spends more than lagLogMs in the bot
 * is written to the log with its biggest sections, so a stutter in the game says what caused it.
 * Off (nothing measured, nothing allocated) while lagLogMs is 0 and SOPRANO_LAG_LOG isn't set.
 */
public final class LagMeter {

    private static final long ENV_MS = parse(System.getenv("SOPRANO_LAG_LOG"));
    // nanos per section this tick, in the order first seen (render passes count to the tick they fall in)
    private static final Map<String, long[]> SECTIONS = new LinkedHashMap<>();
    private static long tickTotal;
    // sections inside sections (a movement's update inside PathingBehavior's tick) count once toward the tick
    private static int depth;
    // running totals for #lag: ticks seen, ticks over, worst
    private static long ticks, over, worstNs;
    private static String worstWhat = "";

    private LagMeter() {
    }

    private static long parse(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long thresholdMs() {
        long s = Baritone.settings().lagLogMs.value;
        return s > 0 ? s : ENV_MS;
    }

    public static boolean on() {
        return thresholdMs() > 0;
    }

    // the client thread (the first one to end a tick): searches on other threads use the same code and aren't measured
    private static volatile Thread client;

    public static long start() {
        if (!on() || Thread.currentThread() != client && client != null) {
            return 0;
        }
        depth++;
        return System.nanoTime();
    }

    /**
     * adds the time since t0 (from start) to the section. called on the client thread only
     */
    public static void stop(String section, long t0) {
        if (t0 == 0) {
            return;
        }
        long dt = System.nanoTime() - t0;
        long[] a = SECTIONS.get(section);
        if (a == null) {
            SECTIONS.put(section, a = new long[2]);
        }
        a[0] += dt;
        a[1]++;
        if (--depth <= 0) {
            depth = 0;
            tickTotal += dt;
        }
    }

    /**
     * the end of a game tick: log it if it was slow, and start the next
     */
    private static long lastGc = -1;

    // the collectors' time so far. by reflection: java.management isn't among the libraries the release build's shrinker
    // is given, and it's only ever wanted while measuring
    private static List<?> gcBeans;
    private static java.lang.reflect.Method gcTime;

    private static long gcMs() {
        try {
            if (gcBeans == null) {
                Class<?> f = Class.forName("java.lang.management.ManagementFactory");
                gcBeans = (List<?>) f.getMethod("getGarbageCollectorMXBeans").invoke(null);
                gcTime = Class.forName("java.lang.management.GarbageCollectorMXBean").getMethod("getCollectionTime");
            }
            long t = 0;
            for (Object b : gcBeans) {
                t += Math.max(0, (Long) gcTime.invoke(b));
            }
            return t;
        } catch (ReflectiveOperationException | RuntimeException e) {
            gcBeans = List.of();
            return 0;
        }
    }

    public static void endTick() {
        client = Thread.currentThread();
        long ms = thresholdMs();
        if (ms <= 0) {
            SECTIONS.clear();
            tickTotal = 0;
            return;
        }
        ticks++;
        long gc = gcMs(), gcDelta = lastGc < 0 ? 0 : gc - lastGc;
        lastGc = gc;
        if (gcDelta > 0) {
            SECTIONS.put("gc(any thread, since last tick)", new long[]{gcDelta * 1_000_000L, 1});
        }
        if (tickTotal > ms * 1_000_000L) {
            over++;
            List<Map.Entry<String, long[]>> e = new ArrayList<>(SECTIONS.entrySet());
            e.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder sb = new StringBuilder("[lag] tick ").append(tickTotal / 1_000_000).append(" ms in the bot:");
            for (int i = 0; i < Math.min(6, e.size()); i++) {
                long[] v = e.get(i).getValue();
                if (v[0] < 500_000L) {
                    break;
                }
                sb.append(' ').append(e.get(i).getKey()).append('=').append(v[0] / 100_000 / 10.0);
                if (v[1] > 1) {
                    sb.append("x").append(v[1]);
                }
            }
            String line = sb.toString();
            if (tickTotal > worstNs) {
                worstNs = tickTotal;
                worstWhat = line;
            }
            System.out.println(line);
        }
        SECTIONS.clear();
        tickTotal = 0;
    }

    public static String summary() {
        return ticks == 0 ? "No ticks measured yet (set lagLogMs to something above 0)." :
                String.format("%d of %d ticks over %d ms. Worst: %s", over, ticks, thresholdMs(), worstWhat);
    }
}
