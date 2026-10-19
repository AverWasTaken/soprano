Assumes you already have Soprano [installed](README.md#install).

# Prefix

Soprano's chat control prefix is `#` by default.

By default, Soprano commands can also be typed straight into the chat box without a prefix. However, if you make a typo, like typing "gola 10000 10000" instead of "goal", it goes into public chat, which is bad, so using `#` is recommended.

- To disable direct chat control (with no prefix), turn off the `chatControl` setting.
- To disable chat control with the `#` prefix, turn off the `prefixControl` setting.

Be careful that you don't leave yourself with all control methods disabled. If you do, reset your settings by deleting `minecraft/baritone/settings.txt` and relaunching.

# Help

`#help` is your friend (it's clickable! commands have tab completion! oh my!). Try it, I promise it won't just send you back here =)

There's also a [tutorial playlist](https://www.youtube.com/playlist?list=PLnwnJ1qsS7CoQl9Si-RTluuzCo_4Oulpa) and a [showcase video](https://youtu.be/CZkLXWo4Fg4).

# Commands

**All** of these commands may need a prefix before them, as above ^.

## Going places

- `goto x y z` or `goto x z` or `goto y` to go to a certain coordinate (starts going immediately)
- `goto portal` or `goto ender_chest` or `goto block_type` to go to a block
- `goal x y z` or `goal x z` or `goal y`, then `path`, to set a goal and then path to it
- `goal` to set the goal to your player's feet, `goal clear` to clear it
- `thisway 1000` then `path` to go in the direction you're facing for a thousand blocks
- `click` to click your destination on the screen. Right click to path on top of the block, left click to path into it (either at foot level or eye level), and left click and drag to select an area (`#help sel` to see what you can do with that selection)
- `follow player playerName` to follow a player. `follow players` to follow any players in range: it picks the nearest one and sticks with it until that one is gone, then picks again (combine with Kill Aura for a fun time). `follow entities` to follow any entities. `follow entity pig` to follow entities of a specific type
- `come` to head towards your camera, useful when freecam doesn't move your player position
- `axis` to go to an axis or diagonal axis at y=120 (`axisHeight` is a configurable setting, defaults to 120)
- `surface` or `top` to head towards the closest surface-like area, this can be the surface or highest available air space
- `invert` to invert the current goal and path. This gets as far away from it as possible, instead of as close as possible. For example, do `goal` then `invert` to run as far as possible from where you're standing
- `explore x z` to explore the world from the origin of x,z. Leave out x and z to default to player feet. This will continually path towards the closest chunk to the origin that it's never seen before. `explorefilter filter.json` with optional invert can be used to load in a list of chunks to load
- `elytra` to fly to the current goal using fireworks ([trailer](https://youtu.be/4bGGPo8yiHo), [usage](https://youtu.be/NnSlQi-68eQ))
- `gather yes` or `gather no` to answer when Soprano asks to collect blocks for a trip. On a trip you started with `goto x y z`, `come`, `waypoints goto` or a click in the `click` screen, it asks once (with clickable [Yes] and [No] in chat and a popup) when it can't finish the path without blocks you don't have, or when a route that needs blocks or a stone pickaxe is a lot faster. Yes goes and gets about what the path needs through Soprano (food and mob defense can still cut in) and then carries on to the goal. The answer holds for the whole trip, it's never asked twice: arriving, `stop`, a new trip, dying or changing dimension ends the trip. If it runs out again on the same trip after a yes it collects more without asking, up to `gatherMaxRetries` times. `askToGatherForPath` turns it off.

## Waypoints

`wp` for waypoints. A "tag" is like "bed" (created automatically on right clicking a bed, once per bed, unless `doBedWaypoints` is off), "death" (created automatically on death), "home" (saved with `sethome`) or "user" (has to be created manually).
So you might want `#wp save user coolbiome`, then `#wp goal coolbiome` to set the goal, then `#path` to path to it.
For death, `#wp list death` will list waypoints under the "death" tag (remember stuff is clickable!). `#wp goal death` immediately sets the goal if there is only one death waypoint, otherwise it lists them so you can pick one.

`sethome` and `home` are shortcuts for saving and going to your home waypoint.

## Mining, building and farming

- `mine diamond_ore iron_ore` to mine diamond ore or iron ore. An amount of blocks can also be specified, for example, `mine 64 diamond_ore`. Turn on the setting `legitMine` to only mine ores that it can actually see, it will explore randomly around y=11 until it finds them
- `tunnel` to dig a 1x2 tunnel. It will only deviate from the straight line if necessary, such as to avoid lava. For a dumber tunnel that is really just cleararea, you can `tunnel 3 2 100` to clear an area 3 high, 2 wide, and 100 deep
- `build` to build a schematic. `build blah.schematic` will load `schematics/blah.schematic` and build it with the origin being your player feet. `build blah.schematic x y z` to set the origin. Any of those can be relative to your player (`~ 69 ~-420` would build at x=player x, y=69, z=player z-420)
- `litematica` to build the schematic that is currently open in Litematica
- `farm` to automatically harvest, replant, or bone meal crops. Use `farm <range>` or `farm <range> <waypoint>` to limit the max distance from the starting point or a waypoint. Set `farmWaitForGrowth` to `true` to keep farming active while nearby crops are immature
- `pickup` to pick up dropped items, or `pickup <item1> <item2>` for only certain items
- `blacklist` to stop Soprano from going to the closest block, so it won't try to get to it again
- `find` to search through Soprano's cache for the location of a block

## Control

- `cancel` or `stop` to stop everything, `forcecancel` is also an option
- `pause` and `resume` to pause Soprano and pick up where it left off, `paused` to check
- `eta` to get the estimated time until the next segment and the goal. Be aware that the ETA to your goal is really imprecise
- `proc` to view miscellaneous information about the process currently controlling Soprano
- `version` to get the version of Soprano you're running
- `gui` (or `clickgui`) to open the click gui, same as pressing right shift in a world

## Maintenance

- `repack` to re-cache the chunks around you
- `reloadall` to reload Soprano's world cache, `saveall` to save it
- `render` to fix glitched chunk rendering without having to reload all of them
- `gc` to call `System.gc()`, which may free up some memory
- `timings` to print which movements take longer (or shorter) than the planner thinks, from what `recordMovementTimings` has collected. `timings 20` hides groups with fewer than 20 samples (8 by default), `timings clear` deletes the data
- `damn` daniel

# Settings

To toggle a boolean setting, just say its name in chat (for example, saying `allowBreak` toggles whether Soprano will consider breaking blocks). For a numeric setting, say its name then the new value (like `primaryTimeoutMS 250`). It's case insensitive.

- `reset acceptableThrowawayItems` resets one setting to its default value
- `reset all` resets all settings
- `modified` lists all settings that have been changed from their defaults

All the settings and their documentation are [here](src/api/java/baritone/api/Settings.java). The settings Soprano added aren't in the HTML javadocs at baritone.leijurv.com, which only covers upstream Soprano.

There are about 330 settings. Soprano keeps using the `baritone` folder in your Minecraft folder, so your existing `settings.txt` carries over from Baritone.

## Soprano settings

All off by default unless it says otherwise.

- `allowNeos` (jump around the end of a wall 1 or 2 blocks thick, needs `allowParkour`)
- `allowMomentumJumps` (run up and bunny hop into jumps a standing start can't make: a 4 block gap on the flat or one up across a gap of 3, both with 3 or more blocks of run up behind you, drops of 1 to 3 blocks that reach 5 or 6 blocks out, and a few pairs of jumps through a one block pad. Needs `allowParkour`)
- `allowClimbJumps` (jump onto ladders and vines across a gap, and off of them again, needs `allowParkour`)
- `allowLadderClutch` (survive long falls by placing a ladder or vine on a wall beside the landing, needs one on the hotbar. `fastMode` turns it on, and a clutch isn't offered below `fastModeMinHealth` because a miss is the whole fall)
- `pickupLadders` (on by default, picks the clutch ladder back up after landing)
- `selfDefense` (on by default. While a plain job like `#mine`, `#tunnel`, `#build`, `#farm`, `#follow` or `#goto` runs, a hostile mob that hits you up close gets hit back with the best sword or axe on the hotbar until it dies or leaves, then the job carries on. No chasing. Creepers, wardens, withers, creakings, the dragon and flying mobs are ignored, and AltoClef tasks use their own combat instead)
- `allowGodBridge` (bridge backwards at walking speed with no sneaking and no stopping when the only thing to place against is the block you stand on. It turns around, walks back, and clicks the moment the far face of the block underfoot is under the crosshair, so a long bridge is one continuous walk. If it can't click in time it sneaks that tick and finishes the block the old way. The planner prices those blocks at walk speed plus placing, so bridges get picked more readily. It stays off ice, slime, honey, water, ladders, vines and footing that opens when clicked (chests, furnaces, crafting tables), which are bridged the old way or not at all, as before. Side placement is unchanged. Risks: a click that lands but places nothing (a laggy server, a rejected placement) is a fall, stopping the bot mid block can coast it off the edge, strict anticheats and laggy servers won't like it, and the camera faces backwards while it bridges)
- `fastMode` (speedrunner style movement, see below) and `fastModeMinHealth` (the health it won't drop below)
- `fallDamageCost` (what a half heart of fall damage costs the planner, in ticks, while `fastMode` is on), `experimentalJumpBias` (how much cheaper jumps look than walking, 0.9 by default) and `experimentalBlockPlacementPenalty` (the most a block placement is allowed to cost)
- `allowGroundShortcuts` (take direct lines across bends in flat walking paths, with full-width clearance and floor checks every tick)
- `preferFasterPathing` (smoother steering along clear straight runs and fewer awkward corner-edging diagonals)
- `allowBoats` (place a boat from the inventory, row it across the water, and take it with you on the other side. `boatMinWaterLength` says how much water is worth it, and the planner only prices a lake as a boat ride when it has a row that long, `colorBoatPath` paints those stretches of the path. `boatBoardCost` and `boatExitCost` are what getting in and out of the boat cost the planner in ticks, raise them to row round islands instead of hopping out to walk over them)
- `allowSwimming` (sprint swim along the waterline, head out and body in, instead of bobbing through water)
- `headHitters` (sprint jump head bonks in 1x2 tunnels, slightly faster than plain sprinting)
- `headHittersDiagonal` (on by default, allows diagonal head bonks when `headHitters` is enabled)
- `sprintJumping` (sprint jump along straight path stretches, up single steps and down small hills, and over 1 and 2 block parkour gaps when you reach them sprinting and the path goes on straight past the landing)
- `sprintJumpingDiagonals` (on by default, allows diagonal sprint jumps when `sprintJumping` is enabled)
- `sprintThroughDescends` (on by default, keeps sprinting off a ledge or step when the path carries on roughly the same way and the cells you coast over after landing are safe: no lava, fire, magma, powder snow, cactus, berry bushes, water or void, and no drop deeper than the planned one)
- `sprintThroughCorners` (on by default, keeps sprinting through turns of 60 degrees or less and starts turning a fraction of a block early so the corner gets cut instead of clipped. Both of these stay off in the nether, near lava, at 6 health or less and next to anything placing blocks or breaking something slower than an instant break)
- `keepFpsWhileBotting` (on by default, counts the bot working as keyboard and mouse input for the game's AFK frame limiter, so the frame rate does not drop to 30 while you watch it. Does nothing while the bot is idle)
- `guiKey` (344, right shift: the GLFW key code that opens the click gui, which does everything a chat command does with buttons. -1 unbinds it and `#gui` still works. The gui's Settings tab has a key capture control for it. Minecraft 26.3 reads keys through SDL instead of GLFW, but the setting stays in GLFW numbers on every version and the gui converts, so one settings file works on both) and `guiKeyEnabled` (on by default, turns the key off without unbinding it)
- `guiDarkMode` (on by default, uses the dark appearance. The sidebar's Light and Dark buttons save your choice) and `guiAnimations` (on by default, animates transitions and scrolling. Turn off for reduced motion)
- `keepPathOnSameGoal` (on by default, when one AltoClef task hands over to another that wants the same goal, keeps walking the path instead of standing still while a new one is calculated)
- `recordMovementTimings` (off by default, writes how many ticks each movement really took next to how many the planner said, one line per movement, to `baritone/movement-timings.csv` in your Minecraft folder. It's batched and written off the game thread, capped at 4 MiB (then it's renamed to `.old` and starts over, so 8 MiB at most), and it only measures: nothing changes a path or a cost. `#timings` reads it back)
- `sprintTelemetry` (off by default, a debug log for sprint. Whenever sprint turns off while a path is being walked it logs the movement and its neighbours, who was steering, the feet, the collision flags and the blocks the hitbox was leaning on, the forward input, the food, what the executor wanted and a guess at who dropped it, and a line when sprint comes back. Every ascend takeoff gets a line (which rule pressed space, what the sim said, how far the face was). When a path ends or is stopped, and every 1000 path ticks, it prints the drops (the ones at the very end of a path counted apart), how long sprint stayed off by cause and by movement, and it replays every tick on the movement sim and logs where the sim and the game disagree. Lines go to the game log with a `[sprintTelemetry]` prefix, and to chat when `chatDebug` is on. `fastMode` does not turn it on)
- `guardAscends` (on by default, checks the keys of a hop up a block against the movement sim every tick and lets go of W, or of W and space, when they would run into the face of the step. This is for staircases of full blocks, where every landing is a fraction of a block from the next face. At most 16 ticks per movement)
- `brakeAscends` (on by default, when a sprint hop up a block would start too close to the face to clear it (flush against it is the worst case), backs up first: W held while facing away from the step for up to three ticks, then forward again and the jump. Vanilla only ends a sprint when forward is let go of, and W held is forward whatever way you face, so the sprint survives. The plan comes from the movement sim and is only used when it beats jumping on the spot. It turns your facing half a circle away from where you are going for a few ticks, which an anticheat that checks sprint direction can look at funny, so turn it off on servers like that)
- `autoTorch` (`#autoTorch` toggles it, places torches from the hotbar, or the inventory with `allowInventory`, in dark spots around you while mining, walking or an AltoClef task is running. It pauses the path for a moment, looks at the block and clicks, never during a dig, a fight, eating, a fall or a task that places or clicks things itself. A spot gets one when both its block light and its sky light are at or below `autoTorchLightLevel` (7), so the surface and cave mouths are left alone. `autoTorchRadius` (4, never past your reach) is how far around you it looks. It never places in the Nether or the End, while building a schematic, next to water or lava, on blocks that open or do something when clicked (chests, doors, crafting tables...), on sand or gravel hanging over air, on the ore being mined or on blocks the path is about to dig out. A spot the server keeps refusing is left alone for 30 seconds. While it's on, AltoClef doesn't throw torches away as junk)
- `shortBaritonePrefix` (use `[S]` instead of `[Soprano]` in chat messages)
- `askToGatherForPath` (on by default, on trips you start yourself, ask before collecting blocks a path needs, see `gather` above. It only collects the items in `acceptableThrowawayItems` (not stone, sand or gravel), mining stone for cobblestone and grass for dirt. Off, a path that needs blocks you don't have fails like before)
- `fasterPathMinPercent` (30) and `fasterPathMinSeconds` (30) (how much faster a route that needs blocks or a pickaxe has to be before it asks about it, both have to hold, with the collecting time counted against it), `gatherTicksPerBlock` (40) and `gatherPickaxeTicks` (2400) (the guess for that collecting time, in ticks), `gatherMaxBlocks` (128, a path that places more than this is never asked about) and `gatherMaxRetries` (3, how many more times it may go collecting on one trip after a yes)

`fastMode` is the "just go fast" switch. It turns on all the parkour (neos, climb jumps, momentum jumps and placing a block mid jump too), diagonal ascends and descends, sprint jumping, head hitters, ground shortcuts, god bridging, ladder clutches, swimming, boats, elytra takeoff from the ground and faster pathing, takes falls that cost some health when that saves time, cuts corners, smooths straight runs, places blocks more freely and leans toward jumps. If you'd rather pick features one by one, leave it off and use the settings above.

## Settings from Baritone

A few that are worth knowing about:

- `allowBreak`
- `allowSprint`
- `allowPlace`
- `allowParkour`
- `allowParkourPlace` (place the block to land on in mid air during a parkour jump, needs `allowParkour`. If the placement misses after the jump is committed you fall)
- `allowDiagonalAscend`
- `blockPlacementPenalty`
- `acceptableThrowawayItems`
- `blocksToAvoidBreaking`
- `avoidance` (avoidance of mobs / mob spawners)
- `legitMine`
- `mineScanDroppedItems`
- `followRadius`
- `backfill` (fill in tunnels behind you)
- `buildInLayers`
- `buildRepeat` and `buildRepeatCount`
- `worldExploringChunkOffset`
- `renderCachedChunks` (and `cachedChunksOpacity`), fun but you need a beefy computer

# AltoClef

AltoClef is a task bot (survival, crafting, combat, beating the game) that's built in and runs on top of Soprano's pathing. It's inert until you start a task: nothing of it ticks, draws, or changes any Soprano setting until you run one of its commands. When the task ends, or you `stop` or `cancel` it, everything goes back how it was.

## AltoClef commands

- `goto <x y z | x z | y> <overworld|nether|the_end>` to go somewhere in another dimension, walking through portals to get there. `goto the_end` on its own just takes you to that dimension. Coordinates have to be plain numbers here (no `~`), and `end` and `the_nether` work as spellings too. Without a dimension on the end, `goto` is Soprano's own
- `stop`, `cancel` and `forcecancel` also stop AltoClef's task
- `get <item> [count] ...` to get items or resources (`get iron_ingot 3 diamond 2`, `get [iron_pickaxe, stone_sword 1]`). `list` shows what it knows how to get
- `give <player> <item> [count]` to collect something and hand it to a player
- `equip <set | piece ...>` to equip armor, like `equip diamond`
- `deposit [item ...]` to store items in a container, `stash x1 y1 z1 x2 y2 z2 [item ...]` to put them in a chest stash
- `food <amount>` and `meat <amount>` to collect that much food or meat
- `punk <player>` to kill a player
- `hunt` to chase and kill the closest other player, then the next one, until you stop it
- `hero` to kill all the hostile mobs it can find, `idle` to stand still with its survival chains running, `selfcare` (unfinished upstream)
- `coverwithblocks` and `coverwithsand` to cover nether lava
- `gamer` to beat the game: gear up, nether, stronghold, dragon. It takes a long while and eats, fights and respawns on its own, and it saves its progress per world, so after a relog, a stop or a crash it carries on from the phase it was in. `gamer status` shows the phase and what has been found, `gamer reset` forgets the saved run (not while it is running), `gamer trace` writes the last 300 log lines to the log file, and `gamer phase <name>` starts in that phase (`gather`, `iron`, `portal`, `nether`, `eyes`, `return`, `locate`, `room`, `open`, `end_prep` or `dragon`), for testing one part of the run
- `locate_structure <structure>` to find a structure, like `desert_temple` or `stronghold`
- `custom <name>` to run a task list from `CustomTasks.json`
- `status` to see what the running task is doing, `inventory [item]` to list or count, `coords` to get the bot's coordinates, `gamma [value]` to set the brightness
- `altoreload` to re-read its json configs
- `whitelist <player>` to let a player whisper commands to the bot, `whitelist remove <player>` to take them off and `whitelist list` to see who is on it. It edits `altoclef_butler_whitelist.txt` for you (comments stay) and works right away, no `altoreload`. Owner only, a whisper can never run it. See the whisper section below

Everything has `#help` and tab completion like any other command. Commands can be chained with `;` (`get log 16 ; get iron_ingot 3`, each one waits for the one before it) in butler whispers, `custom` lists and the idle and death commands. Typing a `;` line in normal chat doesn't do it.

### Whispering the bot

With `altoButler` on (it is by default) a player on the whitelist can send the bot commands with a normal whisper: `/msg <bot> get cobblestone 4`. The whitelist starts empty, so nobody can until you run `#whitelist <name>`. The bot whispers back a line when it starts a command, one when it finishes (or says it was stopped), and `TASK FAILED: ...` when it can't do it, plus whatever the command itself says, like the answer to `coords`. If a second player's command or one you type yourself takes over the bot, the first player is told theirs was replaced. Players who aren't on the list get no answer at all, unless you turn on `replyToUnauthorized` in `configs/butler.json` (and then at most one answer every half minute per name). `debugWhispers` there prints every whisper the butler sees into your chat, off by default.

Names are not authenticated on an offline-mode server, so anyone who joins as a whitelisted name is that player. Keep the server's own whitelist on.

A whisper can run every AltoClef command except `punk`, `gamma`/`setgamma`/`set_gamma`, `whitelist` and `gamer reset`, plus `goto` (coordinates and a dimension, like `goto 100 64 -200 nether`), `follow`, `stop`, `cancel` and `come`, and nothing else of Soprano's (no `set`, `build`, `mine`...). That includes `hunt` (which goes for the closest other player, whisperer included), `give <player> <item>` (to anyone), and `altoreload`. `custom` task lists run under the same rules as the whisper that asked for them, and a list with a line a whisper may not run is refused whole. `come` is different from the `#come` you type yourself, which goes to the camera: a whispered `come` walks to the player who sent it and finishes when it is within 3 blocks, or gives up after two minutes. If the bot can't see them (outside its render distance) it says so and does nothing. `follow` keeps following them until told to stop, or until they are out of sight and the bot has reached the last spot it saw them.

## AltoClef settings

They're normal Soprano settings that all start with `alto`, so `#set alto<tab>` lists them, `#modified` shows what you changed, `#reset` resets them, and they save in `baritone/settings.txt`. String settings take spaces after the name, so `#set altoIdleCommand follow Jacob` works. Some of the useful ones:

| Setting | Default | What it does |
|---|---|---|
| `altoRunsWhenIdle` | false | keep the survival chains (eating, mob defense, MLG) running even with no task. Off means AltoClef does nothing until you start a task |
| `altoButler` | true | let whitelisted players whisper commands to your bot. Only names in `baritone/altoclef/altoclef_butler_whitelist.txt` can (one per line, not case sensitive, `#whitelist <name>` edits it), an empty whitelist means nobody so it does nothing until you add someone, and `altoclef_butler_blacklist.txt` next to it wins over it (unless `useButlerBlacklist` is off in `butler.json`). Whispers can run AltoClef's commands plus `goto`, `follow`, `stop`, `cancel` and `come` (which comes to the player who whispered), never `punk`, `gamma`, `setgamma`, `set_gamma`, `whitelist` or `gamer reset`. Strangers get no answer unless `replyToUnauthorized` is on in `butler.json` |
| `altoShowTaskChains` | true | the task HUD in the top left. `altoHudScale` (1.0, between 0.5 and 2) resizes it, `altoShowTimer` (false) adds a timer, `altoHudDetailed` (false) swaps the plain words for the developer strings from the log, for bug reports |
| `altoGamerHud` | true | the `#gamer` card on the right of the screen while a run is going: the phase and its clock against the budget, the eleven phase dots, what the bot is doing, the kit it is after with item icons and have/want counts, the furnace batches with time left, the coal detour, hp and food, and a red strip while it is fighting or running. Sized by `altoHudScale`. Hidden with the rest of the HUD (F1) and under F3. With it on the task list in the top left only names the phase |
| `altoMobDefense` | true | fight off or avoid mobs. `altoForceFieldStrategy` (`SMART`, or `OFF`, `FASTEST`, `DELAY`) is how the force field picks its targets |
| `altoKillOrAvoidAnnoyingHostiles` | true | whether it deals with angry mobs at all. Off, mobs are scenery: it never commits to a fight or a run (fire, falls, the arrow shield and the force field swinging at what is hitting you stay on). Same meaning as turning off `altoCommitCombat` |
| `altoCommitCombat` | true | the combat brain, in the overworld, the nether and the end. The task keeps walking past mobs: a crowd around you, a lit creeper, a skeleton shooting from range (walking spoils its aim) are all scenery. It fights a mob that hit you and is in contact, and runs on real danger: 8 hp or less with an angry mob within 8, 10 hp or less with a heavy hitter (wither skeleton, hoglin, vindicator...) that hit you or is in contact (one just standing around is walked past), or the warden or the wither within 8 (or a sonic boom from up to 15). A fight holds until the target is dead or has been more than 6 blocks away and quiet for 3 s (it turns into a run on the danger rules), a run holds until you are 24 blocks from where it started with nothing hostile within 12 (25 s cap, or over after 5 s of getting nowhere with nothing near). Boxed in on a run it fights whatever is on top of it, but never the warden. Ghasts, the dragon, blazes while the rod task works them, and a golem fought from its pillar are left to the tasks that own them. Off, it never commits to anything (the same as `altoKillOrAvoidAnnoyingHostiles` off). `altoHostileEngageRange`, `altoHostileEngageHeight`, `altoPassByGraceTicks` and `altoSwarmThreshold` are gone, an old settings file that still has them is skipped without a word |
| `altoIronArbiter` | true | `#gamer` IRON phase only: one chooser picks what the bot is doing (furnace trip, station pickup, loot, coal detour, the climb out, the kit) and whatever started holds until it is done. Off brings back the older fixed order. Changes show up in the log as `activity:` lines |
| `altoSleepAtNight` | true | `#gamer` only: when it is dark (or thundering) in the overworld, with a bed to use (one standing within 40 blocks with no monster by it, one in the bag, or 3 wool of one colour and 3 planks for one), the bot sleeps through the night instead of working on and fighting mobs. It goes to bed the first moment the game allows it, waiting only for things that cannot be left half done: a fight, 8 health or less, a station coming down, ore going into a furnace, or a container screen up (a smelt batch that is merely cooking or walking to its next load does not hold it). If monsters are near the bed spot and it has a bed of its own to place, it does not fight them: it pillars up out of the game's reach (8 flat, 5 up and down from the bed's head, 16 blocks at a time, and once more if the game still refuses), builds the support, puts the bed on top and sleeps there, then takes the bed it placed back and makes its way down, with falls capped at 3. A standing bed with monsters by it is skipped. Mob defense stands down for that, except for a lit creeper or 8 health or less. A night gets 3 tries or 150 s, with 20 s between tries. It gets the bed ready in the day: in the last 2 minutes before the earliest bedtime it crafts one from 3 same-colour wool and planks (or logs) it already holds, and in the last 4 minutes, with no wool and sheep within 40 blocks, it hunts 3 wool of one colour first (150 s at most per day, then it lets it go). The craft only uses the bag, it never breaks a bed it finds. No bed and no wool: nothing changes. Sleeping moves your respawn point to the night bed, which is then picked up, so a death after that respawns at world spawn. The refusal messages it reads are English only, and a skeleton can still shoot it on the pillar. Overworld only; not during PORTAL, NETHER, END_PREP or DRAGON |
| `altoAutoEat`, `altoAutoRespawn`, `altoAutoReconnect` | true | what they say |
| `altoIdleCommand` | empty | a `#` command line to run when idle, only used with `altoRunsWhenIdle` |
| `altoDeathCommand` | empty | what to send after respawning. `{deathmessage}` is replaced with the death message, and several can be separated with ` & `. A `#` line runs as a command, a `/` line goes to the server, anything else is chat |
| `altoThrowawayItems`, `altoImportantItems` | | items it can throw away, and items it won't |
| `altoFoodKeepUnits` | 40 | how much food (hunger points times count, raw meat at its cooked value) a full bag holds on to. Food is only thrown to make room when the rest still covers this, and only after everything else that can go. `#gamer` raises it to its own food floor while it runs. 0 turns it off |
| `altoHomeBasePosition` | | the position AltoClef treats as its home base |
| `altoAreasToProtect` | | areas it won't break or build in, as `x1/y1/z1->x2/y2/z2` with an optional `@nether` or `@end` on the end, separated by commas: `-10/0/-10->10/255/10,1000/50/2000->1200/255/2100@nether` |
| `altoLavaPoolPortal` | true | `#gamer` builds its nether portal on a lava row at least four wide with a throwaway 2x4 slab (8 blocks) and lava and water buckets cycled with an empty one, lit with flint and steel or a fire charge, instead of casting every obsidian block separately. No pool or any trouble and it goes back to the cast, so off just means always cast |
| `altoJumpCrits` | true | hop in place for critical hits in melee: with a mob in reach and the weapon a few ticks from full strength it jumps, drops sprint and swings on the way down for 1.5x damage. No hop with a shield up (kiting, standing), eating, in water or lava, on a ladder, under a low ceiling, next to a ledge or lava, while baritone is walking a path, or when a plain hit already kills. The weapon pick is swords and axes by damage per swing, so an axe beats a sword of the same tier, and a tool past 85% worn loses to any healthy one. Off swings the moment the cooldown is full |
| `altoPickupItemsInWater` | false | go after dropped items that are lying in water. Off, it skips them unless it can grab them from dry land or from a puddle one block deep, so it doesn't drown itself chasing a drop in a lake |
| `altoAsyncSmelting`, `altoUseBlastFurnace`, `altoUseNearbyBlastFurnace`, `altoNearbyBlastFurnaceRange` | false, true, true, 48 | smelting. Async loads the furnace and walks away instead of waiting out the cook, which only makes sense when something comes back for it. In the iron phase `#gamer` stands at a smoker it just loaded when the batch is 8 items or fewer (40 s, plus 10 s of slack), and with a bigger batch it goes back to work and stands for the last 20 s if it is within 32 blocks of the smoker by then. Furnaces never get this stand, and neither does the gather phase. `#gamer` turns it on by itself for its iron phase, leave it off for a plain `#get`. `altoUseBlastFurnace` is whether it crafts a blast furnace of its own (`#gamer` turns it off for the run so the iron goes into armor). The nearby ones are about using a blast furnace that is already standing within that many blocks, like a village armorer has |
| `altoMinimumFoodAllowed`, `altoFoodUnitsToCollect` | 0, 0 | keep at least this much food on you and collect this much when it runs short. 0 means it never goes looking for food on its own |
| `altoResourceMineRange`, `altoEntityReachRange`, `altoContainerItemMoveDelay` | | how far away a normally crafted block (a crafting table) may be mined instead of made, how far it reaches for entities, and the delay between container clicks |

AltoClef uses Soprano's own `replantCrops` setting instead of having one of its own.

The rest of its config (`beat_minecraft.json`, the food chain, block tracker and MLG configs, `butler.json`, `CustomTasks.json`) is still json under `baritone/altoclef/configs/`, and `altoreload` re-reads it. Settings aren't in there any more, they live in `#set`. If you have an old `baritone/altoclef/altoclef_settings.json`, its values are imported once on first start (only into settings you have not changed yourself) and the file is renamed to `altoclef_settings.json.migrated`. A file that is not valid json is not imported and gets renamed to `altoclef_settings.json.failed`.

`#gamer` keeps its run in `<world>/altoclef/gamer.json`, which is why a relog or a crash carries on where it was, and `#gamer reset` deletes it. `configs/beat_minecraft.json` is versioned: a file from an older version keeps your numbers but gets the new default kit lists (the old one is kept as `beat_minecraft.json.bak`), and anything older than that is replaced by the defaults.

# Troubleshooting / common issues

## Why doesn't Soprano respond to any of my chat commands?

This could be one of many things.

First, make sure it's actually installed. An easy way to check is seeing if it created the folder `baritone` in your Minecraft folder.

Second, make sure that you're using the prefix properly, and that chat control is enabled in the way you expect.

For example, some clients turn off direct chat control (i.e. anything typed in chat without a prefix will be ignored and sent publicly). **This is a saved setting**, so if you run one of those once, `chatControl` will be off from then on, **even in other clients**.
So you'll need to use the `#` prefix, or edit `baritone/settings.txt` in your Minecraft folder to undo that (specifically, remove the line `chatControl false`, then restart your client).

## Why can I do `.goto x z` in some client but nowhere else?

That's a custom command the client added, it isn't from Soprano.
The equivalent you're looking for is `goto x z`.
