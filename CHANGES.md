# What Soprano adds

Everything below is off by default unless it says otherwise. Turn things on in game with `#set <setting> true`, for example `#set allowNeos true`.

- **Neo jumps** (`allowNeos`). Jump around the end of a wall that's 1 or 2 blocks thick and land 2 to 4 blocks out. It runs up first, and bunny hops on the run up if the jump needs the speed. The path is drawn bending around the wall.
- **Momentum jumps** (`allowMomentumJumps`). Jumps a standing start can't make, using a run up and bunny hops: a 4 block gap on the flat, or one up across a gap of 3 (both want 3 or more blocks of run up behind you), drops of 1 to 3 blocks that reach 5 or 6 blocks out, and a few pairs of jumps through a one block pad. Needs `allowParkour`, which `fastMode` turns on for you.
- **Ladder and vine jumps** (`allowClimbJumps`). Catch a ladder or vine mid air across a gap of 1 to 3 blocks, or leap off one onto a ledge or another ladder.
- **Ladder clutches** (`allowLadderClutch`). Survive a long fall by placing a vine or ladder next to where you land, like a water bucket clutch. You need one on your hotbar. `pickupLadders` (on by default) picks the ladder back up afterwards.
- **Fast mode** (`fastMode`). Moves more like a speedrunner and less like a robot. It turns on all the parkour, sprint jumping and head hitters, takes falls that cost some health when that saves time (it stays above `fastModeMinHealth`), cuts corners, smooths out straight runs, places blocks more freely, and leans toward jumps.
- **Boats** (`allowBoats`) and better swimming (`allowSwimming`).
- **AltoClef built in.** Ask for an item and it works out how to get it: mining, crafting, smelting, fighting and eating along the way. `#get iron_pickaxe`, `#food 20`, or `#gamer` to try and beat the game. It does nothing until you give it a task.
- **Path rendering.** The path is a smooth antialiased ribbon with rounded corners, and the search visual is softer.
- **Pathfinder performance.** More movements per second, and the cache buffers get reused instead of reallocated.
- **Chat prefix.** Messages say `[Soprano]`.

The new stuff is new, so expect rough edges. [USAGE.md](USAGE.md) lists the settings that go with these. [FEATURES.md](FEATURES.md) has the full list of what the pathfinder can do. Bug reports and feedback are welcome.
