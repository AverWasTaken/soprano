package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;

// what a phase handler gets from the engine each tick
public interface GamerContext {
    GamerConfig cfg();

    // durable memory, saved by the engine. anything that should survive a relog goes in here
    RunState state();

    GamerFacts facts();

    // 1 on the first try of this phase, 2 after a retry...
    int attempt();

    double secondsInPhase();

    // write RunState now (the engine also saves on phase change and every ~20 s when dirty)
    void save();

    // tell the watchdog something useful happened so the stall timer restarts
    void progress(String what);

    // this phase cannot work the way it is going: hands the decision to the handler's onTimeout
    void fail(String reason);

    // one line in chat, soprano prefix, for things the user should know ("giving up on casting, mining obsidian")
    void log(String line);

    // lets baritone path over end portal blocks (volatile toggle in AltoClefSettings), reset by the engine on stop
    void walkOnEndPortal(boolean on);
}
