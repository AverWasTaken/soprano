package adris.altoclef.commands;

import java.util.List;

// a routine the way CustomTasks.json has it. id is the one word #custom takes, name is what the tab shows (spaces ok)
public record StoredRoutine(String id, String name, String description, List<RoutineStep> steps) {

    public StoredRoutine {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
