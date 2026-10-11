# Added by Soprano

These are on top of the Baritone features below, and all are off by default unless it says otherwise. Names in backticks are settings. They're new, so expect some rough edges.

- **Neo jumps** (`allowNeos`). Jump around the end of a wall that's 1 or 2 blocks thick, landing 2 to 4 blocks out. Gets a sprint run up, plus a bunny hop run up when the jump needs the speed. The rendered path bends around the wall.
- **Momentum jumps** (`allowMomentumJumps`). Jumps a standing start can't make, using a run up and bunny hops: a 4 block gap on the flat, or one up across a gap of 3 (both want 3 or more blocks of run up), drops of 1 to 3 blocks that reach 5 or 6 blocks out, and a few pairs of jumps through a one block pad. Needs `allowParkour`.
- **Ladder and vine jumps** (`allowClimbJumps`). Catch a ladder or vine mid air across a gap of 1 to 3 blocks, and leap off one onto a ledge or another ladder.
- **Ladder clutches** (`allowLadderClutch`). Place a vine or ladder next to the landing spot to survive a long fall, like a water bucket clutch. Needs a ladder or vine on the hotbar. `pickupLadders` (on by default) picks the ladder back up afterwards.
- **Fast mode** (`fastMode`). Turns on all the parkour, sprint jumping and head hitters, takes falls that cost some health when it saves time (stays above `fastModeMinHealth`), cuts corners, smooths straight runs, places blocks more freely and leans toward jumps.
- **Boats** (`allowBoats`) and **better swimming** (`allowSwimming`).
- **AltoClef** (`#get`, `#gamer` and friends). [AltoClef](https://github.com/gaucho-matrero/altoclef) runs on top of the pathfinder: ask for an item and it mines, crafts, smelts, fights and eats its way there, with its current task tree shown in the top left. It does nothing until you start a task. Its settings all start with `alto`, see the [AltoClef section of USAGE.md](USAGE.md#altoclef).
- **Rendering.** The path is a smooth antialiased ribbon with rounded corners, and the search visual is softer.
- **Performance.** The pathfinder considers more movements per second and reuses its cache buffers.

# Pathing features

- **Long distance pathing and splicing.** Soprano calculates paths in segments, and precalculates the next segment when the current one is about to end, so that it's moving towards the goal at all times.
- **Chunk caching.** Soprano simplifies chunks to a compact internal 2-bit representation (AIR, SOLID, WATER, AVOID) and stores them in RAM for better very-long-distance pathing. There is also an option to save these cached chunks to disk. [Example](https://www.youtube.com/watch?v=dyfYKSubhdc)
- **Block breaking.** Soprano considers breaking blocks as part of its path. It also takes into account your current tool set and hotbar. For example, if you have an Eff V diamond pick, it may choose to mine through a stone barrier, while if you only had a wood pick it might be faster to climb over it.
- **Block placing.** Soprano considers placing blocks as part of its path. This includes sneak-back-placing, pillaring, etc. It has a configurable penalty for placing a block (1 second by default) to conserve its resources. The list of acceptable throwaway blocks is also configurable, and is cobble, dirt, or netherrack by default. [Example](https://www.youtube.com/watch?v=F6FbI1L9UmU)
- **Falling.** Soprano will fall up to 3 blocks onto solid ground (configurable, if you have Feather Falling and/or don't mind taking a little damage). If you have a water bucket on your hotbar, it will fall up to 20 blocks and place the bucket beneath it. It will fall an unlimited distance into existing still water.
- **Vines and ladders.** Soprano climbs and descends vines and ladders. There is experimental support for strafing to a different ladder / vine column in midair (off by default, setting named `allowVines`). It can break its fall by grabbing ladders / vines midair, and understands when that is and isn't possible.
- **Opening fence gates and doors.**
- **Slabs and stairs.**
- **Falling blocks.** Soprano understands the costs of breaking blocks with falling blocks on top, and includes all of their break costs. Additionally, since it avoids breaking any blocks touching a liquid, it won't break the bottom of a gravel stack below a lava lake (anymore).
- **Avoiding dangerous blocks.** Obviously, it knows not to walk through fire or on magma, not to corner over lava (that deals some damage), not to break any blocks touching a liquid (it might drown), etc.
- **Parkour.** Sprint jumping over 1, 2, or 3 block gaps.
- **Parkour place.** Sprint jumping over a 3 block gap and placing the block to land on while executing the jump. It's really cool.
- **Elytra.** Flying with fireworks, see `#elytra`.
- **Pigs.** It can sort of control pigs. Don't rely on it.

# Pathing method

Soprano uses A*, with some modifications:

- **Segmented calculation.** Traditional A* calculates until the most promising node is in the goal, however in the environment of Minecraft with a limited render distance, we don't know the environment all the way to our goal. Soprano has three possible ways for path calculation to end: finding a path all the way to the goal, running out of time, or getting to the render distance. In the latter two scenarios, the selection of which segment to actually execute falls to the next item (incremental cost backoff). Whenever the path calculation thread finds that the best / most promising node is at the edge of loaded chunks, it increments a counter. If this happens more than 50 times (configurable), path calculation exits early. This happens with very low render distances. Otherwise, calculation continues until the timeout is hit (also configurable) or we find a path all the way to the goal.
- **Incremental cost backoff.** When path calculation exits early without getting all the way to the goal, Soprano needs to select a segment to execute first (assuming it will calculate the next segment at the end of this one). It uses incremental cost backoff to select the best node by varying metrics, then paths to that node. This is unchanged from MineBot and leijurv made a [write-up](https://docs.google.com/document/d/1WVHHXKXFdCR1Oz__KtK8sFqyvSwJN_H4lftkHFgmzlc/edit) that still applies. In essence, it keeps track of the best node by various increasing coefficients, then picks the node with the least coefficient that goes at least 5 blocks from the starting position.
- **Minimum improvement repropagation.** The pathfinder ignores alternate routes that provide minimal improvements (less than 0.01 ticks of improvement), because the calculation cost of repropagating this to all connected nodes is much higher than the half-millisecond path time improvement it would get.
- **Backtrack cost favoring.** While calculating the next segment, Soprano favors backtracking its current segment. The cost is decreased heavily, but is still positive (this won't cause it to backtrack if it doesn't need to). This allows it to splice and jump onto the next segment as early as possible, if the next segment begins with a backtrack of the current one. [Example](https://www.youtube.com/watch?v=CGiMcb8-99Y)
- **Backtrack detection and pausing.** While path calculation happens on a separate thread, the main game thread has access to the latest node considered, and the best path so far (those are rendered light blue and dark blue respectively). When the current best path (rendered dark blue) passes through the player's current position on the current path segment, path execution is paused (if it's safe to do so), because there's no point continuing forward if we're about to turn around and go back that same way. Note that the current best path as reported by the path calculation thread takes into account the incremental cost backoff system, so it's accurate to what the path calculation thread will actually pick once it finishes.

# Chat control

See [the usage page](USAGE.md).

# Goals

The pathing goal can be set to any of these:

- **GoalBlock**: one specific block that the player should stand inside at foot level
- **GoalXZ**: an X and a Z coordinate, used for long distance pathing
- **GoalYLevel**: a Y coordinate
- **GoalTwoBlocks**: a block position that the player should stand in, either at foot or eye level
- **GoalGetToBlock**: a block position that the player should stand adjacent to, below, or on top of
- **GoalNear**: a block position that the player should get within a certain radius of, used for following entities
- **GoalAxis**: a block position on an axis or diagonal axis (so x=0, z=0, or x=z), and y=120 (configurable)
- **GoalRunAway**: anywhere at least a certain distance away from some positions, useful for retreating in combat
- **GoalStrictDirection**: keep going in one direction from a starting point, used for `#tunnel`

And a couple that are made of other goals:

- **GoalComposite**: a list of other goals, any one of which satisfies the goal. For example, `mine diamond_ore` creates a `GoalComposite` of `GoalTwoBlocks`s for every diamond ore location it knows of.
- **GoalInverted**: the opposite of another goal, gets as far away from it as possible. This is what `#invert` uses.

# Future features

Things it doesn't have yet:

- Trapdoors

See [issues](https://github.com/AverWasTaken/soprano/issues) for more.

Things it may not ever have, from most likely to least likely

- Horses (2x3 path instead of 1x2)
