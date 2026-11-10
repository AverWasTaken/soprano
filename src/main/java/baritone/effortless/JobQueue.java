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

package baritone.effortless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// the jobs waiting their turn, in the order they'll run. an entry is one job or a merged group of them: a group runs
// as one build, so the builder works on every shape in it at once instead of finishing one before starting the next.
// no world and no builder in here, the manager runs it and the store saves it
public final class JobQueue {

    public enum State {
        QUEUED, RUNNING, DONE, FAILED
    }

    public static final class Entry {
        private final List<EffortlessJob> jobs;
        private String name;
        private State state = State.QUEUED;
        private String why;
        private final long added;
        private boolean shown;

        public Entry(List<EffortlessJob> jobs, String name, long added) {
            if (jobs.isEmpty()) {
                throw new IllegalArgumentException("an entry needs a job");
            }
            this.jobs = List.copyOf(jobs);
            this.name = name;
            this.added = added;
        }

        public List<EffortlessJob> jobs() {
            return jobs;
        }

        public boolean merged() {
            return jobs.size() > 1;
        }

        public String name() {
            if (name != null) {
                return name;
            }
            return merged() ? jobs.size() + " shapes" : jobs.get(0).name;
        }

        public void rename(String n) {
            this.name = n == null || n.isBlank() ? null : n.trim();
        }

        // the name it was given, null for the made up one
        public String givenName() {
            return name;
        }

        public State state() {
            return state;
        }

        public String why() {
            return why;
        }

        public void state(State s, String reason) {
            this.state = s;
            this.why = reason;
        }

        public long added() {
            return added;
        }

        public boolean shown() {
            return shown;
        }

        public void shown(boolean on) {
            this.shown = on;
        }

        public int cells() {
            int n = 0;
            for (EffortlessJob j : jobs) {
                n += j.mask.count();
            }
            return n;
        }

        public boolean places() {
            return jobs.stream().anyMatch(j -> j.mode == EffortlessJob.Mode.PLACE);
        }

        public boolean breaks() {
            return jobs.stream().anyMatch(j -> j.mode == EffortlessJob.Mode.BREAK);
        }

        public JobSchematic schematic() {
            return new JobSchematic(jobs);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private boolean running;

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Entry get(int i) {
        return entries.get(i);
    }

    public Entry add(EffortlessJob job, long now) {
        Entry e = new Entry(List.of(job), null, now);
        entries.add(e);
        return e;
    }

    public void add(Entry e) {
        entries.add(e);
    }

    public void remove(int i) {
        entries.remove(i);
    }

    // moves the entry at from so it ends up at to, the rest keep their order
    public void move(int from, int to) {
        if (from == to) {
            return;
        }
        Entry e = entries.remove(from);
        entries.add(Math.max(0, Math.min(to, entries.size())), e);
    }

    // the entries at these indices become one group, in their queue order, where the first of them was. a running
    // entry can't be merged, it's already in the builder's hands
    public Entry merge(List<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        Collections.sort(sorted);
        if (sorted.size() < 2) {
            throw new IllegalArgumentException("pick two or more to merge");
        }
        List<EffortlessJob> jobs = new ArrayList<>();
        long added = Long.MAX_VALUE;
        for (int i : sorted) {
            Entry e = entries.get(i);
            if (e.state == State.RUNNING) {
                throw new IllegalArgumentException("can't merge the one that's running");
            }
            jobs.addAll(e.jobs);
            added = Math.min(added, e.added);
        }
        Entry group = new Entry(jobs, null, added);
        for (int k = sorted.size() - 1; k >= 0; k--) {
            entries.remove((int) sorted.get(k));
        }
        entries.add(sorted.get(0), group);
        return group;
    }

    // a group back into its jobs, in place
    public void unmerge(int i) {
        Entry e = entries.get(i);
        if (!e.merged()) {
            return;
        }
        if (e.state == State.RUNNING) {
            throw new IllegalArgumentException("can't split the one that's running");
        }
        entries.remove(i);
        List<Entry> parts = new ArrayList<>();
        for (EffortlessJob j : e.jobs) {
            parts.add(new Entry(List.of(j), null, e.added));
        }
        entries.addAll(i, parts);
    }

    public void clearDone() {
        entries.removeIf(e -> e.state == State.DONE);
    }

    // the next one to run, null when there's nothing queued
    public Entry next() {
        for (Entry e : entries) {
            if (e.state == State.QUEUED) {
                return e;
            }
        }
        return null;
    }

    public Entry runningEntry() {
        for (Entry e : entries) {
            if (e.state == State.RUNNING) {
                return e;
            }
        }
        return null;
    }

    public boolean running() {
        return running;
    }

    public void running(boolean on) {
        this.running = on;
    }

    // 1 based position of e among the entries that aren't done, and how many those are. for "job 2 of 5"
    public int[] progress(Entry e) {
        int pos = 0;
        int total = 0;
        for (Entry x : entries) {
            if (x.state == State.DONE && x != e) {
                continue;
            }
            total++;
            if (x == e) {
                pos = total;
            }
        }
        return new int[]{pos, total};
    }
}
