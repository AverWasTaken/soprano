# Contributing

Bug reports, ideas and pull requests are all welcome.

## Branches

Each branch is one Minecraft version, named after it (`1.21.4`, `1.21.11`, `26.3`). The newest version lives on `26.3`, which is the default branch. Open your pull request against the branch for the version you're targeting. Don't target `26.3` for a fix to an older version.

## Building and testing

You need the JDK in `java_version` in `gradle.properties` (21 on `1.21.4`, 25 on `26.3`), same as [the CI workflow](.github/workflows/gradle_build.yml).

```
./gradlew build            # everything, jars end up in dist/
./gradlew test             # unit tests
./gradlew :fabric:runClient  # dev client with Soprano loaded
```

On Windows use `gradlew` instead of `./gradlew`.

## Code style

- Comments are `//` lines. They're short, lowercase, a little silly, and about the why. Don't restate the code. Numbers from profiling are great ("this was worth 15% by itself, i was not expecting that").
- No javadoc outside `src/api`. The api module is javadoc'd and built with `-Xwerror`, so a broken javadoc comment there fails the build. Everything else isn't.
- Don't touch `baritone.api` signatures lightly. Mods built on Baritone's API have to keep working.
- Commit subjects are imperative and capitalized, with no prefixes: "Speed up the A* bookkeeping", not "perf: speed up". Put the why in the body.

## License headers

Files that came from Baritone keep their existing "This file is part of Baritone" header, untouched. Brand-new files get the same LGPL-3.0 header with Baritone swapped for Soprano:

```java
/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */
```

Files that get contributed back upstream keep the Baritone header, so syncing with upstream stays clean.

## New movements

A new movement should come with a pure, unit-testable sim where that makes sense. Keep the physics (how far a jump goes, where a sprint run up lands) in plain Java that doesn't need a running game, and test it in `src/test`. It's much easier to tune a jump against a test than by watching a bot fall off things. If the movement has a setting, it should be off by default until it has had a good beating.

## Pull requests

Say what it does, why it's safe, and what you actually ran. Numbers help for performance work, along with how you measured them. Keep the line `<!-- No UwU's or OwO's allowed -->` from the template.

## Releasing

For maintainers.

1. Bump `mod_version` in `gradle.properties` and commit it on the version branch.
2. Tag the commit as `soprano-v<version>`, for example `soprano-v1.0.0`. Like Baritone, every Minecraft version branch gets its own version line (1.0.x is Minecraft 1.21.4, 1.1.x is Minecraft 26.3, 1.2.x is Minecraft 1.21.11, the next port gets the next one), so a version number always means one Minecraft version. The `soprano-` prefix is there because the repo still has Baritone's history, and a plain `v1.0.0` would clash with Baritone's own tags for anyone who fetches from upstream.
3. Push the tag: `git push origin soprano-v1.0.0`.

The [release workflow](.github/workflows/release.yml) builds with the JDK from `java_version` in `gradle.properties`, passes the version from the tag to Gradle and creates a GitHub Release called "Soprano v1.1.0 (26.3)" with the api jar per loader plus the unoptimized Fabric jar, a one line note saying which Minecraft version and loaders it's for, and `release-notes/<version>.md` under it. Only 26.3 releases get marked Latest. A `-anyversion` tag (built from `version-agnostic`) becomes a Fabric only pre-release. The Minecraft version comes from `minecraft_version` in `gradle.properties`.

Don't change the release title format or the jar names. The in-game updater finds a player's release by the `(1.21.4)` at the end of the title (any version builds go by `(base 26.3)` plus the `-anyversion` tag suffix), and picks the jar by its `soprano-<api|unoptimized>-<loader>-<version>.jar` name. Change either and every installed copy stops seeing updates. A release made by hand needs the same title, and its jars have to come from a build with `-Prelease_version`, because the updater refuses a jar that doesn't carry its own version and Minecraft version.

If a release goes wrong, delete the release and the tag on GitHub, fix it, and push the tag again.

### Nightlies

A nightly is a build that's out early and allowed to be buggy. Players on a stable build only ever get stable updates, players on a nightly only get newer nightlies, and the title screen card offers a small "Switch to nightly" / "Switch to stable" button when they're up to date. Which track a player is on comes from the version of the jar they run, there is no setting.

- Tag format: `soprano-v<next stable>-nightly.<N>`, for example `soprano-v1.1.7-nightly.1`, and `soprano-v1.1.7-nightly.1-anyversion` for the version-agnostic build. `-anyversion` always goes last, the workflow strips it off the end. N starts at 1 and counts up per stable version.
- Nightlies come from `26.3` and `version-agnostic` only. The older lines don't get them.
- No `mod_version` bump needed, the jars get their version from the tag.
- One nightly release per line on GitHub. When you release nightly N+1, delete nightly N's GitHub release but keep its tag.
- The workflow makes them pre-releases, never Latest, puts a "Nightly build. Expect bugs." line at the top of the notes and skips the Discord post.
- Once the stable `1.1.7` is out, the `1.1.7` nightlies count as older than it, so nightly players get the stable as a normal update and stable players aren't offered a nightly until `1.1.8-nightly.1`.
