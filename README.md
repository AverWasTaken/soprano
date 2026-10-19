<div align="center">
  <h1>Soprano</h1>
  <img width="180" height="180" alt="Soprano" src="https://github.com/user-attachments/assets/38c4de7c-deb9-4d52-8d5e-261255312d73" />
</div>


<div align="center">

A fork of [Baritone](https://github.com/cabaletta/baritone), the Minecraft pathfinding bot, with extra movement tech and [AltoClef](https://github.com/gaucho-matrero/altoclef) built in.

[![Release](https://img.shields.io/github/v/release/AverWasTaken/soprano)](https://github.com/AverWasTaken/soprano/releases)
[![Build](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml/badge.svg)](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml)
[![License](https://img.shields.io/badge/license-LGPL--3.0-green.svg)](LICENSE)

</div>

<p align="center">
  <a href="https://github.com/AverWasTaken/soprano/releases/latest"><img alt="Download the latest release" src="https://img.shields.io/badge/Download-latest%20release-2ea44f?style=for-the-badge&logo=github&logoColor=white"></a>
  <a href="https://discord.gg/ccjQaGSSkT"><img alt="Join the Discord" src="https://img.shields.io/badge/Discord-join%20the%20server-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
</p>

<div align="center">

[Install](#install) · [What's new](CHANGES.md) · [Usage](USAGE.md) · [Features](FEATURES.md) · [Building](SETUP.md) · [Contributing](CONTRIBUTING.md)

</div>
<div align="center">
Soprano keeps everything Baritone does.  <br> The `baritone.api` packages are unchanged, so mods built on Baritone's API keep working. Soprano is a drop-in replacement. Don't install both.
</div>

<div align="center">

[CHANGES.md](CHANGES.md) has everything Soprano adds on top of Baritone.

</div>

## Install

1. Find your Minecraft version in the table below and open its release.
2. Download the jar for your loader (Fabric, Forge or NeoForge) and drop it in your `mods` folder, replacing Baritone if you had it.
3. In game, try `#goto 1000 500`, then `#stop`.

| Minecraft | Latest Soprano | Loaders | Download |
|---|---|---|---|
| 26.3 | 1.1.4 | Fabric, Forge, NeoForge | [Soprano v1.1.4](https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.1.4) |
| 1.21.11 | 1.2.0 | Fabric, Forge, NeoForge | coming soon |
| 1.21.4 (2b2t) | 1.0.2 | Fabric, Forge, NeoForge | [Soprano v1.0.2](https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.0.2) |

Each branch of this repo is one Minecraft version, named after it (`1.21.4`, `1.21.11`, `26.3`). The newest Minecraft version lives on `26.3`, which is the default branch. This branch is 26.3.

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

## Stars over time

[![Stars over time](https://api.star-history.com/svg?repos=AverWasTaken/soprano&type=Date)](https://star-history.com/#AverWasTaken/soprano&Date)
