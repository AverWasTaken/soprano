#!/usr/bin/env python3
"""
Parkour Lab: a test course for Soprano's parkour (and anyone's), built by a data pack for Minecraft 26.3.

One source of truth: the sections below are written as jumps (dx, dy, dz from the block you're on to the next), the
script turns them into blocks, and from the blocks into
  - the data pack (functions that build it, a timer, checkpoints, fall reset),
  - course.json (every block, every checkpoint, every jump) for the planner tests to load,
  - TESTING.md (where everything is and the commands to test each part).

Coordinates: feet y, the way Baritone counts them. A block "at" a landing is the one under the feet.
"""
import json
import math
import os
import shutil
import sys
import zipfile

PACK = "parkourlab"
DATA_FORMAT = 121           # 26.3's data pack format (version.json in the 26.3 jar: data_major 121)
OX, BASE, OZ = 1000, 150, 1000
FALL_Y = BASE - 10          # below this you've fallen, back to the last checkpoint


class Course:
    def __init__(self):
        self.blocks = {}       # (x, y, z) -> block state string
        self.checkpoints = []  # dicts: id, x, y, z (feet, middle of the pad), name
        self.sections = []     # dicts: id, name, start cp, end cp, jumps, notes
        self.labels = []       # (x, y, z, text)
        self.cur = None        # feet cell we're standing on
        self.jumps = []

    def put(self, x, y, z, state, overwrite=True):
        if overwrite or (x, y, z) not in self.blocks:
            self.blocks[(x, y, z)] = state

    def pad(self, cx, y, cz, name, block="gold_block"):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                self.put(cx + dx, y - 1, cz + dz, block)
        cp = {"id": len(self.checkpoints), "x": cx, "y": y, "z": cz, "name": name}
        self.checkpoints.append(cp)
        self.labels.append((cx + 0.5, y + 2.2, cz + 0.5, name))
        return cp

    def start_section(self, name, notes, expect):
        cp = self.checkpoints[-1]
        self.cur = (cp["x"] + 1, cp["y"], cp["z"])  # the east edge of the pad is where the first jump leaves from
        self.jumps = []
        self.sections.append({"id": len(self.sections) + 1, "name": name, "from": cp["id"], "notes": notes,
                              "expect": expect, "jumps": self.jumps})

    def jump(self, dx, dy, dz, block="quartz_block", kind="block"):
        x, y, z = self.cur
        nx, ny, nz = x + dx, y + dy, z + dz
        self.put(nx, ny - 1, nz, block)
        self.jumps.append({"from": [x, y, z], "to": [nx, ny, nz], "kind": kind, "block": block})
        self.cur = (nx, ny, nz)
        return self.cur

    def walk(self, n, block="quartz_block"):
        # n more blocks of floor straight on in +x (a runway), and we stand at the end of it
        x, y, z = self.cur
        for i in range(1, n + 1):
            self.put(x + i, y - 1, z, block)
        self.cur = (x + n, y, z)

    def end_section(self, dx, dy, dz, name):
        # the last jump lands on the west edge of the next pad
        x, y, z = self.cur
        lx, ly, lz = x + dx, y + dy, z + dz
        self.jumps.append({"from": [x, y, z], "to": [lx, ly, lz], "kind": "block", "block": "gold_block"})
        cp = self.pad(lx + 1, ly, lz, name)
        self.sections[-1]["to"] = cp["id"]
        return cp


