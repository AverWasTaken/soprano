# Parkour Lab v4

A test course for Soprano's parkour: 19 sections and 157 jumps, each section aimed at one kind of jump, ending in the chess level. v4 adds five sections (14 to 18) between the slime factory and the chess level: triple neos, neo chains, a combo run, slime to ladder, and an ice tunnel.

It's built at **x 1000, y 150, z 1000**, floating in the air, and runs east (+x) for about 550 blocks. Gold pads are checkpoints. Fall below y 140 and you're put back on your last one.

## Setup (Minecraft 26.3)

**Easiest:** unzip `ParkourLab-world.zip` into your `.minecraft/saves` folder and open the "Parkour Lab" world. The course is already built and you spawn on the start pad. Cheats are on, and it's peaceful.

**Or in your own world:** drop `ParkourLab.zip` into the world's `datapacks` folder and run `/function parkourlab:build`. If you already have the v3 world, replace its `datapacks/ParkourLab.zip` with this one, then `/reload` and `/function parkourlab:build`. The build clears the old course first, so the v3 chess level (which sat where the new sections are now) goes away.

Then:

- `/function parkourlab:start` puts you on the start pad, starts the timer and gives you infinite saturation, so the bot can always sprint.
- `/function parkourlab:cp/<n>` jumps straight to checkpoint n (0 to 18).
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
| 14 | Thick neos (new) | `/function parkourlab:cp/13` | `#goto 1390 153 1018` |
| 15 | Neo chains (new) | `/function parkourlab:cp/14` | `#goto 1415 153 1018` |
| 16 | Combo (new) | `/function parkourlab:cp/15` | `#goto 1436 154 1018` |
| 17 | Slime to ladder (new) | `/function parkourlab:cp/16` | `#goto 1463 154 1018` |
| 18 | Ice tunnel (new) | `/function parkourlab:cp/17` | `#goto 1503 154 1018` |
| 19 | Chess | `/function parkourlab:cp/18` | `#goto 1545 162 1024` |

Full run: `/function parkourlab:start`, then `#goto 1545 162 1024`.

## What the sections test

1. **Warm-up.** Straight 1, 2 and 3 gaps, one up, a drop.
2. **Diagonals.** 2x2, 3x2, 3x3, 2x3, 4x1 and 1x4 jumps on the level.
3. **Ups and downs.** Diagonal jumps one up, and one, two and three down across gaps.
4. **Neos.** Round the end of a 1-thick wall, then a 2-thick one.
5. **Four blockers.** 4-block gaps: straight and 4+1 with a runway behind, 4 gap one down from a single block, 3+1 up, 4+2 one down.
6. **Head hitters.** Ceilings over the jump: 1, 2, 3 and 4 gaps, a 3+1, a 4+1, a diagonal, and a 5 gap one down.
7. **Odd blocks.** Landings on a fence, wall, pane, iron bars, top slab, stairs, hopper, top trapdoor and end rod.
8. **Fence run.** Fence to fence, wall to wall, pane to pane, with gaps and diagonals between.
9. **Ladders.** Jump onto a ladder across a gap and climb it, then the same with a vine.
10. **Ladder neos.** Round the end of a wall onto a ladder on its far side, climb onto the wall top and walk along it. Then the same with a vine.
11. **Ice palace.** Takeoffs off packed, blue and normal ice, with gaps up to 4+2 and 4+3.
12. **Sky tower.** A spiral of diagonal one-up jumps round a pillar.
13. **Slime factory.** Drop onto slime five blocks down and bounce across to ledges no normal jump reaches.
14. **Thick neos (new).** Triple neos: round the end of a wall three blocks thick, onto a block one down. There's no air time for it on the level, so the landing is one down. Then the same with the wall's end on the other side.
15. **Neo chains (new).** Three 1-thick neos in a row with 1-block pads between, each off the pad the last one landed on, with that wall right behind it. Then two double neos back to back with a 1-block pad between.
16. **Combo (new).** A 3-gap head hitter, straight into a neo, straight into a ladder neo up onto a wall, along the top and off the end.
17. **Slime to ladder (new).** Off a ledge, down onto slime, and bounce up onto a ladder on a tower. Climb over the top, then the same onto a vine.
18. **Ice tunnel (new).** Hop along a packed ice tunnel under a ceiling at head height and out into a 5 gap. Then a 6 gap off a longer tunnel. These are the longest jumps on the course.
19. **Chess.** Pawn captures, a knight's tour, the bishop's diagonal, the rook's battlements, a neo round the queen, and the king's ladder to "Checkmate".

## What changed in the bot

- **Momentum between jumps.** A jump used to brake to a standstill on every landing before the next one started. Now, if the next jump (or whatever comes next) can go from the speed it landed with, it just carries on. On the whole course, the time spent braking after Tungsten jumps went from about 9 seconds to under 2.
- **Neos the old rules couldn't do.** Triple neos, neos off a 1-block pad with no run-up, and two neos in one flight are worked out by the full physics search in the background. The planner hears "not yet" while that runs, then picks them up on its next try. A flight only counts once it has landed 16 times in a row in the sim with the aim wobbling.
- **Ice hops.** On ice the bot now hops along the runway, and under a ceiling every hop keeps its push. That's what reaches the 5 and 6 gaps in the ice tunnel. The planner now looks further on long ice runways.
- **Slime to ladder.** The planner now considers bouncing off slime to reach a ladder, which can be a block higher than where you started.

## Results

**In the real game** (a 26.3 client with the default look wobble):

- **Whole course:** start to Checkmate from one `#goto`, 0 falls in all 19 sections.
- Sections 1 to 13 plus chess took about 114 s, against about 120 s for the same sections in v3. That's the momentum change.
- **The first time through** a new neo section, the bot stops in front of it for a while (up to about a minute on a slow 2-core machine). That's the background search working out the neos. After that, the answers are kept for the rest of the session. The neo chains section took 52 s the first time and 14 s after that.

**In the sim:** the test suite builds the same course and plans every section with the real planner. It then flies every jump against the physics sim, 12 runs each, with the aim wobbling. All 19 sections plan, and all 19 land 12/12. Without Tungsten jumps, 16 of the 19 have no path. Full table in `scorecard.md`.

## Known gaps

- The jump onto a top trapdoor (section 7) still has no path when tried on its own, same as v3. The bot takes a different route in that section.
- Planning the whole course in a single search (start to Checkmate) now runs out of time partway, because the course is longer. In the game that doesn't matter: the bot plans in segments as it goes.
- Two double neos in one flight (no stop on the pad) are planned only when there's no way with a stop. In this course the second double neo also works from a standstill on the pad, so the bot takes it in two.
