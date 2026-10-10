package adris.altoclef.trace;

// the text of the lines the monitors and switch traces write, in one place so the shapes are pinned by a test and the
// callers only build them when they are about to say them
public final class Lines {

    private Lines() {
    }

    // a null name is nobody, a NaN priority is a chain that was not active to ask
    public static String chain(String name, float priority) {
        if (name == null) {
            return "none";
        }
        return name + ":" + (Float.isNaN(priority) ? "off" : Float.toString(priority));
    }

    public static String chainSwitch(String from, float fromPriority, String to, float toPriority, String next, float nextPriority) {
        return "switch chain from=" + chain(from, fromPriority) + " to=" + chain(to, toPriority) + " next=" + chain(next, nextPriority);
    }

    public static String repeat(String taskClass, String state, int times) {
        return "repeat: " + taskClass + " \"" + state + "\" x" + times + " in 60 s";
    }

    public static String startStorm(String taskClass) {
        return "MONITOR: start storm " + taskClass + " x" + (StartStormWatch.LIMIT + 1) + " in 60 s";
    }

    public static String cursorStuck(String item, int count, long seconds) {
        return "MONITOR: cursor stuck " + item + " x" + count + " for " + seconds + " s, no container screen open";
    }
}