def build():
    c = Course()
    c.pad(OX, BASE, OZ, "Start", block="emerald_block")

    # 1. warm up: what any parkour move set does. straight gaps of 1, 2, 3, one up, and a 3 gap from a single block
    c.start_section("Warm-up", "Straight jumps: 1, 2 and 3 block gaps, one up a block. Plain Baritone parkour.",
                    "PARKOUR")
    c.jump(2, 0, 0)
    c.jump(3, 0, 0)
    c.jump(4, 0, 0)
    c.jump(3, 1, 0)
    c.jump(4, 0, 0)
    c.jump(2, -1, 0)
    c.end_section(3, 0, 0, "1: Warm-up")

    # 2. diagonals: none of these is on a straight line, the grid moves can't do one of them
    c.start_section("Diagonals", "Diagonal and knight's move jumps: 2x2, 3x2, 3x3, 2x3, 4x1, on the level.",
                    "TUNGSTEN")
    c.jump(2, 0, 2, "cyan_terracotta")
    c.jump(3, 0, 2, "cyan_terracotta")
    c.jump(3, 0, -3, "cyan_terracotta")
    c.jump(2, 0, -3, "cyan_terracotta")
    c.jump(4, 0, 1, "cyan_terracotta")
    c.jump(3, 0, 3, "cyan_terracotta")
    c.jump(1, 0, -4, "cyan_terracotta")
    c.end_section(3, 0, 2, "2: Diagonals")

    # 3. ups and downs at angles. the two down drops have no jump in them (a jump's peak makes the fall hurt)
    c.start_section("Ups and downs", "Diagonal jumps one up, one down, two down and three down across gaps.",
                    "TUNGSTEN")
    c.jump(2, 1, 2, "lime_terracotta")
    c.jump(2, 1, -2, "lime_terracotta")
    c.jump(3, 1, 1, "lime_terracotta")
    c.jump(3, -1, 3, "lime_terracotta")
    c.jump(2, -2, -2, "lime_terracotta")
    c.jump(4, -1, 3, "lime_terracotta")
    c.jump(2, -3, 2, "lime_terracotta")
    c.jump(2, 1, 2, "lime_terracotta")
    c.end_section(3, 1, -2, "3: Ups and downs")

    # 4. neos: round the end of a wall you can't jump over
    c.start_section("Neos", "Round the end of a wall: one thick landing two out, then two thick landing three out.",
                    "NEO / TUNGSTEN")
    x, y, z = c.cur
    c.walk(2, "polished_andesite")
    x, y, z = c.cur
    c.put(x, y - 1, z - 1, "polished_andesite")              # takeoff is two wide, the wall's end is on our +z side
    for wz in range(z - 3, z + 1):
        for wy in (y, y + 1, y + 2):
            c.put(x + 1, wy, wz, "stone_bricks")
    c.jump(2, 0, 0, "polished_andesite", kind="neo")
    c.walk(2, "polished_andesite")
    x, y, z = c.cur
    c.put(x, y - 1, z - 1, "polished_andesite")
    for wz in range(z - 3, z + 1):
        for wy in (y, y + 1, y + 2):
            c.put(x + 1, wy, wz, "stone_bricks")
            c.put(x + 2, wy, wz, "stone_bricks")
    c.jump(3, 0, 0, "polished_andesite", kind="neo")
    c.end_section(3, 0, 0, "4: Neos")

    # 5. four blockers: the longest jumps there are on foot. a runway behind the takeoff for the ones that need a run up
    c.start_section("Four blockers", "Four block gaps: straight and 4+1 with a run up, a 4 down one from a standing start, "
                    "a 3+1 up, a 4+2 down one and a 3x3 down one.", "TUNGSTEN (run ups)")
    c.jump(2, 0, 0, "orange_terracotta")
    c.walk(3, "orange_terracotta")
    c.jump(5, 0, 0, "orange_terracotta", kind="4b")
    c.walk(3, "orange_terracotta")
    c.jump(5, 0, 1, "orange_terracotta", kind="4+1")
    c.jump(5, -1, 0, "orange_terracotta", kind="4b down")
    c.jump(4, 1, 1, "orange_terracotta", kind="3+1 up")
    c.walk(2, "orange_terracotta")
    c.jump(5, -1, 2, "orange_terracotta", kind="4+2 down")
    c.jump(4, 1, -1, "orange_terracotta", kind="3+1 up")
    c.walk(3, "orange_terracotta")
    c.jump(5, 0, -1, "orange_terracotta", kind="4+1")
    c.end_section(3, 0, 0, "5: Four blockers")

    # 6. head hitters: ceilings over the jump
    c.start_section("Head hitters", "Jumps under ceilings: a 1 gap under a ceiling at head height, then 2 and 3 gaps, a "
                    "3+1 and a diagonal under one a block higher.", "PARKOUR / TUNGSTEN")
    x, y, z = c.cur
    for cx in range(x, x + 2):
        c.put(cx, y + 2, z, "dark_prismarine")
    c.jump(2, 0, 0, "prismarine_bricks", kind="headhitter")
    x, y, z = c.cur
    for cx in range(x, x + 4):
        c.put(cx, y + 3, z, "dark_prismarine")
    c.jump(3, 0, 0, "prismarine_bricks", kind="headhitter")
    x, y, z = c.cur
    for cx in range(x, x + 5):
        c.put(cx, y + 3, z, "dark_prismarine")
    c.jump(4, 0, 0, "prismarine_bricks", kind="headhitter 3b")
    x, y, z = c.cur
    for cx in range(x, x + 5):
        for cz in (z, z + 1):
            c.put(cx, y + 3, cz, "dark_prismarine")
    c.jump(4, 0, 1, "prismarine_bricks", kind="headhitter 3+1")
    x, y, z = c.cur
    for i in range(0, 4):
        for j in (0, 1):
            c.put(x + i, y + 3, z + i - j, "dark_prismarine")
    c.jump(3, 0, 3, "prismarine_bricks", kind="headhitter")
    # the four block gap under a ceiling: a runway with the ceiling right over the head (hop along it, every hop bonking,
    # which is how the speed builds), on over the takeoff, and three up over the gap itself
    def hh_runway(n, over_gap, gap_ceiling, dz=0):
        x, y, z = c.cur
        c.jump(2, 0, 0, "prismarine_bricks")
        x, y, z = c.cur
        for i in range(1, n + 1):
            c.put(x + i, y - 1, z, "prismarine_bricks")
        for cx in range(x - 1, x + n + 1):
            for cz in (z - 1, z, z + 1):
                c.put(cx, y + 2, cz, "dark_prismarine")
        if gap_ceiling:
            for cx in range(x + n + 1, x + n + over_gap + 2):
                for cz in range(z - 1, z + 2 + max(dz, 0)):
                    c.put(cx, y + 3, cz, "dark_prismarine")
        c.cur = (x + n, y, z)
    hh_runway(4, 5, True)
    c.jump(5, 0, 0, "prismarine_bricks", kind="headhitter 4b")
    hh_runway(4, 5, True, dz=1)
    c.jump(5, 0, 1, "prismarine_bricks", kind="headhitter 4+1")
    hh_runway(4, 6, False)
    c.jump(6, -1, 0, "prismarine_bricks", kind="headhitter 5b down")
    c.end_section(3, 0, 0, "6: Head hitters")

    # 7. odd blocks: things that aren't a full block to land on
    c.start_section("Odd blocks", "Landings on a fence, a cobblestone wall, a glass pane, iron bars, a top slab, stairs, "
                    "a hopper, a top trapdoor, an end rod, and bottom slabs.", "TUNGSTEN")
    c.jump(2, 0, 0, "oak_fence", kind="odd")
    c.jump(2, 0, 1, "oak_fence", kind="odd")
    c.jump(2, 0, -1, "cobblestone_wall", kind="odd")
    c.jump(2, 0, 0, "glass_pane", kind="odd")
    c.jump(2, 0, 1, "iron_bars", kind="odd")
    c.jump(2, 0, 0, "oak_slab[type=top]", kind="odd")
    c.jump(2, 0, -1, "stone_brick_stairs[facing=east]", kind="odd")
    c.jump(2, 0, 0, "hopper", kind="odd")
    c.jump(2, 0, 1, "oak_trapdoor[half=top]", kind="odd")
    c.jump(2, 0, 0, "end_rod[facing=up]", kind="odd")
    # lone stairs, every way round: land on one with nothing past it, and jump on off it
    c.jump(2, 0, 0, "oak_stairs[facing=west]", kind="stair, low side first")
    c.jump(2, 0, 1, "oak_stairs[facing=north]", kind="stair, side on")
    c.jump(3, 0, 0, "oak_stairs[facing=south]", kind="stair, side on")
    c.jump(2, 1, -1, "oak_stairs[facing=east]", kind="stair, one up")
    c.jump(3, -1, 0, "quartz_block", kind="off a stair")
    # bottom slabs: half a block down, slab to slab and back up onto a full block
    c.jump(2, 0, 0, "smooth_stone_slab", kind="slab")
    c.jump(3, 0, 1, "smooth_stone_slab", kind="slab to slab")
    c.jump(3, 0, -1, "quartz_slab", kind="slab to slab")
    c.jump(3, 0, 0, "quartz_block", kind="slab to block")
    c.end_section(2, 0, 0, "7: Odd blocks")

    # 8. fence and pane run: a whole line of them, gaps and diagonals between, nothing full to stand on until the end
    c.start_section("Fence run", "Fence to fence, wall to wall and pane to pane, with gaps and diagonals between them.",
                    "TUNGSTEN")
    c.jump(2, 0, 0, "dark_oak_fence", kind="fence")
    c.jump(2, 0, 1, "dark_oak_fence", kind="fence")
    c.jump(3, 0, 0, "dark_oak_fence", kind="fence")
    c.jump(2, 0, -2, "dark_oak_fence", kind="fence")
    c.jump(2, 0, 0, "mossy_cobblestone_wall", kind="wall")
    c.jump(3, 0, 1, "mossy_cobblestone_wall", kind="wall")
    c.jump(2, 0, 0, "white_stained_glass_pane", kind="pane")
    c.jump(2, 0, -1, "white_stained_glass_pane", kind="pane")
    c.jump(3, 0, 0, "white_stained_glass_pane", kind="pane")
    c.jump(2, 0, 2, "iron_bars", kind="pane")
    # walls going up: wall to wall a block higher, twice, then back down
    c.jump(2, 0, 0, "cobblestone_wall", kind="wall")
    c.jump(2, 1, 0, "cobblestone_wall", kind="wall one up")
    c.jump(2, 1, 1, "andesite_wall", kind="wall one up")
    c.jump(2, 0, -1, "oak_fence", kind="fence")
    c.jump(3, -1, 0, "dark_oak_planks", kind="down")
    c.end_section(3, 0, 0, "8: Fence run")

    # 8b. stairs and walls, longer: lone stairs (nothing past them to walk on) across three and four block gaps, every
    # way round, walls across three gaps and up a block, and a wall on a block to get round
    c.start_section("Stairs and walls", "Lone stairs across 3 and 4 gaps, facing every way, onto a stair one up, wall to "
                    "wall across gaps and a block up.", "TUNGSTEN")
    c.jump(4, 0, 0, "spruce_stairs[facing=east]", kind="stair 3 gap, high side first")
    c.jump(4, 0, 0, "spruce_stairs[facing=west]", kind="stair 3 gap, low side first")
    c.jump(4, 0, 1, "spruce_stairs[facing=north]", kind="stair 3+1, side on")
    c.jump(3, 0, -1, "quartz_block")
    c.walk(1, "quartz_block")
    c.jump(5, 0, 0, "spruce_stairs[facing=south]", kind="stair 4 gap off a run up, side on")
    c.jump(3, 1, 0, "spruce_stairs[facing=west]", kind="stair 2 gap, one up")
    c.jump(4, -1, 0, "quartz_block", kind="off a stair")
    c.jump(4, 0, 0, "cobblestone_wall", kind="wall 3 gap")
    c.jump(4, 0, -1, "cobblestone_wall", kind="wall to wall 3+1")
    c.jump(3, 1, 0, "cobblestone_wall", kind="wall to wall 2 gap, one up")
    c.jump(3, 0, 1, "stone_brick_wall", kind="wall to wall")
    c.jump(3, -1, 0, "quartz_block", kind="off a wall")
    # round pillars: a block with a wall stood on it, in the way of the straight line, hugged round its corner
    def pillar(px, pz, y):
        for py in range(y - 3, y + 1):
            c.put(px, py, pz, "polished_andesite")
        c.put(px, y + 1, pz, "cobblestone_wall")
    x, y, z = c.cur
    pillar(x + 1, z, y)
    c.jump(2, 0, 1, "quartz_block", kind="round a pillar's corner")
    x, y, z = c.cur
    pillar(x + 1, z, y)
    c.jump(2, 0, -1, "quartz_block", kind="round a pillar's corner")
    x, y, z = c.cur
    pillar(x + 1, z, y)
    pillar(x + 2, z, y)
    c.jump(3, 0, -1, "quartz_block", kind="round a long pillar's corner")
    x, y, z = c.cur
    pillar(x + 1, z, y)
    c.jump(2, 1, 1, "quartz_block", kind="round a corner, one up")
    # 2x2 platforms with a wall (or fence) two high on every block: no way over, walk round the rim of the platform outside
    # the walls, most of the box hanging over the edge
    def walled(px, pz, y, wall):
        for i in (0, 1):
            for k in (0, 1):
                c.put(px + i, y - 1, pz + k, "smooth_stone")
                c.put(px + i, y, pz + k, wall)
                c.put(px + i, y + 1, pz + k, wall)
    x, y, z = c.cur
    walled(x + 1, z, y, "cobblestone_wall")
    c.put(x + 3, y - 1, z, "quartz_block")
    c.jumps.append({"from": [x, y, z], "to": [x + 3, y, z], "kind": "round a walled platform", "block": "quartz_block"})
    c.cur = (x + 3, y, z)
    x, y, z = c.cur
    walled(x + 1, z - 1, y, "oak_fence")
    c.put(x + 3, y - 1, z, "quartz_block")
    c.jumps.append({"from": [x, y, z], "to": [x + 3, y, z], "kind": "round a fenced platform", "block": "quartz_block"})
    c.cur = (x + 3, y, z)
    # round the corner: in on one side, out on the next
    x, y, z = c.cur
    walled(x + 1, z, y, "cobblestone_wall")
    c.put(x + 2, y - 1, z + 3, "quartz_block")
    c.jumps.append({"from": [x, y, z], "to": [x + 2, y, z + 3], "kind": "round a walled platform's corner", "block": "quartz_block"})
    c.cur = (x + 2, y, z + 3)
    c.jump(2, 0, 0, "quartz_block")
    c.end_section(3, 0, 0, "9: Stairs and walls")

    # 9. ladders and vines: catch one in the air, climb, step off the top
    c.start_section("Ladders", "Jump across a gap onto a ladder, climb it, then the same with a vine.", "CLIMB")
    for block, attach in (("ladder[facing=west]", None), ("vine[east=true]", None)):
        c.jump(2, 0, 0, "mossy_stone_bricks")
        x, y, z = c.cur
        for py in range(y - 1, y + 4):
            c.put(x + 3, py, z, "mossy_stone_bricks")
        for ly in range(y, y + 4):
            c.put(x + 2, ly, z, block)
        c.cur = (x + 3, y + 4, z)
        c.jumps.append({"from": [x, y, z], "to": [x + 3, y + 4, z], "kind": "climb", "block": block})
        c.jump(2, -2, 0, "mossy_stone_bricks")
    c.end_section(3, 0, 0, "10: Ladders")

    # 10. ladder neos: round the end of a wall onto a ladder (then a vine) hung on its far side, up it and onto the top of
    # the wall. the runway is two wide, the wall's row and the open row beside it
    c.start_section("Ladder neos", "Round the end of a wall onto a ladder on its far side, climb onto the wall, then the "
                    "same with a vine.", "TUNGSTEN (ladder neo)")
    for climb in ("ladder[facing=east]", "vine[west=true]"):
        c.jump(2, 0, 1, "stone_bricks")
        x, y, z = c.cur          # the open row, z; the wall's row is z - 1
        for i in range(0, 3):
            c.put(x + i, y - 1, z, "stone_bricks")
            c.put(x + i, y - 1, z - 1, "stone_bricks")
        wx, wz = x + 3, z - 1    # the wall: one thick, from wz - 3 to wz, its top a walkway at y + 4
        for wy in range(y - 2, y + 4):
            for cz in range(wz - 3, wz + 1):
                c.put(wx, wy, cz, "cracked_stone_bricks" if (wy + cz) % 3 == 0 else "stone_bricks")
        for ly in range(y - 1, y + 4):
            for cz in (wz - 1, wz):
                c.put(wx + 1, ly, cz, climb)
        c.jumps.append({"from": [x + 2, y, z], "to": [wx, y + 4, wz], "kind": "ladder neo", "block": climb})
        # off the far end of the wall top and on
        c.cur = (wx, y + 4, wz - 3)
        c.jumps.append({"from": [wx, y + 4, wz], "to": [wx, y + 4, wz - 3], "kind": "walk", "block": "stone_bricks"})
        c.jump(3, -2, 1, "stone_bricks")
        c.jump(2, -1, 2, "stone_bricks")
    c.end_section(3, 0, 0, "11: Ladder neos")

    # 11. ice palace: every takeoff is off ice (packed, blue, plain), which keeps more of the jump's push than stone does,
    # so the gaps are longer than anywhere else. the landings are snow, you'd slide off a single block of ice
    c.start_section("Ice palace", "Takeoffs off packed, blue and plain ice into a four gap, a 4+2, a 4+3 down one, a "
                    "3+2 up one and a 4+1.", "TUNGSTEN (ice)")
    c.jump(2, 0, 0, "snow_block")
    c.walk(2, "packed_ice")
    c.jump(5, 0, 0, "snow_block", kind="ice 4b")
    c.walk(1, "packed_ice")
    c.jump(5, 0, 2, "snow_block", kind="ice 4+2")
    c.walk(1, "blue_ice")
    c.jump(5, -1, 3, "snow_block", kind="ice 4+3 down")
    c.walk(1, "packed_ice")
    c.jump(4, 1, -2, "snow_block", kind="ice 3+2 up")
    c.walk(1, "ice")
    c.jump(5, 0, -1, "snow_block", kind="ice 4+1")
    c.end_section(3, 0, 0, "12: Ice palace")

    # 12. sky tower: a spiral of diagonal one-up jumps round a pillar, round and round to the top
    c.start_section("Sky tower", "A spiral of diagonal jumps one up, round a pillar, six blocks up.", "TUNGSTEN")
    c.jump(3, 0, 0, "purpur_block")
    x, y, z = c.cur
    cx, cz = x + 2, z
    spiral = [(2, 1, -2), (2, 1, 2), (-2, 1, 2), (-2, 1, -2), (2, 1, -2), (2, 1, 2)]
    for (dx, dy, dz) in spiral:
        c.jump(dx, dy, dz, "purpur_block", kind="spiral")
    top = c.cur[1]
    for py in range(y - 6, top + 2):
        c.put(cx, py, cz, "purpur_pillar")
    c.put(cx, top + 2, cz, "end_rod[facing=up]")
    c.end_section(3, 0, 0, "13: Sky tower")

    # 13. slime factory: down onto slime and bounced across to a ledge no jump reaches. the slime is five down, the
    # ledges a block or two under where you started
    c.start_section("Slime factory", "Drop onto slime five blocks down and bounce across to a ledge seven out, then a "
                    "diagonal bounce and a longer one.", "TUNGSTEN (bounce)")
    def bounce(drop, sx, sz, lx, ly, lz):
        x, y, z = c.cur
        for i in (-1, 0, 1):
            for k in (-1, 0, 1):
                c.put(x + sx + i, y - 1 - drop, z + sz + k, "slime_block")
        c.jump(lx, ly, lz, "green_concrete", kind="slime bounce")
    c.jump(2, 0, 0, "green_concrete")
    bounce(5, 4, 0, 7, -1, 0)
    bounce(5, 3, 3, 5, -1, 5)
    bounce(5, 3, -3, 5, -1, -5)
    bounce(5, 4, 0, 7, -1, 0)
    c.end_section(3, 0, 0, "14: Slime factory")

    # 14. triple neos: round the end of a wall three thick, level. no standing start gets round one of those: the way it's
    # done is a run up of seven blocks along the row beside the wall's, off the side block at the wall's start, and a curve
    # in onto the block right behind the wall. once with the wall's end on the -z side of the run, once on the +z side
    c.start_section("Triple neos", "Level triple neos: a seven block run up along the side of a wall three thick, off the "
                    "side block and round onto the block behind it. Once on each side.", "TUNGSTEN (searched neo)")
    def wall(x0, x1, z0, z1, y):
        for wx in range(x0, x1 + 1):
            for wz in range(z0, z1 + 1):
                for wy in range(y - 2, y + 3):
                    c.put(wx, wy, wz, "deepslate_bricks" if (wx + wy + wz) % 4 else "cracked_deepslate_bricks")
    def triple_neo(side, block):
        # side: which way the wall's row is from the run's row (-1: the wall is on -z of the run)
        c.jump(2, 0, -side, block)              # onto the start of the run up, one row over from the wall's
        c.walk(6, block)                         # seven blocks of run up, the last one the side block
        x, y, z = c.cur
        wz = z + side                            # the wall's row
        wall(x + 1, x + 3, min(wz, wz + 3 * side), max(wz, wz + 3 * side), y)
        c.jump(4, 0, side, block, kind="triple neo")
    triple_neo(1, "polished_deepslate")
    c.walk(2, "polished_deepslate")
    triple_neo(-1, "polished_deepslate")
    c.walk(2, "polished_deepslate")
    c.end_section(3, 0, 0, "15: Triple neos")

    # 15. neo chains: neos back to back with a one block pad between. three one thick ones (each off the pad the last one
    # came down on, the wall it came round right behind), then two two thick ones where the pad between is no use to stand
    # on: the second only works off the speed of the first, so it's one flight with a touchdown in the middle
    c.start_section("Neo chains", "Three neos in a row with one block pads between, then two double neos back to back "
                    "(no stopping on the pad between).", "TUNGSTEN (searched neo)")
    c.jump(2, 0, 0, "polished_tuff")
    c.walk(1, "polished_tuff")
    x, y, z = c.cur
    for i in range(3):
        wall(x + 1 + 2 * i, x + 1 + 2 * i, z - 3, z, y)
        c.jump(2, 0, 0, "polished_tuff", kind="neo off a pad")
    c.walk(3, "polished_tuff")
    x, y, z = c.cur
    wall(x + 1, x + 2, z - 3, z, y)
    c.put(x + 3, y - 1, z, "tuff_bricks")
    wall(x + 4, x + 5, z - 3, z, y)
    c.put(x + 6, y - 1, z, "polished_tuff")
    c.jumps.append({"from": [x, y, z], "to": [x + 6, y, z], "kind": "double neo x2", "block": "polished_tuff"})
    c.cur = (x + 6, y, z)
    c.walk(2, "polished_tuff")
    c.end_section(3, 0, 0, "16: Neo chains")

    # 16. combo: a head hitter, straight into a neo, straight into a ladder neo, along the top of the wall and down
    c.start_section("Combo", "A three gap head hitter, then a neo, then a ladder neo up onto a wall, along it and off the "
                    "end.", "TUNGSTEN (combo)")
    c.jump(2, 0, 0, "cut_copper")
    x, y, z = c.cur
    for cx in range(x, x + 4):   # not over the landing: the neo goes from right under it
        c.put(cx, y + 3, z, "oxidized_cut_copper")
    c.jump(4, 0, 0, "cut_copper", kind="headhitter 3b")
    c.walk(1, "cut_copper")
    x, y, z = c.cur
    wall(x + 1, x + 1, z - 3, z, y)
    c.jump(2, 0, 0, "cut_copper", kind="neo")
    c.walk(1, "cut_copper")
    x, y, z = c.cur
    c.put(x, y - 1, z + 1, "cut_copper")    # the ladder neo's runway is two wide: the wall's row and the open row
    c.put(x - 1, y - 1, z + 1, "cut_copper")
    wx = x + 1
    for wy in range(y - 2, y + 4):
        for cz in range(z - 3, z + 1):
            c.put(wx, wy, cz, "weathered_cut_copper")
    for ly in range(y - 1, y + 4):
        for cz in (z - 1, z):
            c.put(wx + 1, ly, cz, "ladder[facing=east]")
    c.jumps.append({"from": [x, y, z + 1], "to": [wx, y + 4, z], "kind": "ladder neo", "block": "ladder"})
    c.cur = (wx, y + 4, z - 3)
    c.jumps.append({"from": [wx, y + 4, z], "to": [wx, y + 4, z - 3], "kind": "walk", "block": "weathered_cut_copper"})
    c.jump(3, -2, 1, "cut_copper")
    c.jump(2, -1, 2, "cut_copper")
    c.end_section(3, 0, 0, "17: Combo")

    # 17. slime to ladder: off the ledge, down onto slime, and the bounce up onto a ladder on a tower, up and over the top.
    # then the same onto a vine
    c.start_section("Slime to ladder", "Drop onto slime and bounce up onto a ladder on a tower, climb over the top, then "
                    "the same onto a vine.", "TUNGSTEN (bounce to ladder)")
    for climb in ("ladder[facing=west]", "vine[east=true]"):
        c.jump(2, 0, 0, "lime_concrete")
        x, y, z = c.cur
        for i in range(2, 5):
            for k in (-1, 0, 1):
                c.put(x + i, y - 6, z + k, "slime_block")
        tx = x + 7           # the tower: three wide, its west face carries the ladder from the slime up past the ledge
        for ty in range(y - 8, y + 3):
            for tz in (z - 1, z, z + 1):
                c.put(tx, ty, tz, "lime_terracotta")
        for ly in range(y - 4, y + 3):
            c.put(tx - 1, ly, z, climb)
        c.jumps.append({"from": [x, y, z], "to": [tx, y + 3, z], "kind": "slime to ladder", "block": climb})
        c.cur = (tx, y + 3, z)
        c.jump(2, -3, 0, "lime_concrete")
    c.end_section(3, 0, 0, "18: Slime to ladder")

    # 18. ice tunnel: a long packed ice runway under a ceiling at head height (hop along it, every hop bonking the ceiling,
    # and the ice keeps every bit of the push), out from under it at the end, and the longest jumps on the course
    c.start_section("Ice tunnel", "Hop along a packed ice tunnel under a low ceiling and out into a five gap, then a six "
                    "gap off a longer one.", "TUNGSTEN (ice hops)")
    for n, gap in ((8, 5), (10, 6)):
        c.jump(2, 0, 0, "snow_block")
        x, y, z = c.cur
        for i in range(1, n + 1):
            c.put(x + i, y - 1, z, "packed_ice")
            c.put(x + i, y - 1, z - 1, "packed_ice")
            c.put(x + i, y - 1, z + 1, "packed_ice")
        for cx in range(x, x + n - 1):
            for cz in (z - 1, z, z + 1):
                c.put(cx, y + 2, cz, "light_blue_stained_glass")
        c.cur = (x + n, y, z)
        c.jump(gap + 1, 0, 0, "snow_block", kind=f"ice tunnel {gap}b")
    c.end_section(3, 0, 0, "19: Ice tunnel")

    # 20. CubeCraft: three bits copied block for block out of a recording of a CubeCraft parkour map (#record), the ones the
    # bot couldn't do. the wall bit: off black wool a block up onto the strip of quartz outside a ring of walls, round it,
    # along the far side and off the end onto a lone stair. the wall neo: off the top of a wall round a wall post onto a
    # block. the stair drop: down the halves of one stair onto another and off it onto a stair two down
    c.start_section("CubeCraft", "Copied from a CubeCraft map: a hop up onto the rim of a ring of walls, round it and off "
                    "the end onto a stair; a neo off a wall top round a post; and a drop off a lone stair onto another two down.",
                    "LEDGES")
    WALL_BIT = [
        (0, -2, -1, "black_wool"), (0, -2, 0, "black_wool"), (0, -1, -3, "smooth_quartz"), (0, -1, -2, "smooth_quartz"),
        (0, -1, -1, "black_wool"), (0, -1, 0, "black_wool"), (0, 0, -3, "smooth_quartz"), (0, 0, -2, "smooth_quartz"),
        (0, 1, -3, "diorite_wall[east=tall,north=none,south=tall,up=true]"), (0, 1, -2, "diorite_wall[east=tall,north=tall,south=none,up=true]"),
        (0, 2, -3, "diorite_wall[east=low,north=none,south=low,up=true]"), (0, 2, -2, "diorite_wall[east=low,north=low,south=none,up=true]"),
        (1, -2, -1, "black_wool"), (1, -2, 0, "black_wool"), (1, -1, -3, "smooth_quartz"), (1, -1, -2, "smooth_quartz"),
        (1, -1, -1, "black_wool"), (1, -1, 0, "black_wool"), (1, 0, -3, "smooth_quartz"), (1, 0, -2, "smooth_quartz"),
        (1, 1, -3, "diorite_wall[east=none,north=none,south=tall,up=true,west=tall]"), (1, 1, -2, "diorite_wall[east=tall,north=tall,south=none,up=true,west=tall]"),
        (1, 2, -3, "diorite_wall[east=none,north=none,south=low,up=true,west=low]"), (1, 2, -2, "diorite_wall[east=low,north=low,south=none,up=true,west=low]"),
        (2, 1, -2, "diorite_wall[east=none,north=none,south=tall,up=true,west=tall]"), (2, 1, -1, "diorite_wall[east=none,north=tall,south=none,up=true,west=none]"),
        (2, 2, -2, "diorite_wall[east=none,north=none,south=low,up=true,west=low]"), (2, 2, -1, "diorite_wall[east=none,north=low,south=none,up=true,west=none]"),
        (6, 0, -3, "polished_blackstone_brick_stairs[facing=east,half=bottom]"), (6, 0, -2, "polished_blackstone_brick_stairs[facing=east,half=bottom]"),
        (7, 0, -3, "black_wool"), (7, 0, -2, "black_wool"),
        (7, 1, -3, "polished_blackstone_brick_stairs[facing=east,half=bottom]"), (7, 1, -2, "polished_blackstone_brick_stairs[facing=east,half=bottom]"),
    ]
    WALL_NEO_AND_DROP = [
        (-14, -3, -5, "smooth_quartz_stairs[facing=east,half=bottom]"), (-13, -3, -6, "smooth_quartz"),
        (-13, -2, -6, "smooth_quartz_stairs[facing=east,half=bottom]"),
        (-8, -1, -5, "polished_blackstone_brick_stairs[facing=east,half=bottom]"), (-7, -1, -6, "black_wool"),
        (-7, 0, -6, "polished_blackstone_brick_stairs[facing=east,half=bottom]"),
        (-3, -1, -6, "smooth_quartz"), (-3, -1, -5, "smooth_quartz"), (-3, 0, -6, "smooth_quartz"), (-3, 0, -5, "smooth_quartz"),
        (-2, -1, -6, "smooth_quartz"), (-2, -1, -5, "smooth_quartz"), (-2, 0, -6, "smooth_quartz"), (-2, 0, -5, "smooth_quartz"),
        (3, -1, -6, "black_wool"), (3, -1, -5, "black_wool"), (3, 0, -6, "black_wool"), (3, 0, -5, "black_wool"),
        (3, 1, -6, "blackstone_wall[south=tall,up=true]"), (3, 1, -5, "blackstone_wall[north=tall,up=true]"),
        (3, 2, -6, "blackstone_wall[south=low,up=true]"), (3, 2, -5, "blackstone_wall[north=low,up=true]"),
        (4, -1, -6, "black_wool"), (4, -1, -5, "black_wool"), (4, -1, -3, "smooth_quartz"),
        (4, -1, -1, "diorite_wall[south=low,up=true]"), (4, -1, 0, "blackstone_wall[north=low,up=true]"),
        (4, 0, -6, "black_wool"), (4, 0, -5, "black_wool"), (4, 0, -1, "diorite_wall[up=true]"), (4, 1, -1, "diorite_wall[up=true]"),
    ]
    def copy(blocks, ax, ay, az):
        for dx, dy, dz, state in blocks:
            c.put(ax + dx, ay + dy, az + dz, state)
    def ledge_jump(to, kind, block):
        x, y, z = c.cur
        c.jumps.append({"from": [x, y, z], "to": list(to), "kind": kind, "block": block})
        c.cur = tuple(to)
    # the wall bit, its black wool three on
    x, y, z = c.cur
    ax, ay, az = x + 3, y, z
    copy(WALL_BIT, ax, ay, az)
    c.jumps.append({"from": [x, y, z], "to": [ax, ay, az], "kind": "onto black wool", "block": "black_wool"})
    c.cur = (ax, ay, az)
    ledge_jump((ax + 7, ay + 2, az - 2), "cubecraft wall bit: up onto the rim, round, off onto a stair", "polished_blackstone_brick_stairs")
    # on east along the stair's row and north, then up onto the wall top from the south (the map has another wall top 4 west
    # of it, left out here: from it the quartz past the neo is in reach, and the bot would skip the neo). everything after it is laid out
    # so the only way on is the way the CubeCraft map goes: the bot took every shortcut the first layouts had (off the wall
    # neo straight across onto the finale, and back along the run in to skip the stair drop)
    c.jump(3, 0, 0)
    c.jump(3, 0, 0)
    c.jump(4, 0, 0)
    c.jump(0, 0, -3)
    c.jump(0, 0, -3)
    x, y, z = c.cur
    ax, ay, az = x - 4, y, z - 3
    copy(WALL_NEO_AND_DROP, ax, ay, az)
    ledge_jump((ax + 4, ay, az), "onto a wall top", "blackstone_wall")
    ledge_jump((ax + 4, ay, az - 3), "cubecraft wall neo: off a wall top round a post", "smooth_quartz")
    ledge_jump((ax + 4, ay + 1, az - 5), "up onto wool", "black_wool")
    ledge_jump((ax - 2, ay + 1, az - 5), "past a walled block, 4 gap", "smooth_quartz")
    ledge_jump((ax - 7, ay + 1, az - 6), "onto a lone stair", "polished_blackstone_brick_stairs")
    ledge_jump((ax - 13, ay - 1, az - 6), "cubecraft stair drop: down a stair's halves and off onto a stair two down", "smooth_quartz_stairs")
    # off it to the west and away north, out of reach of all of the above
    c.jump(-3, 0, 0)
    for _ in range(4):
        c.jump(0, 0, -3)
    c.end_section(3, 0, 0, "20: CubeCraft")

    # 21. decorations: the bits of the CubeCraft levels that aren't blocks at all, stood on like blocks. flower pots and
    # heads on fence posts (the fence is the floor, half a block up into the pot's cell), a head floating on its own, a head
    # on a wall, torches and lanterns on posts, a candle
    c.start_section("Decorations", "Flower pots and heads on fence posts, pot to pot up and down, a floating head, a head "
                    "on a wall, a torch and a lantern on posts and a candle: things that aren't blocks, stood on like blocks.",
                    "TUNGSTEN")
    def post(dx, dy, dz, top, under="spruce_fence", kind="decoration"):
        # top in the cell under the feet, a fence post under that: you stand on the post's top, half a block into top's cell
        x, y, z = c.cur
        c.jump(dx, dy, dz, top, kind=kind)
        c.put(x + dx, y + dy - 2, z + dz, under)
    post(2, 1, 0, "potted_cactus", kind="up onto a pot on a post")
    post(3, 0, -1, "potted_cactus", kind="pot to pot")
    post(2, 1, -2, "potted_fern", kind="pot to pot, one up")
    post(3, -1, 1, "potted_dead_bush", kind="pot to pot, one down")
    post(4, -1, 2, "potted_cactus", kind="pot to pot, far and one down")
    post(2, 1, -2, "player_head", kind="onto a head on a post")
    c.jump(3, 0, 0, "player_head", kind="onto a floating head")
    c.jump(2, 0, 2, "player_wall_head[facing=east]", kind="onto a head on a wall")
    x, y, z = c.cur
    c.put(x - 1, y - 1, z, "sandstone")
    c.jump(3, 0, -1, "dark_oak_fence", kind="onto a torch on a fence")
    x, y, z = c.cur
    c.put(x, y, z, "torch")
    post(3, 0, 0, "lantern", under="dark_oak_fence", kind="onto a lantern on a fence")
    post(2, 0, 1, "white_candle", under="quartz_block", kind="onto a candle")
    c.jump(3, 0, 0, "quartz_block", kind="off a candle")
    c.end_section(3, 0, 0, "21: Decorations")

    # 22. pillars over lava: one block pillars with a fence post two high on top, the way the hell level has them. the
    # post leaves a strip of the top either side of it, and that strip is all there is to stand on: hop onto the strip, walk
    # round the post, off the far side onto the next one. one has a head on its post to stand up on, and there's a lone iron
    # bar. the lava's a fall like falling off is
    c.start_section("Pillars", "One block pillars over lava, each with a fence post two high in the middle of it: land on "
                    "the strip of top beside the post, walk round it, jump on. Then up onto a head on a post, down onto bars.",
                    "TUNGSTEN (rims)")
    x0, y, z0 = c.cur
    def pillar_post(px, pz, top_y, post=("nether_brick_fence", "nether_brick_fence")):
        for py in range(top_y - 4, top_y):
            c.put(px, py, pz, "obsidian")
        for i, b in enumerate(post):
            c.put(px, top_y + i, pz, b)
    def rim_jump(to, kind):
        x, yy, z = c.cur
        c.jumps.append({"from": [x, yy, z], "to": list(to), "kind": kind, "block": "obsidian"})
        c.cur = tuple(to)
    # a lava pool under the lot, in a basin so it stays put
    lx0, lx1, lz0, lz1 = x0 + 1, x0 + 22, z0 - 4, z0 + 4
    for lx in range(lx0 - 1, lx1 + 2):
        for lz in range(lz0 - 1, lz1 + 2):
            c.put(lx, y - 6, lz, "netherrack", overwrite=False)
            edge = lx in (lx0 - 1, lx1 + 1) or lz in (lz0 - 1, lz1 + 1)
            c.put(lx, y - 5, lz, "netherrack" if edge else "lava", overwrite=False)
    # A: from the pad onto its west rim, round the post to its east rim
    pillar_post(x0 + 3, z0, y)
    rim_jump((x0 + 2, y, z0), "onto a pillar's rim")
    rim_jump((x0 + 4, y, z0), "round the post")
    # B: across onto its west rim, round its corner to the north rim
    pillar_post(x0 + 8, z0 + 1, y)
    rim_jump((x0 + 7, y, z0 + 1), "rim to rim")
    rim_jump((x0 + 8, y, z0), "round the post's corner")
    # C: a block lower, a fence and a head on it: stand up on the head, half a block up from the rim
    pillar_post(x0 + 11, z0 - 2, y - 1, post=("nether_brick_fence", "player_head"))
    rim_jump((x0 + 11, y + 1, z0 - 2), "up onto a head on a post")
    # D: down onto its rim, two down, round to the far side
    pillar_post(x0 + 14, z0 + 1, y - 1)
    rim_jump((x0 + 14, y - 1, z0), "down onto a rim")
    rim_jump((x0 + 15, y - 1, z0 + 1), "round the post's corner")
    # a lone iron bar on a block, then off onto quartz
    for py in range(y - 5, y - 1):
        c.put(x0 + 18, py, z0 + 2, "obsidian")
    c.put(x0 + 18, y - 1, z0 + 2, "iron_bars")
    rim_jump((x0 + 18, y + 0, z0 + 2), "onto a lone iron bar")
    c.cur = (x0 + 18, y, z0 + 2)
    c.jump(3, 0, -2, "quartz_block", kind="off the bar")
    c.end_section(3, 0, 0, "22: Pillars")

    # 23. ladder tops: single ladders hanging in the air, stood on (a ladder's a thin plate along one side of its cell, and
    # its top edge holds you), and one caught in mid air and climbed onto, the desert level's ladder bit
    c.start_section("Ladder tops", "Single ladders hanging in the air: land on the top edge of one, hop to the next, catch "
                    "one in mid air and climb up onto its top, then off onto a slab.", "TUNGSTEN (ladders)")
    def ladder_top(dx, dy, dz, facing, kind):
        x, yy, z = c.cur
        nx, ny, nz = x + dx, yy + dy, z + dz
        c.put(nx, ny - 1, nz, f"ladder[facing={facing}]")
        c.jumps.append({"from": [x, yy, z], "to": [nx, ny, nz], "kind": kind, "block": "ladder"})
        c.cur = (nx, ny, nz)
    ladder_top(3, 0, 0, "west", "onto a ladder's top")
    ladder_top(2, 1, 2, "north", "ladder top to ladder top, one up")
    ladder_top(3, 0, -1, "east", "ladder top to ladder top")
    # two ladders one over the other, caught at the bottom one and climbed onto the top
    x, yy, z = c.cur
    gx, gz = x + 3, z + 1
    c.put(gx, yy, gz, "ladder[facing=south]")
    c.put(gx, yy + 1, gz, "ladder[facing=south]")
    c.jumps.append({"from": [x, yy, z], "to": [gx, yy, gz], "kind": "catch a ladder", "block": "ladder"})
    c.jumps.append({"from": [gx, yy, gz], "to": [gx, yy + 2, gz], "kind": "climb onto its top", "block": "ladder"})
    c.cur = (gx, yy + 2, gz)
    c.jump(3, -1, 0, "birch_slab", kind="off a ladder top onto a slab")
    c.jump(3, 0, 0, "quartz_block", kind="slab to block")
    c.end_section(3, 0, 0, "23: Ladder tops")

    # 24. soul sand: it slows you down to under half speed, so a run up on it gets you nowhere, and it's a little lower than a
    # block (an eighth). jumps onto it, along it, and off it across gaps that need the run up to count
    c.start_section("Soul sand", "Soul sand slows you to under half speed: onto it, a slow run up along it and across a gap, "
                    "soul sand to soul sand, up onto it and off it.", "TUNGSTEN")
    c.jump(2, 0, 0, "soul_sand", kind="onto soul sand")
    c.walk(2, "soul_sand")
    c.jump(3, 0, 0, "soul_sand", kind="soul sand to soul sand, off a slow run up")
    c.jump(3, 0, 1, "quartz_block", kind="off soul sand")
    c.walk(1, "quartz_block")
    c.walk(3, "soul_sand")
    c.jump(4, 0, 0, "quartz_block", kind="3 gap off a soul sand run up")
    c.jump(2, 1, 0, "soul_sand", kind="up onto soul sand")
    c.jump(3, 0, -1, "soul_sand", kind="soul sand to soul sand, diagonal")
    c.jump(3, -1, 0, "quartz_block", kind="off soul sand, one down")
    c.end_section(3, 0, 0, "24: Soul sand")

    # 25. water blocks: single water sources hanging in the air, the desert level's water bit. there's no jumping out of the
    # top of one (in water, space only swims up), so the way on is a block you can fall into, or one a block or two higher
    # that the top of you reaches into as you come up out of this one, and swim up that
    c.start_section("Water blocks", "Water blocks hanging in the air, like the desert level's: into a column of it, down into "
                    "another, then up a ladder of them two at a time (swim up, lean over into the next), and out at the bottom "
                    "onto a block.", "TUNGSTEN (water)")
    def water(dx, dy, dz, kind, deep=False):
        x, yy, z = c.cur
        nx, ny, nz = x + dx, yy + dy, z + dz
        c.put(nx, ny, nz, "water")
        if deep:
            c.put(nx, ny - 1, nz, "water")
        c.jumps.append({"from": [x, yy, z], "to": [nx, ny, nz], "kind": kind, "block": "water"})
        c.cur = (nx, ny, nz)
    water(3, 1, 0, "into a column of water two high, on the level", deep=True)
    water(2, -2, 1, "down into another")
    water(1, 2, 1, "up into the one over it, two up")
    water(1, 2, -1, "and two up again")
    water(2, -3, 0, "down into one more")
    c.jump(2, -3, -1, "quartz_block", kind="out of the side and down onto a block")
    c.end_section(3, 0, 0, "25: Water blocks")

    # 26. the finale: a bit of every section, one after the other, no checkpoints between. straights, diagonals, ups and
    # downs, a neo, a four blocker, a head hitter, fences, a ladder, ice, a slime bounce, a triple neo, a neo chain, slime
    # onto a ladder, the ice tunnel, a ladder neo up a wall, and the finish on top
    c.start_section("Finale", "Every section in one run: straights, diagonals, ups and downs, a neo, a four blocker, a "
                    "head hitter, slabs, fences, a ladder, ice, a slime bounce, a triple neo, a neo chain, slime to a ladder, the "
                    "ice tunnel and a ladder neo up to the finish.", "THE BOSS")
    F = "white_concrete"
    G = "black_concrete"
    # straights, diagonals, ups and downs
    c.jump(3, 0, 0, F, kind="straight")
    c.jump(4, 0, 0, G, kind="straight 3b")
    c.jump(3, 0, 2, F, kind="diagonal")
    c.jump(2, 1, -2, G, kind="diagonal up")
    c.jump(3, -1, 2, F, kind="diagonal down")
    c.jump(3, -1, -1, G, kind="down")
    # a neo round a one thick wall
    c.walk(1, G)
    x, y, z = c.cur
    wall(x + 1, x + 1, z - 3, z, y)
    c.jump(2, 0, 0, F, kind="neo")
    # a four blocker off a run up
    c.walk(2, F)
    c.jump(5, 0, 0, G, kind="4b")
    # a head hitter, three gap
    x, y, z = c.cur
    for cx in range(x, x + 4):
        c.put(cx, y + 3, z, "polished_blackstone_bricks")
    c.jump(4, 0, 0, F, kind="headhitter 3b")
    # slabs and fences
    c.jump(3, 0, 0, "smooth_stone_slab", kind="slab")
    c.jump(3, 0, -1, "smooth_stone_slab", kind="slab to slab")
    c.jump(2, 0, 1, "dark_oak_fence", kind="fence")
    c.jump(2, 0, -1, "white_stained_glass_pane", kind="pane")
    c.jump(3, 0, 0, G, kind="off a pane")
    # a ladder: across and up it
    x, y, z = c.cur
    for py in range(y - 1, y + 4):
        c.put(x + 3, py, z, G)
    for ly in range(y, y + 4):
        c.put(x + 2, ly, z, "ladder[facing=west]")
    c.jumps.append({"from": [x, y, z], "to": [x + 3, y + 4, z], "kind": "climb", "block": "ladder"})
    c.cur = (x + 3, y + 4, z)
    c.jump(2, -2, 0, F)
    # an ice jump
    c.walk(2, "packed_ice")
    c.jump(5, 0, 0, "snow_block", kind="ice 4b")
    # a slime bounce
    x, y, z = c.cur
    for i in (-1, 0, 1):
        for k in (-1, 0, 1):
            c.put(x + 4 + i, y - 6, z + k, "slime_block")
    c.jump(7, -1, 0, G, kind="slime bounce")
    # a triple neo
    c.walk(1, G)
    triple_neo(1, F)
    # a neo chain: two one thick neos, a pad between
    c.walk(2, F)
    x, y, z = c.cur
    for i in range(2):
        wall(x + 1 + 2 * i, x + 1 + 2 * i, z - 3, z, y)
        c.jump(2, 0, 0, G if i == 0 else F, kind="neo off a pad")
    # slime onto a ladder on a tower, up and over
    c.walk(2, F)
    x, y, z = c.cur
    for i in range(2, 5):
        for k in (-1, 0, 1):
            c.put(x + i, y - 6, z + k, "slime_block")
    tx = x + 7
    for ty in range(y - 8, y + 3):
        for tz in (z - 1, z, z + 1):
            c.put(tx, ty, tz, "polished_blackstone")
    for ly in range(y - 4, y + 3):
        c.put(tx - 1, ly, z, "ladder[facing=west]")
    c.jumps.append({"from": [x, y, z], "to": [tx, y + 3, z], "kind": "slime to ladder", "block": "ladder"})
    c.cur = (tx, y + 3, z)
    c.jump(2, -3, 0, F)
    # the ice tunnel
    c.jump(2, 0, 0, "snow_block")
    x, y, z = c.cur
    for i in range(1, 9):
        for k in (-1, 0, 1):
            c.put(x + i, y - 1, z + k, "packed_ice")
    for cx in range(x, x + 7):
        for k in (-1, 0, 1):
            c.put(cx, y + 2, z + k, "light_blue_stained_glass")
    c.cur = (x + 8, y, z)
    c.jump(6, 0, 0, "snow_block", kind="ice tunnel 5b")
    # a ladder neo up onto a wall, and the finish on top of it
    c.jump(2, 0, 1, G)
    x, y, z = c.cur          # the open row, z; the wall's row is z - 1
    for i in range(0, 3):
        c.put(x + i, y - 1, z, G)
        c.put(x + i, y - 1, z - 1, G)
    wx, wz = x + 3, z - 1
    for wy in range(y - 2, y + 4):
        for cz in range(wz - 3, wz + 1):
            c.put(wx, wy, cz, "polished_blackstone_bricks")
    for ly in range(y - 1, y + 4):
        for cz in (wz - 1, wz):
            c.put(wx + 1, ly, cz, "ladder[facing=east]")
    c.jumps.append({"from": [x + 2, y, z], "to": [wx, y + 4, wz], "kind": "ladder neo", "block": "ladder"})
    # the finish: emerald along the top of the wall
    fx, fy, fz = wx, y + 4, wz - 2
    for dz in (-1, 0, 1):
        c.put(fx, fy - 1, fz + dz, "emerald_block")
    c.jumps.append({"from": [wx, y + 4, wz], "to": [fx, fy, fz], "kind": "walk", "block": "emerald_block"})
    finish = {"id": len(c.checkpoints), "x": fx, "y": fy, "z": fz, "name": "Finish"}
    c.checkpoints.append(finish)
    c.labels.append((fx + 0.5, fy + 2.2, fz + 0.5, "Finish"))
    c.sections[-1]["to"] = finish["id"]
    return c


