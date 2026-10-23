# Parkour Lab v5

A test course for Soprano's parkour: 20 sections and 204 jumps, each section aimed at one kind of jump, ending in a finale that strings a bit of every section together.

It's built at **x 1000, y 150, z 1000**, floating in the air, and runs east (+x) for about 735 blocks. Gold pads are checkpoints. Fall below y 140 and you're put back on your last one.

## What's new in this build (bot v6, same course)

- **Walking along the edge of a block with a wall or fence on it.** The player is 0.6 wide and only needs a sliver of floor under them, so the strip of block outside a wall post is somewhere to walk. The bot now finds that walk in a few milliseconds: it maps where the player fits and has floor under them, takes the shortest way through that keeps off the edges, and then checks it holds up with the aim wobbling. It walks round walled and fenced platforms and round a pillar's corner instead of jumping round them like a neo. Before, it waited about 25 s for the background search.
- **Walk along the rim, stop, jump.** Where the landing is across a gap past the wall, it walks the rim to a spot with a clear jump, stops, and jumps from there.
- **Stairs and walls: 71 s down to 22 s** in the real game, with 0 falls.
- **Held on ladders.** If the next path isn't ready while the bot is on a ladder, it sneaks, which holds it in place. Before, it let go and slid off the bottom.
- **Less planning-ahead lag at the end of a path.** It retries every 8 ticks instead of every tick.
- **Recording (`#record`)**, Flashback style: see RECORDING.md.

## What was new in v5

- **Proper triple neos (section 15).** Round the end of a wall three blocks thick, onto a block at the same height. You take off from the side block (the block beside the wall's start) after a 7-block run-up along that side. That's how they're done in game: a standing start doesn't have the speed.
- **Stairs and walls (new section 9).** Lone stairs across 3 and 4 gaps, facing every way, with nothing past them to walk on. Also a stair one up, cobblestone walls across 3 gaps and one block up, jumps round the corner of a pillar (a block with a wall on top of it), and 2x2 platforms with a wall or fence two high on every block: the way past is round the rim outside the walls.
- **Bottom slabs** in the odd blocks section and the finale.
- **The finale (section 20)** replaces the chess level. It's a bit of every section in one run with no checkpoints in between: straights, diagonals, ups and downs, a neo, a 4-blocker, a head hitter, slabs, fences, a ladder, ice, a slime bounce, a triple neo, a neo chain, slime to a ladder, the ice tunnel, and a ladder neo up to the finish.

## Setup (Minecraft 26.3)

**Easiest:** unzip `ParkourLab-world.zip` into your `.minecraft/saves` folder and open the "Parkour Lab" world. The course is already built and you spawn on the start pad. Cheats are on, and it's peaceful.

**Or in your own world:** drop `ParkourLab.zip` into the world's `datapacks` folder and run `/function parkourlab:build`. If you already have an older Parkour Lab world, replace its `datapacks/ParkourLab.zip` with this one, then `/reload` and `/function parkourlab:build`. The build clears the old course first. Don't stand in the course area while it builds: the blocks under you can vanish.

Then:

- `/function parkourlab:start` puts you on the start pad, starts the timer and gives you infinite saturation, so the bot can always sprint.
- `/function parkourlab:cp/<n>` jumps straight to checkpoint n (0 to 19).
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
| 7 | Odd blocks | `/function parkourlab:cp/6` | `#goto 1238 147 1015` |
| 8 | Fence run | `/function parkourlab:cp/7` | `#goto 1277 148 1016` |
| 9 | Stairs and walls (new) | `/function parkourlab:cp/8` | `#goto 1346 149 1019` |
| 10 | Ladders | `/function parkourlab:cp/9` | `#goto 1365 153 1019` |
| 11 | Ladder neos | `/function parkourlab:cp/10` | `#goto 1390 155 1019` |
| 12 | Ice palace | `/function parkourlab:cp/11` | `#goto 1427 155 1021` |
| 13 | Sky tower | `/function parkourlab:cp/12` | `#goto 1439 161 1021` |
| 14 | Slime factory | `/function parkourlab:cp/13` | `#goto 1470 157 1021` |
| 15 | Triple neos | `/function parkourlab:cp/14` | `#goto 1503 157 1021` |
| 16 | Neo chains | `/function parkourlab:cp/15` | `#goto 1528 157 1021` |
| 17 | Combo | `/function parkourlab:cp/16` | `#goto 1549 158 1021` |
| 18 | Slime to ladder | `/function parkourlab:cp/17` | `#goto 1576 158 1021` |
| 19 | Ice tunnel | `/function parkourlab:cp/18` | `#goto 1616 158 1021` |
| 20 | Finale (new) | `/function parkourlab:cp/19` | `#goto 1732 162 1019` |

Full run: `/function parkourlab:start`, then `#goto 1732 162 1019`.

## What changed in the bot

- **Triple neos off the side block.** The planner knows the neo that starts from the block beside the wall, not just the one from right behind it.
- **Round the corner of a pillar at any angle.** When a pillar or the end of a wall is right in front of the takeoff, the planner now works out a curve round its corner. It's a background search, like the other hard neos.
- **Round walled platforms.** The "is something in the way" check only looked at the corners of the player's box, so a thin wall or fence post in the middle of the line slipped between them. Now it checks the middle too, and the way round goes along the rim outside the walls.
- **No jumps that bump a wall before taking off.** When the bot brushes a wall, the game decides whether it keeps sprinting by where the camera points, not the way it's moving. That made the sim and the game disagree. Jumps that need a bump are left to the searched paths, which keep clear of walls.
- **Stairs.** Coming down on the low step of a stair used to count as missing the landing, so the bot gave up and walked off. Now it walks up onto the stair and carries on.
- **Bottom slabs** work as takeoffs and landings.
- **Less lag:**
  - The background search uses one thread instead of two, and drops jobs more than 48 blocks from the player.
  - Each search spends less effort before it gives up.
  - The planner no longer tries the jump rules on shapes where they never work.
  - Planning ahead waits 2 seconds after it comes up empty, instead of starting again every tick.
  - "No path found" while planning ahead no longer pops up a notification.

## Results

**In the real game** (a 26.3 client with the default look wobble): start to finish from one `#goto`, 0 falls in all 20 sections, about 4.5 minutes in all on a cold start.

The first time through the neo chains and the finale, the bot waits in front of the searched neos while the background search works them out (up to about a minute on this slow 2-core machine). The answers are kept for the rest of the session.

**In the sim:** every section plans, and every one lands 12/12 with the aim wobbling. Full table in `scorecard.md`.
