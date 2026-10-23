# Parkour Lab v5

A test course for Soprano's parkour: 20 sections and 200 jumps, each section aimed at one kind of jump, ending in a finale that strings a bit of every section together.

It's built at **x 1000, y 150, z 1000**, floating in the air, and runs east (+x) for about 725 blocks. Gold pads are checkpoints. Fall below y 140 and you're put back on your last one.

## What's new in v5

- **Proper triple neos (section 15).** Round the end of a wall three blocks thick, onto a block at the same height. You take off from the side block (the block beside the wall's start) after a 7-block run-up along that side. That's how they're done in game: a standing start doesn't have the speed.
- **Stairs and walls (new section 9).** Lone stairs across 3 and 4 gaps, facing every way, with nothing past them to walk on. Also a stair one up, cobblestone walls across 3 gaps and one block up, and jumps round the corner of a pillar (a block with a wall on top of it).
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
| 9 | Stairs and walls (new) | `/function parkourlab:cp/8` | `#goto 1336 149 1016` |
| 10 | Ladders | `/function parkourlab:cp/9` | `#goto 1355 153 1016` |
| 11 | Ladder neos | `/function parkourlab:cp/10` | `#goto 1380 155 1016` |
| 12 | Ice palace | `/function parkourlab:cp/11` | `#goto 1417 155 1018` |
| 13 | Sky tower | `/function parkourlab:cp/12` | `#goto 1429 161 1018` |
| 14 | Slime factory | `/function parkourlab:cp/13` | `#goto 1460 157 1018` |
| 15 | Triple neos | `/function parkourlab:cp/14` | `#goto 1493 157 1018` |
| 16 | Neo chains | `/function parkourlab:cp/15` | `#goto 1518 157 1018` |
| 17 | Combo | `/function parkourlab:cp/16` | `#goto 1539 158 1018` |
| 18 | Slime to ladder | `/function parkourlab:cp/17` | `#goto 1566 158 1018` |
| 19 | Ice tunnel | `/function parkourlab:cp/18` | `#goto 1606 158 1018` |
| 20 | Finale (new) | `/function parkourlab:cp/19` | `#goto 1722 162 1016` |

Full run: `/function parkourlab:start`, then `#goto 1722 162 1016`.

## What changed in the bot

- **Triple neos off the side block.** The planner knows the neo that starts from the block beside the wall, not just the one from right behind it.
- **Round the corner of a pillar at any angle.** When a pillar or the end of a wall is right in front of the takeoff, the planner now works out a curve round its corner. It's a background search, like the other hard neos.
- **Stairs.** Coming down on the low step of a stair used to count as missing the landing, so the bot gave up and walked off. Now it walks up onto the stair and carries on.
- **Bottom slabs** work as takeoffs and landings.
- **Less lag:**
  - The background search uses one thread instead of two, and drops jobs more than 48 blocks from the player.
  - Each search spends less effort before it gives up.
  - The planner no longer tries the jump rules on shapes where they never work.
  - Planning ahead waits 2 seconds after it comes up empty, instead of starting again every tick.
  - "No path found" while planning ahead no longer pops up a notification.

## Results

**In the real game** (a 26.3 client with the default look wobble): start to finish from one `#goto`, 0 falls in all 20 sections.

The first time through the neo chains and the finale, the bot waits in front of the searched neos while the background search works them out (both took about 45 to 50 s on this slow 2-core machine). The answers are kept for the rest of the session.

**In the sim:** every section plans, and every one lands 12/12 with the aim wobbling. Full table in `scorecard.md`.