# ---------------------------------------------------------------------------------------------- data pack output

def runs(blocks):
    """merge single blocks into fills along x where the state is the same"""
    by_row = {}
    for (x, y, z), s in blocks.items():
        by_row.setdefault((y, z, s), []).append(x)
    out = []
    for (y, z, s), xs in by_row.items():
        xs.sort()
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and x == prev + 1:
                prev = x
                continue
            out.append((start, y, z, prev, s))
            if x is not None:
                start = prev = x
    return out


def bbox(c):
    xs = [p[0] for p in c.blocks]
    ys = [p[1] for p in c.blocks]
    zs = [p[2] for p in c.blocks]
    return min(xs) - 2, min(ys) - 1, min(zs) - 2, max(xs) + 2, max(ys) + 4, max(zs) + 2


def chunk_ranges(lo, hi, step):
    a = lo
    while a <= hi:
        yield a, min(hi, a + step - 1)
        a += step


def still(box, fluids):
    """fills that take out the water or lava that has run from the sources (CubeCraft has fluids turned off: a lone water
    block or a lava fall stays put there). strict, so the sources aren't told and don't run again"""
    x0, y0, z0, x1, y1, z1 = box
    lines = []
    for fluid in fluids:
        for lvl in range(1, 16):
            for a, b in chunk_ranges(x0, x1, 16):
                for d, e in chunk_ranges(z0, z1, 16):
                    for ya, yb in chunk_ranges(y0, y1, 120):
                        lines.append(f"fill {a} {ya} {d} {b} {yb} {e} air replace minecraft:{fluid}[level={lvl}] strict")
    return lines


