# Parkour Lab v7 (bot v15)

A test course for Soprano's parkour: 26 sections, each aimed at one kind of jump, ending in a finale that strings a bit of every section together. Next to it, in rows to the south, the CubeCraft levels you recorded, rebuilt block for block, each a course of its own.

The Lab is built at **x 1000, y 150, z 1000**, floating in the air, and runs east (+x) for about 820 blocks. Gold pads are checkpoints. Fall below y 140, or into lava, and you're put back on your last one.

## What's new in bot v15 (same course, v7)

- **Chess (harder) all the way**, with your recording of the gap after stop 2 (`chh-gap`): the bot runs off the black wool, clips the corner of the quartz and hops straight on onto the stair, the way you did.
- **A recording of just the hard bit joins onto the level's.** A trail that stops short (yours from stop 2 to stop 4) is followed and then the way goes on along whichever trail crosses it and gets to the finish (the level's own recording, past where you flew). Up to three trails in a row. The ones learned from recordings go first; the bot's own runs only when no learned way gets there.
- **Flying over a gap isn't a way.** Learning a recording, a flight (in the air and not coming down the way anything thrown does) across more than 4 blocks to somewhere new ends the trail there; what's after it is a trail of its own. Flying back up to where you were after a miss is the same as before.
- **Hop chains by way of a block on the way**: the first jump at the bit of that block nearest the landing, the hop off it at the landing (it's how you did the stair). And onto a stair's low step counts as landing on the stair. The plan has to make it with the aim off half a degree and a hair slower in the air than the sim (the game is).
- **Leaps up two along a trail** (onto the stair's low step and up it), and onto the trail's own cells whatever they're on.
- **The quick tries first.** A leap's hop chains (a fraction of a second) for everything waiting, before any leap's full search (seconds each). Your stair hop was found 8 minutes late behind the searches round wherever the bot had wandered.
- **Stepping off to be put back, with nothing to drop off near**: on the edge of a block that puts you back (Dam's glass, the middle of us over the gap), onto the middle of it.
- TrailCheck loads every trail in the routes file, stands the player at the start (the search queue's order is the game's), and takes `TRAIL_ATTEMPTS`.

**How v15 did** (`#goto` the finish from the start):

| | runs | result |
|---|---|---|
| Chess | 1 | done, 161 s, 1 fall |
| Aqua | 1 | done, 40 s, 0 falls |
| Candy | 1 | done, 25 s, 0 falls |
| Hell | 1 | done, 196 s, 2 falls |
| Chess (hard) | 1 | done, 125 s, 1 fall |
| Chess (harder) | 2 | **done once**, 427 s, 3 falls (the gap at 26 s). The other got past the gap at once and to stop 8 in 272 s, then didn't make stop 9 in the 15 minutes (the done one took 4 minutes there too): that's the next one to look at |
| Dam | 2 | checkpoint 6 in 179-194 s. Your recording of the ladder under the fence is in, but the way past the fence (on top of it, back down into the ladder, edging along it) is more than the planner puts together yet |

## What was new in bot v14 (same course, v7; the level data pack's checkpoints moved)

- **Chess (hard) all the way.** The corner jump after stop 4 (a run off the overhang onto the corner of the black wool) is a hop chain now, and then the ladder tops: the bot hops into the one-block ladder over the gap, climbs it, and goes along the tops of the ladders the way you did.
- **Into a ladder next door.** A hop into the ladder next to us with nothing under it, where the trail went (no grid move gets onto a ladder without standing at the foot of it).
- **Off the top of a ladder.** A ladder's top is a plate a sixteenth or three thick along the wall. Jumps from one are planned from where you can really stand on it (half a player from the wall, not inside it), the full search is asked about the ones somebody made, the climb finishes standing on the plate before the jump goes, and the climb up a ladder allows for the way we were already going (a ladder caught moving along the wall slid on out of its column onto the next one). No grid "ascend" off a ladder onto the top of the next one: it aims at the middle of the cell and misses the plate.
- **The jump off the block we're standing on comes first.** The background searches went leaps first, wherever they were along the level; on Chess (hard) the one jump off the checkpoint waited four minutes behind leaps 10 to 30 blocks on. Now: from where we stand, then leaps near us, then the rest by how far.
- **A minute without getting anywhere, on a course that puts you back, and it steps off.** Short paths a few blocks and back kept the "no path" count from ever getting to the step off (Chess (harder): the snow under the tower, above where a fall puts you back).
- **Lab data pack: checkpoints on top of a ladder** put you on the ladder's plate, not in the middle of the cell (you slid down it and out of the bottom). Chess (hard) stop 5, Dam stops 1 and 5.

**How v14 did**:

| | runs | result |
|---|---|---|
| Chess | 1 | done, 121 s, 0 falls |
| Aqua | 1 | done, 40 s, 0 falls |
| Candy | 1 | done, 24 s, 0 falls |
| Hell | 1 | done, 150 s, 1 fall |
| Chess (hard) | 4 | **done all**: 482 s (42 falls), 514 s (39), 450 s (5), 213 s (1), 178 s (1) as the fixes went in |
| Dam | 1 | checkpoint 6 in 238 s (v13: 132 s). Next is still the ladder under the fence, the bit you flew |
| Chess (harder) | 1 | checkpoint 2 in 46 s. After it is the 4 block gap up onto the stair that you flew over in the recording ("too hard"): no jump the sim knows makes it from a 2 block run up, so there's nothing to follow. A recording of someone doing it would be |
| Desert | - | not rerun (no changes there) |

## What was new in bot v13 (same course, v7, plus Chess (hard) and Dam)

- **The course is what your recording stood on.** On a level where everything but the course puts you back (Dam's glass, the wooden pillar on Chess (hard), Desert's sand), the blocks a learned recording went over are the course. The planner won't land anywhere else. A block the recording only touched once or twice, with hundreds more of it all round (the glass under the odd cell of a practice mode recording), doesn't count. Trails from `#routes learn` are marked as learned in `routes.json` (`"learned"`), and those are the ones it goes by.
- **No jump plan that touches down on the wrong thing on the way.** A searched plan that came down on the glass floor and hopped up onto the ladder from there was a reset in the game. Those are thrown out now, along with anything that comes down on a block FallFloor learned. Slime is fine.
- **Slime beside the line.** A bounce off slime a block to the side of the straight line (Dam's pillar to pillar) is a background search aimed over the slime.
- **Hop chains.** A run at a landing that hops again the moment it touches down on the way (Dam's pillar top, four down and seven across) goes further than any jump from standing. Also a run all the way to the edge with a back up first. Tried before the full search for trail leaps.
- **Trail jumps the rule can't do get the full search**, in the background (a hop up onto a wall top from a stair on Chess (hard)).
- **Stepping off to be put back.** When there's no way on at all, on a course that puts you back, the bot walks off the nearest edge and starts again from the checkpoint (up to 3 times a goal), instead of standing there.
- **The way we went before is dropped after it fails 3 times from a spot**, not after it's been asked 3 times. Planning ahead asks a dozen times a jump, and on Chess (harder) the trail was gone after the first two jumps.
- Smaller: leaps coming back towards us no longer jump the queue (the chess stall after stop 1), the slime under a bounce isn't learned as a reset block, the block a put back puts us on is only unlearned on the first landing, a learned floor never goes above the lowest point of the trail, trail jumps count a ladder a block under the cell.

**How v13 did** (`#goto` the finish from the start, 15 minutes each):

| | runs | result |
|---|---|---|
| Chess | 3 | done all, 119–139 s, 0 falls |
| Aqua | 4 | done all, 40–46 s, 0 falls |
| Candy | 4 | done all, 24 s, 0 falls |
| Hell | 4 | done 3 (183 s, 209 s, 434 s); the other stuck at 4 (the drop jump at 1447 37 1162) |
| Endurance | 1 | checkpoint 29 of 43 in the 15 minutes, still going |
| Desert | 2 | checkpoint 3 in 15 s, then the last water block |
| Chess (harder) | 1 | checkpoint 2 in 54 s (v12: 1) |
| Chess (hard) | 3 | checkpoint 4 in 48 s (v12: 3); the corner jump to the black wool after it is next |
| Dam | 2 | checkpoint 6 in 132 s (v12: 3). Next is the ladder under the fence and round the pillar, the one you flew |

## What was new in bot v12 (same course, v7)

- **Trail leaps run.** The executor's check of a path rejected every leap (it has no route guide); a leap's worked out plan is looked up without one now.
- **No pause in the middle of a jump.** Baritone pauses the keys while the next stretch is planned if that plan goes back over where we stand; in a jump's run up (a step back first) that threw the jump off its plan every time, over and over (Chess after stop 7, Hell after 4).
- **A jump that never lands is no jump.** Missed 4 times in the game and never landed: gone round, whatever the sim says (one along a wall on Endurance was tried 13 times).
- **Curved jumps under a block.** A jump on a trail with a block over the straight line at head height gets the aimed variants too, the way the player went round under the side of it (Endurance after stop 27).
- **Out of the side of the water.** Dropping out of a water block to a landing well under it: forward and jump, going up or sinking (before: kept to the column and only leaned on the way up, and sank out of the bottom).
- The routes file is written by hand (the release jar crashed saving it), and the background search results and ladder memo have caps.

**How v12 did** (`#goto` the finish from the start):

| | runs | result |
|---|---|---|
| Chess | 2 | done both, 142 s and 76 s |
| Aqua | 2 | done both, 40–45 s, 0 falls |
| Candy | 2 | done both, 24 s, 0 falls |
| Hell | 3 | done 2 of 3 (93 s and 236 s); the other stuck at 4 |
| Endurance | 1 | checkpoint 36 of 43 (20 minutes) |
| Desert | 1 | checkpoint 3: through the swim, not yet off the last water block onto the post |
| Chess (harder) | 1 | not past 1 |

## What's new in bot v11 (same course, v7)

- **It learns the way from your recordings.** `#routes learn <recording>` reads a `#record` recording from `.minecraft/baritone/recordings` and lays down the way you went as trails, just as if the bot had watched you play. Play a level once with `#record`, learn it, and the next `#goto` follows your way. Add `dx dy dz` to move it (for a map rebuilt somewhere else, like Parkour Lab's levels: see below).
  - Falls you were put back from are cut out. So are misses you flew back up from (landing on or next to where you'd been is a way back there), and anything you flew up to by accident and came back down from. A fall down to a lower part of the course that you then flew back up from keeps the way between.
  - Swimming and climbing are on the trail too (your recording's own blocks say where the water and the ladders were), so a swim up hanging water or a ladder climb isn't a gap.
- **Leaps along the way.** From a block on a trail to one up to 6 cells on along it, further than any single jump the planner asks about, in one go by the full search, in the background. That covers a chain like running off the edge of one block into the next jump with the speed carried.
- **Touchy jumps as a last resort.** A jump that only lands on the very edge, or only with the aim spot on, was left out; now it's taken (at three times the cost) when nothing else gets there. Jumps on a trail that the planner skipped behind a wall (bars, posts in between) get tried too.
- **It treads water.** In water with somewhere to go and no plan yet, it holds jump instead of sinking out of a hanging water block onto the floor under it, and the bob doesn't restart its search every tick.
- **It doesn't give up.** A `#goto` that finds nothing looks again, up to `noPathRetries` times (10) a couple of seconds apart, instead of stopping.
- **Restarting a level, a fall that sends you flying back up** (Endurance): the trail keeps the climb.

**How v11 did** (`#goto` the finish, 10 minutes a go, trails learned from the seven recordings):

| | v10 | v11 |
|---|---|---|
| Chess | done, 145 s (v9) | done, 46 s, 0 falls (78 s with no trails) |
| Aqua | done with a trail, 78 s | done, 38–39 s, 0 falls |
| Candy | done, 30 s | done, 24 s, 0 falls |
| Endurance | not past the start (v9) | checkpoint 18–27 of 43 |
| Desert | checkpoint 3 | checkpoint 3 (in 25 s), through the swim to the last water block |
| Hell | checkpoint 2 | checkpoint 3–4 |
| Chess (harder) | checkpoint 1 | checkpoint 1, some runs |

Still to do: Hell after 4 (a run round a fence post on a one block pillar, a jump to the next and off its far edge with the speed carried, five blocks down: the full search doesn't find the run round the post yet), Desert after the swim (post to post, four and a bit across), the rest of Endurance, and Chess (harder)'s stair (you flew that one, so there's no way to copy) and its fence and wall jumps.

Levels: checkpoints on a fence or wall post put you back on top of the post now (they put you inside it, where nothing can plan), and one recorded standing on the edge of a block (Hell's 4) puts you on the block, not over the lava next to it.

To teach the Lab's levels from the recordings (the levels are moved from where CubeCraft has them):

```
#routes learn chess -4970 0 1166
#routes learn chessharder -4852 0 -811
#routes learn aqua 246 0 1195
#routes learn desert -654 0 1119
#routes learn hell -1554 0 1186
#routes learn endurance -3465 0 1115
#routes learn candy -14344 0 1183
```

(the recordings go in `.minecraft/baritone/recordings`, as .zip or folders). On CubeCraft itself there's nothing to move: `#routes learn <name>` as it is.

## What's new in bot v10 (same course, v7)

- **It remembers the way.** Every block you stand on goes onto a trail, whether the bot's walking or you are, with loops and the bits before a fall left out. The trails are saved with the world's other Soprano data, so they're still there next session. A `#goto` towards somewhere a trail goes past follows it. When it can't see all the way, it goes as far along the trail as it can (not as near the finish as the crow flies, which on a winding level is the wrong way). A trail that never got to the finish still gets it as far as last time, and it carries on from there.
  - So on a level that winds, it learns: the first go (or a go checkpoint by checkpoint, or you playing it once) teaches it, the next `#goto` follows. Aqua: stuck at checkpoint 3 or 4 before; after one go checkpoint by checkpoint, a `#goto` from the start finished in 74 s with 0 falls.
  - A trail that keeps not working from some spot (its next jump fails, or it went into a dead end) stops being followed from there after three tries, for that `#goto`.
  - Restarting a level keeps the trail (a teleport after standing still is a restart; one straight after a fall, or the moment you step on something, is a put back, and the bit after the checkpoint goes).
  - `#routes` says how many trails it has for this world, `#routes forget` wipes them. The setting is `useRouteMemory`.
- **It remembers which jumps keep missing** in the game, and goes round them when there's another way.
- **The jump net.** A small net (NNUE-shaped: a wide sparse first layer over the blocks round a jump, then two small ones) that guesses whether the sim will make a jump. It learns from every jump the sim works out as the bot plays, and keeps what it's learned in `baritone/jumpnet.bin`. With `#set useJumpNet true` the planner skips the sim for jumps the net is sure won't work. Trained on the Lab and four of the levels and tested on Hell, which it had never seen: it skips about a fifth of the hopeless jumps and loses about 1 in 1000 of the good ones. The one to use (trained on the Lab and all seven levels: about a quarter skipped, 0.16% lost on jumps held back from training) comes separately, as it's trained on CubeCraft's maps: copy it to `.minecraft/baritone/jumpnet.bin`. Off by default for now; `learnJumpNet` (on) keeps it learning either way.
- **No more back and forth.** On a course that heads away from the finish first (Aqua's start), the bot could jump out to the far heads and straight back, over and over: from out there, the best part path was back where it came from. A part path doesn't end where the bot has just been (on the way to this goal: where the last go went is the way, this time) any more.
- **Restarting a level isn't a fall.** Starting a level over while standing on some block made it think that block resets you.

**How v10 did** (`#goto` the finish, 9 minutes a go):

| | v9 | v10, first go | v10, with a trail |
|---|---|---|---|
| Aqua | checkpoint 3 or 4 | checkpoint 5 of 8 (the start's heads in 17 s, not stuck) | done, 78 s, 0 falls |
| Hell | checkpoint 2 | | checkpoint 2 (the trail only went that far) |
| Chess (harder) | not past the start | | checkpoint 1 |

Where it helps most: a level that's been done once, by you or by the bot a checkpoint at a time. Where it doesn't yet: Aqua's second half heads away from the finish (east, then back west); the first go heads straight for the finish, under it, and finds the way round only after a lot of falls.

## What's new in v7

### The course
- **Five new sections, before the finale** (now section 26), made of the things the bot found hard on the CubeCraft levels:
  - **21 Decorations:** flower pots and heads on fence posts, pot to pot up and down, a floating head, a head on a wall, a torch and a lantern on posts, a candle.
  - **22 Pillars:** one block pillars over lava with a fence post two high in the middle of each. The strip of top beside the post is all there is to stand on: hop onto it, walk round the post, jump off the far side. Then up onto a head on a post, and onto a lone iron bar.
  - **23 Ladder tops:** single ladders hanging in the air, stood on (the top edge of a ladder holds you), and two caught in mid air and climbed onto.
  - **24 Soul sand:** soul sand slows you to under half speed and sits a little low. Onto it, a slow run up along it and across a gap, soul sand to soul sand, up onto it, off it diagonally and one down.
  - **25 Water blocks:** single water blocks hanging in the air, like the desert level's water bit. There's no jumping out of the top of one (in water the jump key only swims up), so the way on is a block you can drop into, or one two higher that the top of you reaches into as you swim up out of this one. Into a column two high, down into another, up two and up two again, down into one more, and out of its side down onto a block.
- **The CubeCraft levels** (see "The levels" below), built by the same `/function parkourlab:build`.
- **Lava is a fall**, on the Lab and the levels: you get fire resistance, and touching lava puts you back on your checkpoint.
- **No fall damage** (the pack sets the `fall_damage` game rule off when it loads): like on CubeCraft, a fall only ever puts you back. A long fall during a level used to kill the bot outright.
- **Checkpoints count on every level.** A level's checkpoint markers could miss their numbers when the level was built, and then none of that level's checkpoints ever counted (and a fall put you back at a random one). They go by the markers' own tags now.
- **Water and lava stay put**, like on CubeCraft. A water block hanging in the air would run down, and the Hell level's lava falls would spread over the course (they did, in the last world: lava ended up across the first jumps). Starting a level, or the Lab, or teleporting to a checkpoint now clears whatever has run out of the sources.

### The bot
- **Standing on the rim of a pillar.** A block with a fence post on it leaves a strip of its top either side of the post. The planner now knows those strips as places to stand: land on one, walk round the post to another side, jump off. The Hell level is full of them.
- **Off-centre floors.** The top of a ladder is a thin plate along one side of its block; a head on a wall only covers half of it. The bot used to aim at the middle of the block, which is thin air. Now it aims its landings at the bit that's there, takes off from there, and settles there before the next jump.
- **Pots and heads on posts.** The fence post under a flower pot or a head is the real floor, half a block up into the pot's block. The planner looked for the floor in the wrong place and found none, so it never planned jumps off them.
- **Jumps that carry speed through a small floor.** On a pot, a post or a head there's no room to run up, and some of the jumps on the levels only work by jumping again the moment you land, keeping the speed of the jump before. The planner now plans those (only when the jump before comes in the right way), and the bot doesn't stop on the small floor between them.
- **A run up on a post.** Backing off to the far edge of a post or a single block before the jump: a little more speed, a little further.
- **Steps to the block next door.** Up onto the wall next to you, onto bars, onto a ladder's top: the plain moves won't stand on those, and the jumps used to start two blocks out. Now they do next door too, in buckets of their own so they don't crowd out the longer jumps.
- **Heights by the real floor.** A pot on a fence two up is only a block up really, a stair's low step only half a block. The planner judged how far a jump can go by the cells, and threw out jumps it can make.
- **Water blocks hanging in the air.** The desert level has a few, a ladder of them. The bot drops or jumps into one, swims up the middle of it, leans over towards the next one as it comes out of the top, and catches the next one with its head two blocks up, or falls into one lower down. The sim swims the way the game does: slowed by the water, and no jump out of the top (in water the jump key only swims up, which is what makes the desert's water ladder work the way it does).
- **Learning fail blocks you walk onto.** The desert's sand puts you back the moment you step on it, with no fall at all, so the bot never learned it. It does now: put back to somewhere it's already been, straight after standing on a block, means that block (at that height or lower) is off limits round there.
- **Jobs in the background take turns.** Near the start of the hell level there are a lot of hopeless searches (round a wall to somewhere you can't get to). Each takes seconds, and they all sat in front of the one the path needed. Now a landing that keeps coming up with nothing goes to the back of the queue.
- **"Still being worked out" isn't a dead end.** When there's no path to the goal yet, the bot won't set off down to a spot it can't get back up from. A spot whose next jumps were still being worked out in the background used to count as one of those; it doesn't now.
- **Exploring when stuck.** A course that goes the other way first (the hell level's start goes round a lava pit away from the finish) used to leave the bot standing at the start: nothing it could reach was any nearer the goal. Now, stuck in one spot, it searches longer, and then goes somewhere new on the edge of what the search has seen, never back to where it's been. And it only drops below where it started when the search can see a way back up, or when it has been stuck at the same spot a few times.
- **No more frozen planner.** A search could spend minutes on one spot (the rule's round-a-wall jumps from every post on a walled map). The jumps now get at most 8 s of a search.
- **Falls don't end the #goto.** Planning from mid air (falling off a jump, before the server puts you back) found nothing and gave up for good. Now it waits to land and tries again.
- **Candles.** Standing on one is half a block lower than on a pot; the planner thought there was no floor there, and a "move down" into the candle's cell kept the bot stuck.
- **Small landings with a steady aim.** A jump that only lands with the aim within a degree of right (a wall's post, a pot) used to be thrown out; the bot's aim wobbles round the right line rather than sitting off it. Those are planned now, a bit dearer than the rest.

## The levels

`/function parkourlab:levels` lists them; click one, or `/function parkourlab:level/<name>`. `/function parkourlab:marathon` runs the Lab and then every level, one after the other. Each level has its own checkpoints, falls and finish message, and the same rules as on the server: fall to its floor, touch its fail blocks (the desert's sand) or touch lava, and you're back at the last checkpoint.

| Level | Start with | Then |
|---|---|---|
| Chess | `/function parkourlab:level/chess` | `#goto 1009 64 1126` |
| Chess (harder) | `/function parkourlab:level/chessharder` | `#goto 1121 56 1228` |
| Aqua | `/function parkourlab:level/aqua` | `#goto 1230 37 1143` |
| Desert | `/function parkourlab:level/desert` | `#goto 1346 36 1124` |
| Hell | `/function parkourlab:level/hell` | `#goto 1445 39 1135` |
| Endurance | `/function parkourlab:level/endurance` | `#goto 1558 118 1124` |
| Candy | `/function parkourlab:level/candy` | `#goto 1647 34 1212` |

**Bot settings for the levels:** CubeCraft has no fall damage, so let the bot drop further: `#set maxFallHeightNoWater 8`. Leave `learnFallFloor` on: when the server puts you back after a fall, the bot learns how low is too low (and which blocks reset you) and keeps its paths above that.

The levels aren't in this repo: they're CubeCraft's maps. To build them, put your recordings' course files and a levels.json in a folder and run `gen.py out --levels <folder>` (see levels.py).

## What was new in bot v8 (course v6)

- **No more jumping off with no way back up.** When there's no path all the way to the goal yet, the bot sets off on the best part of one. That part could be a drop onto the ground under the course, sure it would find a way back up later. On a parkour map there isn't one. Now a part-path can't end more than 2 blocks below both where it starts and the goal. It waits for the jumps it's still working out, and only goes lower if it's been stuck in the same spot twice and there's nothing left to work out. On the candy level it now stays up on the course from the start. The setting is `partialPathMaxDrop` (`-1` turns it off).
- **Less freezing, faster planning.** Measured on the fence and wall sections in the real game, with a profiler, before and after:
  - GC pauses over 50 ms: 14 → 0 (the longest went from 216 ms to 39 ms). Those pauses were the freezes.
  - Stairs and walls, on a cold start: 64 s → 26 s. No more "slow tick" warnings there (18 before).
  - The background planners make about half the garbage they did. Most of it was simulated players each carrying 3 KB of scratch space, boxed numbers in the rim-walk grids, and failure messages formatted that nobody read.
  - The jump sim looks each block's shape up once per tick, from a flat table, instead of working out a fence's or a wall's shape every time.
  - A quarter of the background searches' time went into one slow square-root function (`Math.hypot`). It's now a plain square root.
  - A* searches with Tungsten jumps: the finale is about 35% faster, and the other sections are about the same.

## What was new in course v6 (bot v7)

- **New section 20, CubeCraft.** The bits of the CubeCraft map the bot found hard, copied block for block from your recording (the stairs face the same way, the walls join the same way). The finale is now section 21.
  - **The wall bit:** off the black wool up onto the strip of quartz outside a ring of diorite walls, round the walls, along the far side and off the end onto a lone stair.
  - **The wall neo:** off the top of a wall, round a wall post, onto a block.
  - **The stair drop:** past a walled block across a 4 gap, onto a lone stair, then off it onto a stair two blocks down.
  - It's laid out so the only way through is the map's way. The first layout let the bot hop from the wall neo straight across onto the finale, then back along the run-in to skip the stair drop, so it got moved. One wall top from the map is left out: from it, the quartz past the neo is in reach.
- **Ledge chains (bot).** When the bot is stuck somewhere, it now also plans chains of short jumps and walks over strips of floor that aren't whole blocks: the rim outside a wall, a stair's half steps. It flies each chain in the sim before trusting it, plays one jump at a time and replans from wherever it actually landed. It only looks for these round spots where it got stuck, so they don't slow pathing down anywhere else.
- **Lone stairs (bot).** The plain parkour moves no longer land long jumps on a lone stair. They overshot and slid off the front.
- **The scorecard warms up a section at a time**, the way the bot meets them in game.

## What was new in bot v6 (same course as v5)

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
- **The finale (now section 21)** replaces the chess level. It's a bit of every section in one run with no checkpoints in between: straights, diagonals, ups and downs, a neo, a 4-blocker, a head hitter, slabs, fences, a ladder, ice, a slime bounce, a triple neo, a neo chain, slime to a ladder, the ice tunnel, and a ladder neo up to the finish.

## Setup (Minecraft 26.3)

**Easiest:** unzip `ParkourLab-world.zip` into your `.minecraft/saves` folder and open the "Parkour Lab" world. The course is already built and you spawn on the start pad. Cheats are on, and it's peaceful.

**Or in your own world:** drop `ParkourLab.zip` into the world's `datapacks` folder and run `/function parkourlab:build`. If you already have an older Parkour Lab world, replace its `datapacks/ParkourLab.zip` with this one, then `/reload` and `/function parkourlab:build`. The build clears the old course first. Don't stand in the course area while it builds: the blocks under you can vanish.

Then:

- `/function parkourlab:start` puts you on the start pad, starts the timer and gives you infinite saturation, so the bot can always sprint.
- `/function parkourlab:cp/<n>` jumps straight to checkpoint n (0 to 25).
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
| 9 | Stairs and walls | `/function parkourlab:cp/8` | `#goto 1346 149 1019` |
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
| 20 | CubeCraft | `/function parkourlab:cp/19` | `#goto 1621 159 992` |
| 21 | Decorations (new) | `/function parkourlab:cp/20` | `#goto 1658 160 992` |
| 22 | Pillars (new) | `/function parkourlab:cp/21` | `#goto 1684 160 992` |
| 23 | Ladder tops (new) | `/function parkourlab:cp/22` | `#goto 1706 162 994` |
| 24 | Soul sand (new) | `/function parkourlab:cp/23` | `#goto 1737 162 994` |
| 25 | Water blocks (new) | `/function parkourlab:cp/24` | `#goto 1753 159 994` |
| 26 | Finale | `/function parkourlab:cp/25` | `#goto 1869 163 992` |

Full run: `/function parkourlab:start`, then `#goto 1869 163 992`.

## What changed in the bot in v5

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

**In the real game, this build** (fresh 26.3 client, `maxFallHeightNoWater 8`, `#goto` the finish and nothing else):

| Course | How far | Time, falls |
|---|---|---|
| Parkour Lab, sections 1 to 10 | all | 0 falls |
| 11 Ladder neos | done | 100 to 150 s, 3 or 4 falls (slower than it was, to look at) |
| 12 to 22 | all | the combo (17) 85 s with 3 falls; the rest quick, 0 falls |
| 23 Ladder tops (new) | done, the hard way | 200 to 800 s, dozens of falls: landing on the top edge of a ladder (a strip 3/16 of a block wide) and stopping there is the hardest thing on the course |
| 24 Soul sand, 25 Water blocks, 26 Finale | all | 9 s, 53 s (2 falls), 54 s (0 falls) |
| Chess | finish | 145 s, 1 fall |
| Candy | finish | 30 s, 1 fall |
| Aqua | checkpoint 3 of 8 (4 of 8 in some runs) | |
| Desert | checkpoint 3 of 8 | it learns the sand, then the drop onto the fence before the water bit |
| Hell | checkpoint 2 of 8 | the start, the first jumps over the lava and back round to stop 1 and 2 all work now |
| Chess (harder), Endurance | not past the start | see below |

The CubeCraft levels that wind a long way round (Hell, Chess harder, Endurance, the back half of Aqua) are where it gets lost: given only the finish, the bot heads for it, and the way there goes off in another direction first. It now explores when it's stuck (somewhere new, on the edge of what its search has seen, never back where it's been), and won't drop into a pit it can't see a way out of, but on these levels that isn't enough yet. Section by section, from checkpoint to checkpoint (the way the scorecard does it), it gets a lot further. A solved-map cache is the proper fix: once it's found the way through a level, it keeps it.

**In the real game, bot v8**: start to finish, all 21 checkpoints, 0 falls, 307 s (stairs and walls and the fence run had already been worked out that session; the rest were cold). The CubeCraft course: 0 falls, 141 s on a cold start. The candy level, start to the end of the recording: 22 s, never off the course.

**In the real game, bot v7** (26.3 client with the default look wobble):
- **Parkour Lab:** start to finish from one `#goto`, all 21 checkpoints, 0 falls, 265 s. Section 20 took 28 s. The bot hopped up onto the rim, walked round the walls, did the neo off the wall top, walked along the rim of the walled wool, went down the stair's halves and dropped onto the low stair, the way you did it on CubeCraft. On a cold start (nothing worked out yet this session), the first full run took 337 s with 0 falls. Most of the extra time was waiting in front of the neo chains (68 s) and the wall bit (about 30 s at the wool while the ledge chain was worked out).
- **The CubeCraft course** (rebuilt 1:1 from your recording): start to the end of the recording from one `#goto`, 0 falls, 109 s. That includes the stair drop at 6001 → 5995, which used to drift off the plan and fall.

**In the sim:** every section plans, and every one lands 12/12 with the aim wobbling. Full table in `scorecard.md`.
