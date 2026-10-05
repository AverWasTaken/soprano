package adris.altoclef.tasks.speedrun.gamer;

// what a handler wants after its budget or stall timer ran out
public enum Timeout {
    // reset the phase's sub state and go again
    RETRY,
    // give up on this phase and move on (only sane for optional phases)
    SKIP,
    // stop the whole run, state stays saved
    STUCK
}
