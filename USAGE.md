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
- `follow player playerName` to follow a player. `follow players` to follow any players in range (combine with Kill Aura for a fun time). `follow entities` to follow any entities. `follow entity pig` to follow entities of a specific type
- `come` to head towards your camera, useful when freecam doesn't move your player position
- `axis` to go to an axis or diagonal axis at y=120 (`axisHeight` is a configurable setting, defaults to 120)
- `surface` or `top` to head towards the closest surface-like area, this can be the surface or highest available air space
- `invert` to invert the current goal and path. This gets as far away from it as possible, instead of as close as possible. For example, do `goal` then `invert` to run as far as possible from where you're standing
- `explore x z` to explore the world from the origin of x,z. Leave out x and z to default to player feet. This will continually path towards the closest chunk to the origin that it's never seen before. `explorefilter filter.json` with optional invert can be used to load in a list of chunks to load
- `elytra` to fly to the current goal using fireworks ([trailer](https://youtu.be/4bGGPo8yiHo), [usage](https://youtu.be/NnSlQi-68eQ))

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

## Maintenance

- `repack` to re-cache the chunks around you
- `reloadall` to reload Soprano's world cache, `saveall` to save it
- `render` to fix glitched chunk rendering without having to reload all of them
- `gc` to call `System.gc()`, which may free up some memory
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
- `allowLadderClutch` (survive long falls by placing a ladder or vine on a wall beside the landing, needs one on the hotbar)
- `pickupLadders` (on by default, picks the clutch ladder back up after landing)
- `experimentalMovement` (speedrunner style movement, see below) and `experimentalMinHealth` (the health it won't drop below)
- `fallDamageCost` (what a half heart of fall damage costs the planner, in ticks, while `experimentalMovement` is on), `experimentalJumpBias` (how much cheaper jumps look than walking, 0.9 by default) and `experimentalBlockPlacementPenalty` (the most a block placement is allowed to cost)
- `allowGroundShortcuts` (take direct lines across bends in flat walking paths, with full-width clearance and floor checks every tick)
- `preferFasterPathing` (smoother steering along clear straight runs and fewer awkward corner-edging diagonals)
- `allowBoats` (place a boat from the inventory, row it across the water, and take it with you on the other side. `boatMinWaterLength` says how much water is worth it, `colorBoatPath` paints those stretches of the path)
- `allowSwimming` (sprint swim along the waterline, head out and body in, instead of bobbing through water)
- `headHitters` (sprint jump head bonks in 1x2 tunnels, slightly faster than plain sprinting)
- `headHittersDiagonal` (on by default, allows diagonal head bonks when `headHitters` is enabled)
- `sprintJumping` (sprint jump along straight path stretches, up single steps and down small hills)
- `sprintJumpingDiagonals` (on by default, allows diagonal sprint jumps when `sprintJumping` is enabled)
- `sprintThroughDescends` (on by default, keeps sprinting off a ledge or step when the path carries on roughly the same way and the cells you coast over after landing are safe: no lava, fire, magma, powder snow, cactus, berry bushes, water or void, and no drop deeper than the planned one)
- `sprintThroughCorners` (on by default, keeps sprinting through turns of 60 degrees or less and starts turning a fraction of a block early so the corner gets cut instead of clipped. Both of these stay off in the nether, near lava, at 6 health or less and next to anything placing or breaking blocks)
- `keepFpsWhileBotting` (on by default, counts the bot working as keyboard and mouse input for the game's AFK frame limiter, so the frame rate does not drop to 30 while you watch it. Does nothing while the bot is idle)
- `shortBaritonePrefix` (use `[S]` instead of `[Soprano]` in chat messages)

`experimentalMovement` is the "just go fast" switch. It turns on all the parkour (neos, climb jumps and momentum jumps too), diagonal ascends and descends, sprint jumping, head hitters, ground shortcuts and faster pathing, takes falls that cost some health when that saves time, cuts corners, smooths straight runs, places blocks more freely and leans toward jumps. If you'd rather pick features one by one, leave it off and use the settings above.

## Settings from Baritone

A few that are worth knowing about:

- `allowBreak`
- `allowSprint`
- `allowPlace`
- `allowParkour`
- `allowParkourPlace`
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
- `hero` to kill all the hostile mobs it can find, `idle` to stand still with its survival chains running, `selfcare` (unfinished upstream)
- `coverwithblocks` and `coverwithsand` to cover nether lava
- `gamer` to beat the game: gear up, nether, stronghold, dragon. It takes a long while and eats, fights and respawns on its own, and it saves its progress per world, so after a relog, a stop or a crash it carries on from the phase it was in. `gamer status` shows the phase and what has been found, `gamer reset` forgets the saved run (not while it is running), and `gamer phase <name>` starts in that phase (`gather`, `iron`, `portal`, `nether`, `eyes`, `return`, `locate`, `room`, `open`, `end_prep` or `dragon`), for testing one part of the run
- `locate_structure <structure>` to find a structure, like `desert_temple` or `stronghold`
- `custom <name>` to run a task list from `CustomTasks.json`
- `status` to see what the running task is doing, `inventory [item]` to list or count, `coords` to get the bot's coordinates, `gamma [value]` to set the brightness
- `altoreload` to re-read its json configs

Everything has `#help` and tab completion like any other command. Commands can be chained with `;` (`get log 16 ; get iron_ingot 3`, each one waits for the one before it) in butler whispers, `custom` lists and the idle and death commands. Typing a `;` line in normal chat doesn't do it.

## AltoClef settings

They're normal Soprano settings that all start with `alto`, so `#set alto<tab>` lists them, `#modified` shows what you changed, `#reset` resets them, and they save in `baritone/settings.txt`. String settings take spaces after the name, so `#set altoIdleCommand follow Jacob` works. Some of the useful ones:

| Setting | Default | What it does |
|---|---|---|
| `altoRunsWhenIdle` | false | keep the survival chains (eating, mob defense, MLG) running even with no task. Off means AltoClef does nothing until you start a task |
| `altoButler` | false | let whitelisted players whisper commands to your bot. Only names in `baritone/altoclef/altoclef_butler_whitelist.txt` can (one per line, not case sensitive), an empty whitelist means nobody, and `altoclef_butler_blacklist.txt` next to it wins over it. Whispers can run AltoClef's commands plus `goto`, `follow`, `stop` and `cancel`, never `punk`, `gamma` or `setgamma` |
| `altoShowTaskChains` | true | the task HUD in the top left. `altoHudScale` (1.0, between 0.5 and 2) resizes it, `altoShowTimer` (false) adds a timer, `altoHudDetailed` (false) swaps the plain words for the developer strings from the log, for bug reports |
| `altoGamerHud` | true | the `#gamer` card on the right of the screen while a run is going: the phase and its clock against the budget, the eleven phase dots, what the bot is doing, the kit it is after with item icons and have/want counts, the furnace batches with time left, the coal detour, hp and food, and a red strip while it is fighting or running. Sized by `altoHudScale`. Hidden with the rest of the HUD (F1) and under F3. With it on the task list in the top left only names the phase |
| `altoMobDefense` | true | fight off or avoid mobs. `altoForceFieldStrategy` (`SMART`, or `OFF`, `FASTEST`, `DELAY`) is how the force field picks its targets |
| `altoSwarmThreshold` | 3 | how many angry mobs within 6 blocks make a crowd, which it runs from instead of fighting on the spot |
| `altoKillOrAvoidAnnoyingHostiles` | true | whether it deals with angry mobs at all. Off, mobs are scenery: it never commits to a fight or a run (fire, falls, a lit creeper, the arrow shield and the force field swinging at what is hitting you stay on). Same meaning as turning off `altoCommitCombat` |
| `altoCommitCombat` | true | the combat brain, in the overworld, the nether and the end: mobs are ignored (the task keeps walking) until one hit you, an angry melee mob is in contact, or you are at 8 hp or less with one within 8. Then it commits: a fight holds until the target is dead (a run at 8 hp, a crowd of 3, or 10 hp against a heavy hitter like a wither skeleton, hoglin or vindicator), a run holds until you are 24 blocks from where it started with nothing hostile within 12 (25 s cap, or over after 5 s of getting nowhere with nothing near). The warden is never a fight, not even boxed in (a heavy hitter boxed in is). A blaze that hits you from out of reach is a run. Ghasts, the dragon, blazes while the rod task works them, and a golem fought from its pillar are left to the tasks that own them. Off, it never commits to anything (the same as `altoKillOrAvoidAnnoyingHostiles` off). `altoHostileEngageRange`, `altoHostileEngageHeight` and `altoPassByGraceTicks` are gone, an old settings file that still has them is skipped without a word |
| `altoIronArbiter` | true | `#gamer` IRON phase only: one chooser picks what the bot is doing (furnace trip, station pickup, loot, coal detour, the climb out, the kit) and whatever started holds until it is done. Off brings back the older fixed order. Changes show up in the log as `activity:` lines |
| `altoAutoEat`, `altoAutoRespawn`, `altoAutoReconnect` | true | what they say |
| `altoIdleCommand` | empty | a `#` command line to run when idle, only used with `altoRunsWhenIdle` |
| `altoDeathCommand` | empty | what to send after respawning. `{deathmessage}` is replaced with the death message, and several can be separated with ` & `. A `#` line runs as a command, a `/` line goes to the server, anything else is chat |
| `altoThrowawayItems`, `altoImportantItems` | | items it can throw away, and items it won't |
| `altoHomeBasePosition` | | the position AltoClef treats as its home base |
| `altoAreasToProtect` | | areas it won't break or build in, as `x1/y1/z1->x2/y2/z2` with an optional `@nether` or `@end` on the end, separated by commas: `-10/0/-10->10/255/10,1000/50/2000->1200/255/2100@nether` |
| `altoLavaPoolPortal` | true | `#gamer` builds its nether portal on a lava row at least four wide with a throwaway 2x4 slab (8 blocks) and lava and water buckets cycled with an empty one, lit with flint and steel or a fire charge, instead of casting every obsidian block separately. No pool or any trouble and it goes back to the cast, so off just means always cast |
| `altoJumpCrits` | true | hop in place for critical hits in melee: with a mob in reach and the weapon a few ticks from full strength it jumps, drops sprint and swings on the way down for 1.5x damage. No hop with a shield up (kiting, standing), eating, in water or lava, on a ladder, under a low ceiling, next to a ledge or lava, while baritone is walking a path, or when a plain hit already kills. The weapon pick is swords and axes by damage per swing, so an axe beats a sword of the same tier, and a tool past 85% worn loses to any healthy one. Off swings the moment the cooldown is full |
| `altoPickupItemsInWater` | false | go after dropped items that are lying in water. Off, it skips them unless it can grab them from dry land or from a puddle one block deep, so it doesn't drown itself chasing a drop in a lake |
| `altoAsyncSmelting`, `altoUseBlastFurnace`, `altoUseNearbyBlastFurnace`, `altoNearbyBlastFurnaceRange` | false, true, true, 48 | smelting. Async loads the furnace and walks away instead of waiting out the cook, which only makes sense when something comes back for it. `#gamer` turns it on by itself for its iron phase, leave it off for a plain `#get`. `altoUseBlastFurnace` is whether it crafts a blast furnace of its own (`#gamer` turns it off for the run so the iron goes into armor). The nearby ones are about using a blast furnace that is already standing within that many blocks, like a village armorer has |
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
