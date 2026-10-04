# Contributing

Bug reports, ideas and pull requests are all welcome.

## Branches

Each branch is one Minecraft version. `main` is the newest one (26.3 right now) and `1.21.4` is the 1.21.4 branch. Open your pull request against the version branch you're targeting, and don't target `main` for a fix to an older version.

## Building and testing

You need JDK 25 on `main` (the `java_version` in `gradle.properties`, same as [the CI workflow](.github/workflows/gradle_build.yml)). The `1.21.4` branch needs JDK 21.

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
2. Tag the commit as `soprano-v<version>`, for example `soprano-v1.1.0`. Like Baritone, every Minecraft version branch gets its own version line (1.0.x is Minecraft 1.21.4, 1.1.x is Minecraft 26.3, the next port gets the next one), so a version number always means one Minecraft version. The `soprano-` prefix is there because the repo still has Baritone's history, and a plain `v1.0.0` would clash with Baritone's own tags for anyone who fetches from upstream.
3. Push the tag: `git push origin soprano-v1.1.0`.

The [release workflow](.github/workflows/release.yml) builds with the JDK from `java_version` in `gradle.properties`, passes the version from the tag to Gradle and creates a GitHub Release called "Soprano v1.1.0" laid out like Baritone's: every jar flavor plus `checksums.txt`, and a one line note saying which Minecraft version and loaders it's for. The Minecraft version comes from `minecraft_version` in `gradle.properties`.

If a release goes wrong, delete the release and the tag on GitHub, fix it, and push the tag again.