def fluids_in(blocks):
    return [f for f in ("water", "lava") if any(st.startswith(f) for st in blocks.values())]


def datapack(c, outdir, levels=()):
    x0, y0, z0, x1, y1, z1 = bbox(c)
    fn = {}

    def tellraw(text, color="gold"):
        return 'tellraw @s ' + json.dumps({"text": text, "color": color})

    fn["load"] = [
        # like CubeCraft: no fall damage (a fall in the Lab or a level only puts you back, it never kills you)
        "gamerule fall_damage false",
        "scoreboard objectives add pl_cp dummy",
        "scoreboard objectives add pl_t dummy",
        "scoreboard objectives add pl_split dummy",
        "scoreboard objectives add pl_falls dummy",
        "scoreboard objectives add pl_sfalls dummy",
        "scoreboard objectives add pl_id dummy",
        "scoreboard objectives add pl_tmp dummy",
        "scoreboard players set #20 pl_tmp 20",
        "scoreboard players set #5 pl_tmp 5",
        "scoreboard players set #10 pl_tmp 10",
        'tellraw @a ' + json.dumps([{"text": "[Parkour Lab] ", "color": "gold"},
                                     {"text": "loaded. ", "color": "white"},
                                     {"text": "/function parkourlab:build", "color": "aqua",
                                      "click_event": {"action": "suggest_command", "command": "/function parkourlab:build"}},
                                     {"text": " to build the course, then ", "color": "white"},
                                     {"text": "/function parkourlab:start", "color": "aqua",
                                      "click_event": {"action": "suggest_command", "command": "/function parkourlab:start"}}]),
    ]

    # building: load the chunks, give them a couple of seconds, then fill. fills are capped at 32768 blocks each, the runs
    # are tiny, so it's just a lot of lines
    corners = [(x0, y0, z0), (x1, y0, z0), (x0, y0, z1), (x1, y0, z1), ((x0 + x1) // 2, y0, (z0 + z1) // 2)]
    loaded = " ".join(f"if loaded {cx} {cy} {cz}" for cx, cy, cz in corners)
    fn["build"] = [
        tellraw("[Parkour Lab] Loading the area, the course goes up in a moment..."),
        f"schedule clear {PACK}:clear_wait",
        f"forceload add {x0} {z0} {x1} {z1}",
        "scoreboard players set #wait pl_tmp 0",
        f"schedule function {PACK}:build_wait 10t",
    ]
    # chunks a forceload asked for turn up over the next few ticks, not straight away. a fill into one that isn't there yet
    # just fails, so wait until all the corners are in (or give up after 30 seconds)
    fn["build_wait"] = [
        "scoreboard players add #wait pl_tmp 1",
        f"execute {loaded} run return run function {PACK}:build_now",
        'execute if score #wait pl_tmp matches 60.. run return run tellraw @a {"text":"[Parkour Lab] The area never loaded, try again from closer to it (x 1000 z 1000).","color":"red"}',
        f"schedule function {PACK}:build_wait 10t",
    ]
    lines = [f"kill @e[tag={PACK}]"]
    # clear the box first so a rebuild on top of an old one is clean
    for a, b in chunk_ranges(x0, x1, 16):
        for d, e in chunk_ranges(z0, z1, 16):
            lines.append(f"fill {a} {y0} {d} {b} {y1} {e} air")
    # things that hang in the air (a water block, a ladder) go in last and with no block updates: water would run, and a
    # ladder with nothing behind it would drop off when something next to it got placed
    def fragile(st):
        return st.startswith(("water", "ladder"))
    for (xa, y, z, xb, s) in sorted(runs(c.blocks), key=lambda r: (fragile(r[4]), r[1], r[2], r[0])):
        mode = " strict" if fragile(s) else ""
        if xa == xb:
            lines.append(f"setblock {xa} {y} {z} {s}{mode}")
        else:
            lines.append(f"fill {xa} {y} {z} {xb} {y} {z} {s}{mode}")
    for cp in c.checkpoints:
        lines.append(f'summon marker {cp["x"] + 0.5} {cp["y"]} {cp["z"] + 0.5} '
                     f'{{Tags:["{PACK}","pl_labcp","pl_cp{cp["id"]}"],Rotation:[-90f,0f]}}')
        lines.append(f'scoreboard players set @e[type=marker,tag=pl_cp{cp["id"]},limit=1] pl_id {cp["id"]}')
    for (lx, ly, lz, text) in c.labels:
        lines.append(f'summon text_display {lx} {ly} {lz} {{Tags:["{PACK}"],billboard:"center",'
                     f'text:{json.dumps(text)},background:1073741824,see_through:1b}}')
    lines.append(f"forceload remove {x0} {z0} {x1} {z1}")
    if levels:
        lines.append(f"schedule function {PACK}:lvb/0 2t")
    lines.append('tellraw @a ' + json.dumps([{"text": "[Parkour Lab] ", "color": "gold"},
                                             {"text": f"Built: {len(c.blocks)} blocks, {len(c.sections)} sections. ", "color": "white"},
                                             {"text": "[Start]", "color": "green",
                                              "click_event": {"action": "run_command", "command": f"/function {PACK}:start"}}]))
    fn["build_now"] = lines

    clear = [tellraw("[Parkour Lab] Clearing..."), f"forceload add {x0} {z0} {x1} {z1}",
             f"schedule clear {PACK}:build_wait", "scoreboard players set #wait pl_tmp 0",
             f"schedule function {PACK}:clear_wait 10t"]
    fn["clear_wait"] = [
        "scoreboard players add #wait pl_tmp 1",
        f"execute {loaded} run return run function {PACK}:clear_now",
        f"execute if score #wait pl_tmp matches ..59 run schedule function {PACK}:clear_wait 10t",
    ]
    fn["clear"] = clear
    clear_now = [f"kill @e[tag={PACK}]"]
    for a, b in chunk_ranges(x0, x1, 16):
        for d, e in chunk_ranges(z0, z1, 16):
            clear_now.append(f"fill {a} {y0} {d} {b} {y1} {e} air")
    clear_now += [f"forceload remove {x0} {z0} {x1} {z1}", 'tellraw @a {"text":"[Parkour Lab] Cleared.","color":"gold"}']
    fn["clear_now"] = clear_now

    # the Lab's own hanging water (section 25): what runs from it taken out, at the start and every checkpoint teleport
    wet = [p for p, st in c.blocks.items() if st.startswith("water")]
    fn["still"] = still((min(p[0] for p in wet) - 8, y0, min(p[2] for p in wet) - 8, max(p[0] for p in wet) + 8,
                         max(p[1] for p in wet), max(p[2] for p in wet) + 8), ["water"]) if wet else []
    s = c.checkpoints[0]
    fn["start"] = [
        f"function {PACK}:still",
        f"schedule function {PACK}:still 200t",
        f"function {PACK}:leave",
        f"tag @s add pl_run",
        "scoreboard players set @s pl_cp 0",
        "scoreboard players set @s pl_t 0",
        "scoreboard players set @s pl_split 0",
        "scoreboard players set @s pl_falls 0",
        "scoreboard players set @s pl_sfalls 0",
        "effect give @s saturation infinite 0 true", "effect give @s fire_resistance infinite 0 true",
        f"tp @s {s['x'] + 0.5} {s['y']} {s['z'] + 0.5} -90 0",
        tellraw("[Parkour Lab] Go! Timer's running. Fall and you're back at the last checkpoint. /function parkourlab:stop to stop."),
    ]
    fn["stop"] = [f"function {PACK}:leave", "tag @s remove pl_marathon", "effect clear @s saturation", tellraw("[Parkour Lab] Stopped.")]
    # out of whatever course this is on (the Lab or a level)
    fn["leave"] = ["tag @s remove pl_run", "tag @s remove pl_lvrun"] + [f"tag @s remove pl_on_{lv.name}" for lv in levels]

    # checkpoints you can teleport straight to (the run carries on from there, timer and all)
    for cp in c.checkpoints:
        fn[f"cp/{cp['id']}"] = [
            f"function {PACK}:still",
            f"function {PACK}:leave",
            "tag @s add pl_run",
            f"scoreboard players set @s pl_cp {cp['id']}",
            "scoreboard players operation @s pl_split = @s pl_t",
            "scoreboard players set @s pl_sfalls 0",
            "effect give @s saturation infinite 0 true", "effect give @s fire_resistance infinite 0 true",
            f"tp @s {cp['x'] + 0.5} {cp['y']} {cp['z'] + 0.5} -90 0",
            tellraw(f"[Parkour Lab] At checkpoint {cp['id']}: {cp['name']}"),
        ]

    fn["tick"] = [
        "scoreboard players add @a[tag=pl_run] pl_t 1",
        "scoreboard players add @a[tag=pl_lvrun] pl_t 1",
        f"execute as @a[tag=pl_lvrun] run function {PACK}:hud",
        f"execute as @e[type=marker,tag=pl_labcp] at @s as @a[tag=pl_run,distance=..2.3] run function {PACK}:at_cp",
        f"execute as @a[tag=pl_run] at @s if entity @s[y=-64,dy={FALL_Y + 64}] run function {PACK}:fell",
        f"execute as @a[tag=pl_run] at @s if block ~ ~ ~ minecraft:lava run function {PACK}:fell",
        f"execute as @a[tag=pl_run] run function {PACK}:hud",
    ]
    fn["hud"] = [
        "scoreboard players operation #s pl_tmp = @s pl_t",
        "scoreboard players operation #s pl_tmp /= #20 pl_tmp",
        'title @s actionbar ' + json.dumps([{"text": "Checkpoint ", "color": "gray"}, {"score": {"name": "@s", "objective": "pl_cp"}, "color": "white"},
                                            {"text": "   Time ", "color": "gray"}, {"score": {"name": "#s", "objective": "pl_tmp"}, "color": "white"},
                                            {"text": "s   Falls ", "color": "gray"}, {"score": {"name": "@s", "objective": "pl_falls"}, "color": "white"}]),
    ]
    # (which checkpoint by the marker's own tag, not its pl_id score: see the levels' at_cp)
    fn["at_cp"] = ["scoreboard players set #id pl_tmp -1"] + [
        f"execute if entity @e[type=marker,tag=pl_cp{cp['id']},distance=..0.1] run scoreboard players set #id pl_tmp {cp['id']}"
        for cp in c.checkpoints] + [
        f"execute if score #id pl_tmp > @s pl_cp run function {PACK}:new_cp",
    ]
    names = {cp["id"]: cp["name"] for cp in c.checkpoints}
    last = c.checkpoints[-1]["id"]
    new_cp = [
        "scoreboard players operation #sec pl_tmp = @s pl_t",
        "scoreboard players operation #sec pl_tmp -= @s pl_split",
        "scoreboard players operation #secs pl_tmp = #sec pl_tmp",
        "scoreboard players operation #secs pl_tmp /= #20 pl_tmp",
        "scoreboard players operation #frac pl_tmp = #sec pl_tmp",
        "scoreboard players operation #frac pl_tmp %= #20 pl_tmp",
        "scoreboard players operation #frac pl_tmp *= #5 pl_tmp",
        "scoreboard players operation #fract pl_tmp = #frac pl_tmp",
        "scoreboard players operation #fract pl_tmp /= #10 pl_tmp",
        "scoreboard players operation #fracu pl_tmp = #frac pl_tmp",
        "scoreboard players operation #fracu pl_tmp %= #10 pl_tmp",
        "scoreboard players operation @s pl_cp = #id pl_tmp",
        "scoreboard players operation @s pl_split = @s pl_t",
        'tellraw @s ' + json.dumps([{"text": "[Parkour Lab] ", "color": "gold"}, {"text": "Section done: checkpoint ", "color": "white"},
                                    {"score": {"name": "@s", "objective": "pl_cp"}, "color": "aqua"},
                                    {"text": " in ", "color": "white"}, {"score": {"name": "#secs", "objective": "pl_tmp"}, "color": "aqua"},
                                    {"text": ".", "color": "aqua"}, {"score": {"name": "#fract", "objective": "pl_tmp"}, "color": "aqua"},
                                    {"score": {"name": "#fracu", "objective": "pl_tmp"}, "color": "aqua"},
                                    {"text": "s (", "color": "white"}, {"score": {"name": "#sec", "objective": "pl_tmp"}, "color": "white"},
                                    {"text": " ticks), falls this section: ", "color": "white"},
                                    {"score": {"name": "@s", "objective": "pl_sfalls"}, "color": "red"}]),
        "scoreboard players set @s pl_sfalls 0",
        f"execute if score @s pl_cp matches {last} run function {PACK}:finished",
    ]
    fn["new_cp"] = new_cp
    fn["finished"] = [
        "scoreboard players operation #secs pl_tmp = @s pl_t",
        "scoreboard players operation #secs pl_tmp /= #20 pl_tmp",
        'tellraw @s ' + json.dumps([{"text": "[Parkour Lab] CHECKMATE! ", "color": "green", "bold": True},
                                    {"text": "Whole course in ", "color": "white"}, {"score": {"name": "#secs", "objective": "pl_tmp"}, "color": "aqua"},
                                    {"text": "s with ", "color": "white"}, {"score": {"name": "@s", "objective": "pl_falls"}, "color": "red"},
                                    {"text": " falls.", "color": "white"}]),
        "tag @s remove pl_run",
    ] + ([f"execute if entity @s[tag=pl_marathon] run function {PACK}:level/{levels[0].name}"] if levels else [])
    fn["fell"] = [
        "scoreboard players add @s pl_falls 1",
        "scoreboard players add @s pl_sfalls 1",
    ] + [f"execute if score @s pl_cp matches {cp['id']} run tp @s {cp['x'] + 0.5} {cp['y']} {cp['z'] + 0.5} -90 0" for cp in c.checkpoints]

    level_functions(fn, levels, tellraw)

    root = os.path.join(outdir, PACK)
    if os.path.exists(root):
        shutil.rmtree(root)
    os.makedirs(root)
    with open(os.path.join(root, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "Parkour Lab: a parkour test course (/function parkourlab:build)",
                            "min_format": DATA_FORMAT, "max_format": DATA_FORMAT}}, f, indent=2)
    for name, lines in fn.items():
        p = os.path.join(root, "data", PACK, "function", name + ".mcfunction")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write("\n".join(lines) + "\n")
    for tag, f_ in (("load", "load"), ("tick", "tick")):
        p = os.path.join(root, "data", "minecraft", "tags", "function", tag + ".json")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            json.dump({"values": [f"{PACK}:{f_}"]}, f)
    zpath = os.path.join(outdir, "ParkourLab.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, files in os.walk(root):
            for fname in files:
                full = os.path.join(dp, fname)
                z.write(full, os.path.relpath(full, root))
    return zpath, fn


def level_functions(fn, levels, tellraw):
    """each CubeCraft level: building it (in parts, a lot of blocks), starting it, its checkpoints, falls and finish"""
    if not levels:
        fn["build_levels"] = [tellraw("[Parkour Lab] No CubeCraft levels in this build.")]
        return
    # building, one level after another, each in parts a tick apart (a function stops at 65536 commands a tick)
    part_size = 20000
    for i, lv in enumerate(levels):
        x0, y0, z0, x1, y1, z1 = lv_bbox(lv)
        corners = [(x0, y0, z0), (x1, y0, z0), (x0, y0, z1), (x1, y0, z1), ((x0 + x1) // 2, y0, (z0 + z1) // 2)]
        loaded = " ".join(f"if loaded {cx} {cy} {cz}" for cx, cy, cz in corners)
        nxt = f"schedule function {PACK}:lvb/{i + 1} 1t" if i + 1 < len(levels) else 'tellraw @a ' + json.dumps(
            [{"text": "[Parkour Lab] ", "color": "gold"}, {"text": "The CubeCraft levels are built. ", "color": "white"},
             {"text": "[Levels]", "color": "green", "click_event": {"action": "run_command", "command": f"/function {PACK}:levels"}}])
        fn[f"lvb/{i}"] = [
            'tellraw @a ' + json.dumps({"text": f"[Parkour Lab] Building {lv.title} ({len(lv.blocks)} blocks)...", "color": "gold"}),
            f"forceload add {x0} {z0} {x1} {z1}",
            "scoreboard players set #wait pl_tmp 0",
            f"schedule function {PACK}:lvb/{i}_wait 10t",
        ]
        fn[f"lvb/{i}_wait"] = [
            "scoreboard players add #wait pl_tmp 1",
            f"execute {loaded} run return run function {PACK}:lvb/{i}_p0",
            'execute if score #wait pl_tmp matches 120.. run return run tellraw @a ' + json.dumps({"text": f"[Parkour Lab] {lv.title}: the area never loaded.", "color": "red"}),
            f"schedule function {PACK}:lvb/{i}_wait 10t",
        ]
        lines = [f"kill @e[tag=pl_lv_{lv.name}]"]
        for a, b in chunk_ranges(x0, x1, 16):
            for d, e in chunk_ranges(z0, z1, 16):
                for ya, yb in chunk_ranges(y0, y1, 120):
                    lines.append(f"fill {a} {ya} {d} {b} {yb} {e} air")
        for (xa, y, z, xb, st) in sorted(runs(lv.blocks), key=lambda r: (r[1], r[2], r[0])):
            # strict: no block updates, so a ladder or a head with nothing to hang on (CubeCraft's are like that) stays put
            lines.append(f"setblock {xa} {y} {z} {st} strict" if xa == xb else f"fill {xa} {y} {z} {xb} {y} {z} {st} strict")
        for cp in lv.checkpoints:
            lines.append(f'summon marker {cp["x"] + 0.5} {cp["y"]} {cp["z"] + 0.5} '
                         f'{{Tags:["{PACK}","pl_lv_{lv.name}","pl_lv_{lv.name}_{cp["id"]}"]}}')
            lines.append(f'scoreboard players set @e[type=marker,tag=pl_lv_{lv.name}_{cp["id"]},limit=1] pl_id {cp["id"]}')
        start, finish = lv.checkpoints[0], lv.checkpoints[-1]
        for (lx, ly, lz, text) in ((start["x"] + 0.5, start["y"] + 2.2, start["z"] + 0.5, f"{lv.title}: start"),
                                   (finish["x"] + 0.5, finish["y"] + 2.2, finish["z"] + 0.5, f"{lv.title}: finish")):
            lines.append(f'summon text_display {lx} {ly} {lz} {{Tags:["{PACK}","pl_lv_{lv.name}"],billboard:"center",'
                         f'text:{json.dumps(text)},background:1073741824,see_through:1b}}')
        lines.append(f"forceload remove {x0} {z0} {x1} {z1}")
        lines.append(nxt)
        parts = [lines[k:k + part_size] for k in range(0, len(lines), part_size)]
        for k, part in enumerate(parts):
            if k + 1 < len(parts):
                part = part + [f"schedule function {PACK}:lvb/{i}_p{k + 1} 1t"]
            fn[f"lvb/{i}_p{k}"] = part
    fn["build_levels"] = [f"function {PACK}:lvb/0"]

    # the menu
    menu = [{"text": "[Parkour Lab] CubeCraft levels: ", "color": "gold"}]
    for lv in levels:
        menu.append({"text": f"[{lv.title}] ", "color": "green", "hover_event": {"action": "show_text", "value": lv.notes or lv.title},
                     "click_event": {"action": "run_command", "command": f"/function {PACK}:level/{lv.name}"}})
    menu.append({"text": "[Marathon: the Lab, then every level]", "color": "aqua",
                 "click_event": {"action": "run_command", "command": f"/function {PACK}:marathon"}})
    fn["levels"] = ['tellraw @s ' + json.dumps(menu)]
    fn["marathon"] = [f"function {PACK}:start", "tag @s add pl_marathon",
                      tellraw("[Parkour Lab] Marathon: the Lab, then every CubeCraft level, one after the other. /function parkourlab:stop to stop.")]

    for i, lv in enumerate(levels):
        tag, mk = f"pl_on_{lv.name}", f"pl_lv_{lv.name}"
        s = lv.checkpoints[0]
        f_ = lv.checkpoints[-1]
        last = f_["id"]
        lfl = fluids_in(lv.blocks)
        fn[f"still/{lv.name}"] = still(lv_bbox(lv), lfl)
        fn[f"level/{lv.name}"] = ([f"function {PACK}:still/{lv.name}", f"schedule function {PACK}:still/{lv.name} 200t"] if lfl else []) + [
            f"function {PACK}:leave",
            "tag @s add pl_lvrun",
            f"tag @s add {tag}",
            "scoreboard players set @s pl_cp 0",
            "scoreboard players set @s pl_t 0",
            "scoreboard players set @s pl_split 0",
            "scoreboard players set @s pl_falls 0",
            "scoreboard players set @s pl_sfalls 0",
            "effect give @s saturation infinite 0 true", "effect give @s fire_resistance infinite 0 true",
            f"tp @s {cp_xz(lv, s)[0]} {cp_y(lv, s)} {cp_xz(lv, s)[1]}",
            tellraw(f"[Parkour Lab] {lv.title}: go! The finish is at {f_['x']} {f_['y']} {f_['z']}." + (f" {lv.notes}" if lv.notes else "")),
        ]
        for cp in lv.checkpoints:
            fn[f"level/{lv.name}/cp/{cp['id']}"] = [
                f"function {PACK}:leave", "tag @s add pl_lvrun", f"tag @s add {tag}",
                f"scoreboard players set @s pl_cp {cp['id']}", "scoreboard players operation @s pl_split = @s pl_t",
                "scoreboard players set @s pl_sfalls 0", "effect give @s saturation infinite 0 true", "effect give @s fire_resistance infinite 0 true",
                f"tp @s {cp_xz(lv, cp)[0]} {cp_y(lv, cp)} {cp_xz(lv, cp)[1]}",
                tellraw(f"[Parkour Lab] {lv.title}: at checkpoint {cp['id']} ({cp['name']})"),
            ]
        fn["tick"] += [
            f"execute as @e[type=marker,tag={mk}] at @s as @a[tag={tag},distance=..1.5] run function {PACK}:level/{lv.name}/at_cp",
            f"execute as @a[tag={tag}] at @s if entity @s[y=-64,dy={lv.fall_y + 64}] run function {PACK}:level/{lv.name}/fell",
            # in lava is a fall too (with fire resistance on, so it only puts you back)
            f"execute as @a[tag={tag}] at @s if block ~ ~ ~ minecraft:lava run function {PACK}:level/{lv.name}/fell",
        ] + [f"execute as @a[tag={tag}] at @s if entity @s[nbt={{OnGround:1b}}] if block ~ ~-0.2 ~ minecraft:{b} run function {PACK}:level/{lv.name}/fell"
             for b in lv.fail_blocks]
        # which checkpoint, by the marker's own tag (run at the marker): its pl_id score, set when it was summoned, isn't
        # always there (a marker summoned into a chunk that's only just been force loaded can miss the scoreboard command
        # right after, and then no checkpoint of that level ever counted)
        fn[f"level/{lv.name}/at_cp"] = ["scoreboard players set #id pl_tmp -1"] + [
            f"execute if entity @e[type=marker,tag={mk}_{cp['id']},distance=..0.1] run scoreboard players set #id pl_tmp {cp['id']}"
            for cp in lv.checkpoints] + [
            f"execute if score #id pl_tmp > @s pl_cp run function {PACK}:level/{lv.name}/new_cp",
        ]
        fn[f"level/{lv.name}/new_cp"] = [
            "scoreboard players operation @s pl_cp = #id pl_tmp",
            "scoreboard players operation @s pl_split = @s pl_t",
            "scoreboard players operation #secs pl_tmp = @s pl_t",
            "scoreboard players operation #secs pl_tmp /= #20 pl_tmp",
            'tellraw @s ' + json.dumps([{"text": f"[Parkour Lab] {lv.title}: ", "color": "gold"}, {"text": "checkpoint ", "color": "white"},
                                        {"score": {"name": "@s", "objective": "pl_cp"}, "color": "aqua"}, {"text": f" of {last} at ", "color": "white"},
                                        {"score": {"name": "#secs", "objective": "pl_tmp"}, "color": "aqua"}, {"text": "s, falls so far ", "color": "white"},
                                        {"score": {"name": "@s", "objective": "pl_falls"}, "color": "red"}]),
            "scoreboard players set @s pl_sfalls 0",
            f"execute if score @s pl_cp matches {last} run function {PACK}:level/{lv.name}/finished",
        ]
        then = f"function {PACK}:level/{levels[i + 1].name}" if i + 1 < len(levels) else "tag @s remove pl_marathon"
        fn[f"level/{lv.name}/finished"] = [
            "scoreboard players operation #secs pl_tmp = @s pl_t",
            "scoreboard players operation #secs pl_tmp /= #20 pl_tmp",
            'tellraw @s ' + json.dumps([{"text": f"[Parkour Lab] {lv.title} done! ", "color": "green", "bold": True},
                                        {"text": "In ", "color": "white"}, {"score": {"name": "#secs", "objective": "pl_tmp"}, "color": "aqua"},
                                        {"text": "s with ", "color": "white"}, {"score": {"name": "@s", "objective": "pl_falls"}, "color": "red"},
                                        {"text": " falls.", "color": "white"}]),
            f"function {PACK}:leave",
            f"execute if entity @s[tag=pl_marathon] run {then}",
        ]
        fn[f"level/{lv.name}/fell"] = [
            "scoreboard players add @s pl_falls 1",
            "scoreboard players add @s pl_sfalls 1",
        ] + [
            # back to the last checkpoint, by its coordinates (not the marker's pl_id, see at_cp)
            f"execute if score @s pl_cp matches {cp['id']} run tp @s {cp_xz(lv, cp)[0]} {cp_y(lv, cp)} {cp_xz(lv, cp)[1]}"
            for cp in lv.checkpoints
        ]



def cp_xz(lv, cp):
    """where across to put the player at a checkpoint: the middle of its cell, or if there's nothing under that (it was
    recorded standing on the edge of the block next door, the middle of you over the gap), at that edge, on the block"""
    x, y, z = cp["x"], cp["y"], cp["z"]
    def solid(st):
        return st and not st.split("[")[0] in ("water", "lava", "air") and "pressure_plate" not in st and "torch" not in st
    if solid(lv.blocks.get((x, y - 1, z), "")) or solid(lv.blocks.get((x, y, z), "")):
        return x + 0.5, z + 0.5
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
        if solid(lv.blocks.get((x + dx, y - 1, z + dz), "")):
            return x + 0.5 + dx * 0.32, z + 0.5 + dz * 0.32
    return x + 0.5, z + 0.5


def cp_y(lv, cp):
    """where the player stands at a checkpoint: on top of what's there (a level's checkpoint y is the cell the feet were
    in, and a fence post under it stands half a block up into that cell: a teleport to the cell's floor is inside the post,
    and you drop through to the post under it)"""
    x, y, z = cp["x"], cp["y"], cp["z"]
    below = lv.blocks.get((x, y - 1, z), "")
    here = lv.blocks.get((x, y, z), "")
    def name(st):
        return st.split("[")[0]
    if name(below).endswith("_fence") or name(below).endswith("_wall"):
        return y + 0.5
    if name(here).endswith("_slab") and "type=bottom" in here:
        return y + 0.5
    if name(here).endswith("_carpet"):
        return y + 0.0625
    return y

def lv_bbox(lv):
    xs = [p[0] for p in lv.blocks]
    ys = [p[1] for p in lv.blocks]
    zs = [p[2] for p in lv.blocks]
    return min(xs) - 1, max(min(ys) - 1, -64), min(zs) - 1, max(xs) + 1, min(max(ys) + 2, 319), max(zs) + 1


def course_json(c, outdir):
    data = {
        "base": BASE, "fallY": FALL_Y,
        "blocks": [[x, y, z, s] for (x, y, z), s in sorted(c.blocks.items())],
        "checkpoints": c.checkpoints,
        "sections": c.sections,
    }
    with open(os.path.join(outdir, "course.json"), "w") as f:
        json.dump(data, f)


if __name__ == "__main__":
    # gen.py [out dir] [--levels folder]: the CubeCraft levels come from a folder of their own (see levels.py)
    args = sys.argv[1:]
    lv_dir = None
    if "--levels" in args:
        k = args.index("--levels")
        lv_dir = args[k + 1]
        del args[k:k + 2]
    out = args[0] if args else os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import levels as levels_mod
    lvs = levels_mod.place(levels_mod.load(lv_dir))
    c = build()
    z, fn = datapack(c, out, lvs)
    course_json(c, out)
    if lvs:
        os.makedirs(os.path.join(out, "levels"), exist_ok=True)
        for lv in lvs:
            with open(os.path.join(out, "levels", lv.name + ".json"), "w") as f:
                json.dump(lv.course_json(), f)
            s0, s1 = lv.checkpoints[0], lv.checkpoints[-1]
            print("level", lv.name, len(lv.blocks), "blocks,", len(lv.checkpoints), "checkpoints, start", s0["x"], s0["y"], s0["z"],
                  "finish", s1["x"], s1["y"], s1["z"], "fall at or below", lv.fall_y, "moved", lv.dx, lv.dz)
    print("blocks", len(c.blocks), "commands", sum(len(v) for v in fn.values()), "->", z)
    for s in c.sections:
        print(s["id"], s["name"], len(s["jumps"]), "jumps, cp", s["from"], "->", s["to"])
    for cp in c.checkpoints:
        print("cp", cp["id"], cp["name"], cp["x"], cp["y"], cp["z"])
    print("bbox", bbox(c))
