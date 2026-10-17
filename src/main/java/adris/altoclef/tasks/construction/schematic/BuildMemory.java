package adris.altoclef.tasks.construction.schematic;

import java.util.LinkedHashSet;
import java.util.Set;

// what a survival build remembers between starts. the schematics tab keeps one per world and file (PlacementStore's
// choices), so a stop and a start again, or a restart of the game, picks up the same answers. game thread only
public interface BuildMemory {

    // item ids the player unchecked in the tab, read once when the build starts
    Set<String> excluded();

    // a chat build or a test: remembers for as long as the task lives and no longer
    static BuildMemory inMemory(Set<String> excluded) {
        return new BuildMemory() {
            private final Set<String> out = new LinkedHashSet<>(excluded);

            @Override
            public Set<String> excluded() {
                return Set.copyOf(out);
            }
        };
    }
}
