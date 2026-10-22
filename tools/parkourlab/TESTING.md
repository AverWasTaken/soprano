# Parkour Lab v3

A test course for Soprano's parkour: 14 sections and 126 jumps, each section aimed at one kind of jump, ending in the chess level.

It's built at **x 1000, y 150, z 1000**, floating in the air, and runs east (+x) for about 410 blocks. Gold pads are checkpoints. Fall below y 140 and you're put back on your last one.

## Setup (Minecraft 26.3)

**Easiest:** unzip `ParkourLab-world.zip` into your `.minecraft/saves` folder and open the "Parkour Lab" world. The course is already built and you spawn on the start pad. Cheats are on, and it's peaceful.

**Or in your own world:** drop `ParkourLab.zip` into the world's `datapacks` folder and run `/function parkourlab:build`. If you already have the v2 world, replace its `datapacks/ParkourLab.zip` with this one, then `/reload` and `/function parkourlab:build`.

Then:

- `/function parkourlab:start` puts you on the start pad, starts the timer and gives you infinite saturation, so the bot can always sprint.
- `/function parkourlab:cp/<n>` jumps straight to checkpoint n (0 to 13).
- `/function parkourlab:stop` stops the timer. `/function parkourlab:clear` removes the course.

Every time you reach a checkpoint, chat shows that section's time and how many times you fell.

## Bot settings

```
#set allowParkour true
#set allowTungstenJumps true
#set allowNeos true
#set allowMomentumJumps true
#set allowClimbJumps true
#set allowBreak false
#set allowPlace false
#set chatDebug true
```

`allowBreak`/`allowPlace false` make it behave like a parkour server, where it can't dig through or bridge. `chatDebug` shows why a jump gave up.

## Checkpoints

To test one section, teleport to its start checkpoint, then `#goto` the next one:

| # | Section | Start with | Then |
|---|---|---|---|
| 1 | Warm-up | `/function parkourlab:cp/0` | `#goto 1023 150 1000` |
| 2 | Diagonals | `/function parkourlab:cp/1` | `#goto 1046 150 1000` |
| 3 | Ups and downs | `/function parkourlab:cp/2` | `#goto 1071 148 1007` |
| 4 | Neos | `/function parkourlab:cp/3` | `#goto 1085 148 1007` |
| 5 | Four blockers | `/function parkourlab:cp/4` | `#goto 1136 148 1009` |
| 6 | Head hitters | `/function parkourlab:cp/5` | `#goto 1191 147 1014` |
| 7 | Odd blocks | `/function parkourlab:cp/6` | `#goto 1215 147 1015` |
| 8 | Fence run | `/function parkourlab:cp/7` | `#goto 1243 147 1016` |
| 9 | Ladders | `/function parkourlab:cp/8` | `#goto 1262 151 1016` |
| 10 | Ladder neos | `/function parkourlab:cp/9` | `#goto 1287 153 1016` |
| 11 | Ice palace | `/function parkourlab:cp/10` | `#goto 1324 153 1018` |
| 12 | Sky tower | `/function parkourlab:cp/11` | `#goto 1336 159 1018` |
| 13 | Slime factory | `/function parkourlab:cp/12` | `#goto 1367 155 1018` |
| 14 | Chess | `/function parkourlab:cp/13` | `#goto 1409 163 1024` |

Full run: `/function parkourlab:start`, then `#goto 1409 163 1024`.

## What the sections test

1. **Warm-up.** Straight 1, 2 and 3 gaps, one up, a drop.
2. **Diagonals.** 2x2, 3x2, 3x3, 2x3, 4x1 and 1x4 jumps on the level.
3. **Ups and downs.** Diagonal jumps one up, and one, two and three down across gaps.
4. **Neos.** Round the end of a 1-thick wall, then a 2-thick one.
5. **Four blockers.** 4-block gaps: straight and 4+1 with a runway behind, 4 gap one down from a single block, 3+1 up, 4+2 one down.
6. **Head hitters.** Ceilings over the jump: 1, 2 and 3 gaps, a 3+1, a diagonal, and three new ones:
   - a 4-block gap under a ceiling
   - a 4+1 under a ceiling
   - a 5-block gap one down, after a run-up under a ceiling at head height

   The bot hops along the low runway, bonking the ceiling each time, to build speed.
7. **Odd blocks.** Landings on a fence, wall, pane, iron bars, top slab, stairs, hopper, top trapdoor and end rod.
8. **Fence run.** Fence to fence, wall to wall, pane to pane, with gaps and diagonals between.
9. **Ladders.** Jump onto a ladder across a gap and climb it, then the same with a vine.
10. **Ladder neos (new).** Round the end of a wall onto a ladder on its far side, climb onto the wall top and walk along it. Then the same with a vine.
11. **Ice palace.** Takeoffs off packed, blue and normal ice, with gaps up to 4+2 and 4+3.
12. **Sky tower.** A spiral of diagonal one-up jumps round a pillar.
13. **Slime factory.** Drop onto slime five blocks down and bounce across to ledges no normal jump reaches.
14. **Chess.** Pawn captures, a knight's tour, the bishop's diagonal, the rook's battlements, a neo round the queen, and the king's ladder to "Checkmate".

## Results

**In the real game** (a 26.3 client running the Fabric build, with the default look wobble on):

- **Whole course:** start to Checkmate from one `#goto`, 0 falls, about 2 minutes, in every full run.
- **Neos:** 5 runs in a row clean.
- **Ladder neos:** 8 runs in a row clean.

**In the sim:** the test suite builds the same course and plans every section with the real planner. It then flies every jump against the physics sim, 12 runs each, with the aim wobbling. All 14 sections plan, and all 14 land 12/12. Without Tungsten jumps, 11 of the 14 sections have no path. Full table in `scorecard.md`.
