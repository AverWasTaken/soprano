# Soprano

A fork of [Baritone](https://github.com/cabaletta/baritone), the Minecraft pathfinding bot, with extra movement tech and [AltoClef](https://github.com/gaucho-matrero/altoclef) built in.

[![Release](https://img.shields.io/github/v/release/AverWasTaken/soprano)](https://github.com/AverWasTaken/soprano/releases)
[![Build](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml/badge.svg?branch=1.21.4)](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml)
[![License](https://img.shields.io/badge/license-LGPL--3.0-green.svg)](LICENSE)

[Install](#install) · [What's different](#whats-different) · [Usage](USAGE.md) · [Features](FEATURES.md) · [Building](SETUP.md) · [Contributing](CONTRIBUTING.md)

Soprano keeps everything Baritone does: `#goto`, `#mine`, `#build`, `#farm`, `#elytra`, the lot. The `baritone.api` packages are unchanged, so mods built on Baritone's API keep working. Soprano is a drop-in replacement. Don't install both.

## What's different

- **More ways to move.** Neo jumps, momentum jumps, ladder and vine jumps, ladder clutches, boats and better swimming, plus `experimentalMovement`, which makes the bot move more like a speedrunner than a robot. All of it is off by default.
- **AltoClef built in.** Ask for an item and it works out how to get it: mining, crafting, smelting, fighting and eating along the way. `#get iron_pickaxe`, `#food 20`, or `#gamer` to try and beat the game. It does nothing until you give it a task.
- **Faster and smoother.** The pathfinder gets through more movements per second, and the path is drawn as a smooth ribbon.

The new stuff is new, so expect rough edges. [FEATURES.md](FEATURES.md) goes through every addition and the setting behind it, and [USAGE.md](USAGE.md) has the commands. Bug reports and feedback are welcome.

## Install

1. Go to [Releases](https://github.com/AverWasTaken/soprano/releases) and download the jar for your Minecraft version and loader (Fabric, Forge or NeoForge).
2. Drop it in your `mods` folder, replacing Baritone if you had it.
3. In game, try `#goto 1000 500`, then `#stop`.

Each branch of this repo is one Minecraft version, named after it (`1.21.4`, `26.3`). The newest Minecraft version lives on `26.3`, which is the default branch. This branch is 1.21.4.

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
#get iron_pickaxe
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

The built-in AltoClef is [AltoClef](https://github.com/gaucho-matrero/altoclef) by gaucho-matrero and Adris Jautakas, using MarvionKirito's 1.21 fork. It's MIT licensed, see [LICENSE-AltoClef](LICENSE-AltoClef).

Soprano is licensed under the LGPL-3.0, same as Baritone. See [LICENSE](LICENSE).
