# Recording a level (Flashback style, for rebuilding it 1:1)

Soprano can record what you're doing along with the level round you, so someone else can rebuild the level block for block, test the bot on it, and watch your run played back.

## Recording (in game, Soprano installed)

```
#record start cubecraft_jumps        start recording (the name is optional)
#record mark it fell here            flag this spot and moment (as many as you like)
#record stop                         stop and save
#record                              is it recording, and how much so far
```

- **What it saves:** every block within 4 chunks of you (pick another radius with `#record start <name> <radius>`), 48 blocks above and below your feet. Blocks are saved in full: the way stairs face, which way walls and fences join, slab halves, ladders and so on. Blocks are taken as you come near them, so walk or play through the whole level once.
- **Every tick:** your position, speed, where you're looking, which keys are down, and whether you're on the ground or touching a wall. Also which move Soprano was doing, and the path it planned.
- **The chat.** On a server that moves you between worlds (lobby, game, lobby), each world is kept separately.
- **Where it goes:** `.minecraft/baritone/recordings/<name>.zip`. Click the message `#record stop` gives you to open the folder, then send that zip.

The `#` commands never reach the server, so recording works the same on CubeCraft as in singleplayer. Recording a minute of play takes a few hundred KB.

## Working with a recording (tools/recordings/replay.py)

```
replay.py summary  run.zip                       every jump (from, to, how long in the air, what Soprano was doing), falls, marks, Soprano's complaints
replay.py render   run.zip out/                  the map from above with the run drawn on it, height over time, a 3D close-up of each jump
replay.py render   run.zip out/ --around X Y Z   a 3D view of one spot, with the run through it
replay.py course   run.zip course.json           the level and the run as a course for ParkourLabScorecard: start, marks and end are checkpoints, every jump is a test
replay.py datapack run.zip rebuild.zip           a data pack that rebuilds the level and plays the run back
```

Options: `--world N` picks a world when the recording went through several (by default, the one you spent longest in). `--ticks A-B` keeps only part of the run. `--jumps A-B` picks which jumps get close-ups. `--offset X Y Z` builds the level somewhere else.

## Rebuilding and watching it in game

1. Put `rebuild.zip` in a world's `datapacks` folder. A superflat or void world is best, because the level is rebuilt at its original coordinates.
2. Run `/reload`, then `/function replay:build`. The level goes up part by part, and when it's done you get links to go to the start and to play the run.
3. `/function replay:play`: a mannequin with your skin walks your run, tick by tick. Its name tag shows the recording's name, and the chat and your marks come up when the run gets to them.
   - `/function replay:watch` puts you in spectator, looking through the mannequin's eyes.
   - `/function replay:pause` pauses and resumes.
   - `/function replay:play_at {t:1200}` starts playback from tick 1200.
   - `/function replay:stop` stops playback.

Only the run is played back. Other players, mobs and moving blocks aren't recorded. For a video-quality replay with free cameras, you can run Flashback alongside.
