# Soprano

A fork of [Baritone](https://github.com/cabaletta/baritone), the Minecraft pathfinding bot, with a lot of extra movement tech.

[![Release](https://img.shields.io/github/v/release/AverWasTaken/soprano)](https://github.com/AverWasTaken/soprano/releases)
[![Build](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml/badge.svg)](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml)
[![License](https://img.shields.io/badge/license-LGPL--3.0-green.svg)](LICENSE)

[Install](#install) · [What's new](#what-soprano-adds) · [Usage](USAGE.md) · [Features](FEATURES.md) · [Building](SETUP.md) · [Contributing](CONTRIBUTING.md)

Soprano keeps everything Baritone does: `#goto`, `#mine`, `#build`, `#farm`, `#elytra`, the lot. On top of that it adds jumps and movement Baritone doesn't have, like neo jumps, ladder and vine jumps, and long fall clutches.

The `baritone.api` packages are unchanged, so mods built on Baritone's API keep working. Soprano is a drop-in replacement. Don't install both.

The new features are new, so expect rough edges. Bug reports and feedback are welcome.

## What Soprano adds

Everything below is off by default unless it says otherwise. Turn things on in game with `#set <setting> true`, for example `#set allowNeos true`.

- **Neo jumps** (`allowNeos`). Jump around the end of a wall that's 1 or 2 blocks thick and land 2 to 4 blocks out. It runs up first, and bunny hops on the run up if the jump needs the speed. The path is drawn bending around the wall.
- **Momentum jumps** (`allowMomentumJumps`). Jumps a standing start can't make, using a run up and bunny hops: a 4 block gap on the flat, or one up across a gap of 3 (both want 3 or more blocks of run up behind you), drops of 1 to 3 blocks that reach 5 or 6 blocks out, and a few pairs of jumps through a one block pad. Needs `allowParkour`, which `experimentalMovement` turns on for you.
- **Ladder and vine jumps** (`allowClimbJumps`). Catch a ladder or vine mid air across a gap of 1 to 3 blocks, or leap off one onto a ledge or another ladder.
- **Ladder clutches** (`allowLadderClutch`). Survive a long fall by placing a vine or ladder next to where you land, like a water bucket clutch. You need one on your hotbar. `pickupLadders` (on by default) picks the ladder back up afterwards.
- **Experimental movement** (`experimentalMovement`). Moves more like a speedrunner and less like a robot. It turns on all the parkour, sprint jumping and head hitters, takes falls that cost some health when that saves time (it stays above `experimentalMinHealth`), cuts corners, smooths out straight runs, places blocks more freely, and leans toward jumps.
- **Boats** (`allowBoats`) and better swimming (`allowSwimming`).
- **Path rendering.** The path is a smooth antialiased ribbon with rounded corners, and the search visual is softer.
- **Pathfinder performance.** More movements per second, and the cache buffers get reused instead of reallocated.
- **Chat prefix.** Messages say `[Soprano]`.

[USAGE.md](USAGE.md) lists the settings that go with these. [FEATURES.md](FEATURES.md) has the full list of what the pathfinder can do.

## Install

1. Go to [Releases](https://github.com/AverWasTaken/soprano/releases) and download the jar for your Minecraft version and loader (Fabric, Forge or NeoForge).
2. Drop it in your `mods` folder, replacing Baritone if you had it.
3. In game, try `#goto 1000 500`, then `#stop`.

Each branch of this repo is one Minecraft version. `main` is the newest one (26.3 right now), and the `1.21.4` branch is the 1.21.4 version.

Releases come in three flavors. Most people want the `api` jar.

| Jar | What it is |
|---|---|
| `soprano-api-<loader>-<version>.jar` | The one you want. Everything outside `baritone.api` is obfuscated, so other mods can still use the API. |
| `soprano-standalone-<loader>-<version>.jar` | Everything is obfuscated. Slightly faster, but other mods can't use the API. |
| `soprano-unoptimized-<loader>-<version>.jar` | Nothing is obfuscated. Use it when you're reporting a bug, the stack traces are readable. |

See [SETUP.md](SETUP.md) for building it yourself.

## Usage basics

Commands start with `#`.

```
#goto 1000 500
#mine diamond_ore
#set allowNeos true
#stop
```

`#help` lists every command, and it's clickable. [USAGE.md](USAGE.md) covers commands, settings and common problems.

## Using the API

Soprano ships Baritone's API as it was, so existing integrations work as they are. The docs for it are [Baritone's javadocs](https://baritone.leijurv.com/), and the new settings are in [Settings.java](src/api/java/baritone/api/Settings.java).

```java
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().allowNeos.value = true;
BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

Anything outside `baritone.api` isn't supported for outside use.

## Bugs and contributing

Found something broken? [Open an issue](https://github.com/AverWasTaken/soprano/issues). It helps a lot to include `latest.log`, your Minecraft version and the output of `#modified`.

If you want to change code, read [CONTRIBUTING.md](CONTRIBUTING.md). Some of the work done here has been sent back to Baritone as pull requests, and bugs that also happen in plain Baritone are best reported [upstream](https://github.com/cabaletta/baritone/issues) too.

## Credits

Soprano is Baritone, made by [cabaletta](https://github.com/cabaletta), [leijurv](https://github.com/leijurv), Brady and all of [Baritone's contributors](https://github.com/cabaletta/baritone/graphs/contributors). Without their years of work there's nothing to fork. The pathfinder, the API, the commands and most of the movement code are theirs.

Soprano is licensed under the LGPL-3.0, same as Baritone. See [LICENSE](LICENSE).

## Stars over time

[![Stars over time](https://api.star-history.com/svg?repos=AverWasTaken/soprano&type=Date)](https://star-history.com/#AverWasTaken/soprano&Date)
