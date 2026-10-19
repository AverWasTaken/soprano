<div align="center">

<img width="160" height="160" alt="Soprano" src="https://github.com/user-attachments/assets/38c4de7c-deb9-4d52-8d5e-261255312d73" />

# Soprano

**The Minecraft pathfinding bot [Baritone](https://github.com/cabaletta/baritone), with extra movement tech and [AltoClef](https://github.com/gaucho-matrero/altoclef) built in.**

[![Release](https://img.shields.io/github/v/release/AverWasTaken/soprano?style=flat-square)](https://github.com/AverWasTaken/soprano/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/AverWasTaken/soprano/gradle_build.yml?branch=26.3&style=flat-square&label=build)](https://github.com/AverWasTaken/soprano/actions/workflows/gradle_build.yml)
[![License](https://img.shields.io/badge/license-LGPL--3.0-blue?style=flat-square)](LICENSE)

<a href="https://github.com/AverWasTaken/soprano/releases/latest"><img alt="Download the latest release" src="https://img.shields.io/badge/Download-latest%20release-2ea44f?style=for-the-badge&logo=github&logoColor=white"></a>
<a href="https://discord.gg/ccjQaGSSkT"><img alt="Join the Discord" src="https://img.shields.io/badge/Discord-join%20the%20server-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>

[Install](#install) · [Quick start](#quick-start) · [What's new](CHANGES.md) · [Usage](USAGE.md) · [Features](FEATURES.md) · [Building](SETUP.md) · [Contributing](CONTRIBUTING.md)

</div>

---

> [!IMPORTANT]
> Soprano is a drop-in replacement for Baritone. Don't install both.

## Install

| Minecraft | Latest Soprano | Loaders | Download |
|:--|:--|:--|:--|
| 26.3 | 1.1.4 | Fabric, Forge, NeoForge | [Soprano v1.1.4](https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.1.4) |
| 1.21.11 | 1.2.0 | Fabric, Forge, NeoForge | Coming soon |
| 1.21.4 (2b2t) | 1.0.2 | Fabric, Forge, NeoForge | [Soprano v1.0.2](https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.0.2) |

<details>
<summary><b>The version I want isn't here</b></summary>
<br>

Join our [Discord](https://discord.gg/ccjQaGSSkT) and put a request in the `version-request` channel!

</details>

1. Open the release for your Minecraft version.
2. Download the jar for your loader and drop it in your `mods` folder, replacing Baritone if you had it.
3. In game, try `#goto 1000 500`, then `#stop`.

<details>
<summary><b>Which jar do I want?</b></summary>
<br>

Most people want the `api` jar.

| Jar | What it is |
|:--|:--|
| `soprano-api-<loader>-<version>.jar` | **The one you want.** Everything outside `baritone.api` is obfuscated, so other mods can still use the API. |
| `soprano-standalone-<loader>-<version>.jar` | Everything is obfuscated. Slightly faster, but other mods can't use the API. |
| `soprano-unoptimized-<loader>-<version>.jar` | Nothing is obfuscated. Use it when reporting a bug, the stack traces are readable. |

</details>

> [!NOTE]
> Each branch of this repo is one Minecraft version, named after it (`1.21.4`, `1.21.11`, `26.3`). The newest version lives on `26.3`, the default branch. You're looking at **26.3**. To build it yourself, see [SETUP.md](SETUP.md).

## Quick start

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

Soprano ships Baritone's API as it was, so existing integrations keep working. See [Baritone's javadocs](https://baritone.leijurv.com/) for the API, and [Settings.java](src/api/java/baritone/api/Settings.java) for the new settings.

```java
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().allowNeos.value = true;
BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

Anything outside `baritone.api` isn't supported for outside use.

## Bugs and contributing

Found something broken? [Open an issue](https://github.com/AverWasTaken/soprano/issues) and include your `latest.log`, your Minecraft version and the output of `#modified`. Questions are welcome in the [Discord](https://discord.gg/ccjQaGSSkT).

To change code, read [CONTRIBUTING.md](CONTRIBUTING.md). Some of the work done here has been sent back to Baritone as pull requests. Bugs that also happen in plain Baritone are worth reporting [upstream](https://github.com/cabaletta/baritone/issues) too.

## Credits

Soprano is Baritone, made by [cabaletta](https://github.com/cabaletta), [leijurv](https://github.com/leijurv), Brady and all of [Baritone's contributors](https://github.com/cabaletta/baritone/graphs/contributors). Without their years of work there's nothing to fork. The pathfinder, the API, the commands and most of the movement code are theirs.

The built-in AltoClef is [AltoClef](https://github.com/gaucho-matrero/altoclef) by gaucho-matrero and Adris Jautakas, using MarvionKirito's 1.21 fork. It's MIT licensed, see [LICENSE-AltoClef](LICENSE-AltoClef).

## License

Soprano is licensed under the [LGPL-3.0](LICENSE), same as Baritone.

## Star history

<a href="https://star-history.com/#AverWasTaken/soprano&Date">
  <img alt="Star history" src="https://api.star-history.com/svg?repos=AverWasTaken/soprano&type=Date" width="600">
</a>
