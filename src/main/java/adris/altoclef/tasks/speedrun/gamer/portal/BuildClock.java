package adris.altoclef.tasks.speedrun.gamer.portal;

// the two clocks of a portal build, in the task's own ticks. clearing a rough site has its own, so digging out a bump
// doesn't eat the time the build itself gets
final class BuildClock {
    static final int PREP_TICKS = 20 * 60;
    static final int BUILD_TICKS = 20 * 85;

    enum Verdict {OK, PREP_OUT, BUILD_OUT}

    private int prep;
    private int build;

    Verdict tick(boolean prepping) {
        if (prepping) {
            return ++prep > PREP_TICKS ? Verdict.PREP_OUT : Verdict.OK;
        }
        return ++build > BUILD_TICKS ? Verdict.BUILD_OUT : Verdict.OK;
    }
}
