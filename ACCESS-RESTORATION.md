# Building access and restoration

A build has two kinds of work: the requested structure and the temporary access used to reach it. Filling a passage merely because the player walked past it can undo the path the next task needs. Cleanup must consider the route left after an action.

The builder now keeps path-dug schematic cells deferred until construction finishes. The general backfill process yields while the builder owns the job. For Effortless jobs, either `backfill` or `effortlessRestoreTerrain` records terrain dug outside the requested area for the final restoration pass. `allowBreak` remains authoritative: enabling restoration does not enable mining.

With `builderPreserveAccess` enabled (default), a local walking graph checks solid placements against their resulting access route. It also defers closures that cut off remaining nearby work. If the current side would be sealed off, the builder selects reachable stance goals on the retreat side instead. Movement-driven placements use the same snapshot check. Existing A* movement costs still compare walking, mining and scaffolding during construction; when restoration is requested, outside mining costs more to account for replacing it.

Cleanup checks whether removing a support disconnects the player or other nearby cleanup targets. It keeps existing column/top-down ordering where safe, and can move to another stance before removal. It does not add new temporary pillars outside the schematic during cleanup; inaccessible temporary blocks are reported and left in place.

Effortless terrain restoration is a separate non-destructive phase. It protects the job's selected cells, forbids movement mining and movement placement, and places only from existing, reachable stances with a retreat route. A closure is deferred if another pending repair would lose all nearby reachable stances. This lets deeper repairs precede sealing an entrance, without a fixed coordinate order. The remembered entry side is preferred as the retreat reference. If no safe route or materials can be found, the queue pauses with the repair still retained. Start resumes that repair phase after conditions change; it does not rebuild the completed structure.

## Bounds and validation

The graph covers loaded terrain within twelve blocks horizontally and six vertically from the player, with standing, one-block steps, and head clearance. It refreshes after block changes, moving to another cell, or five ticks. It is a local postcondition check, not a whole-world construction search. Stance distance is an access estimate; the existing placement simulator still checks the actual click. It does not prove swimming, ladder, flight or newly scaffolded escape routes. Normal construction falls back to the existing movement planner where no walking reference can be proved. Restoration conservatively refuses those closures.

Pure regression scenarios cover sealing from the wrong side, a usable alternate corridor, repairs behind a closing entrance, bridge dismantling, step headroom, and unknown terrain. Builder and Effortless regression suites also run. These checks do not replace in-game testing of a particular base or terrain layout.
