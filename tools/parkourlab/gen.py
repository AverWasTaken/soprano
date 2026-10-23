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

    # 21. the finale: a bit of every section, one after the other, no checkpoints between. straights, diagonals, ups and
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


def datapack(c, outdir):
    x0, y0, z0, x1, y1, z1 = bbox(c)
    fn = {}

    def tellraw(text, color="gold"):
        return 'tellraw @s ' + json.dumps({"text": text, "color": color})

    fn["load"] = [
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
    for (xa, y, z, xb, s) in sorted(runs(c.blocks), key=lambda r: (r[1], r[2], r[0])):
        if xa == xb:
            lines.append(f"setblock {xa} {y} {z} {s}")
        else:
            lines.append(f"fill {xa} {y} {z} {xb} {y} {z} {s}")
    for cp in c.checkpoints:
        lines.append(f'summon marker {cp["x"] + 0.5} {cp["y"]} {cp["z"] + 0.5} '
                     f'{{Tags:["{PACK}","pl_cp","pl_cp{cp["id"]}"],Rotation:[-90f,0f]}}')
        lines.append(f'scoreboard players set @e[type=marker,tag=pl_cp{cp["id"]},limit=1] pl_id {cp["id"]}')
    for (lx, ly, lz, text) in c.labels:
        lines.append(f'summon text_display {lx} {ly} {lz} {{Tags:["{PACK}"],billboard:"center",'
                     f'text:{json.dumps(text)},background:1073741824,see_through:1b}}')
    lines.append(f"forceload remove {x0} {z0} {x1} {z1}")
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

    s = c.checkpoints[0]
    fn["start"] = [
        f"tag @s add pl_run",
        "scoreboard players set @s pl_cp 0",
        "scoreboard players set @s pl_t 0",
        "scoreboard players set @s pl_split 0",
        "scoreboard players set @s pl_falls 0",
        "scoreboard players set @s pl_sfalls 0",
        "effect give @s saturation infinite 0 true",
        f"tp @s {s['x'] + 0.5} {s['y']} {s['z'] + 0.5} -90 0",
        tellraw("[Parkour Lab] Go! Timer's running. Fall and you're back at the last checkpoint. /function parkourlab:stop to stop."),
    ]
    fn["stop"] = ["tag @s remove pl_run", "effect clear @s saturation", tellraw("[Parkour Lab] Stopped.")]

    # checkpoints you can teleport straight to (the run carries on from there, timer and all)
    for cp in c.checkpoints:
        fn[f"cp/{cp['id']}"] = [
            "tag @s add pl_run",
            f"scoreboard players set @s pl_cp {cp['id']}",
            "scoreboard players operation @s pl_split = @s pl_t",
            "scoreboard players set @s pl_sfalls 0",
            "effect give @s saturation infinite 0 true",
            f"tp @s {cp['x'] + 0.5} {cp['y']} {cp['z'] + 0.5} -90 0",
            tellraw(f"[Parkour Lab] At checkpoint {cp['id']}: {cp['name']}"),
        ]

    fn["tick"] = [
        "scoreboard players add @a[tag=pl_run] pl_t 1",
        f"execute as @e[type=marker,tag=pl_cp] at @s as @a[tag=pl_run,distance=..2.3] run function {PACK}:at_cp",
        f"execute as @a[tag=pl_run] at @s if entity @s[y=-64,dy={FALL_Y + 64}] run function {PACK}:fell",
        f"execute as @a[tag=pl_run] run function {PACK}:hud",
    ]
    fn["hud"] = [
        "scoreboard players operation #s pl_tmp = @s pl_t",
        "scoreboard players operation #s pl_tmp /= #20 pl_tmp",
        'title @s actionbar ' + json.dumps([{"text": "Checkpoint ", "color": "gray"}, {"score": {"name": "@s", "objective": "pl_cp"}, "color": "white"},
                                            {"text": "   Time ", "color": "gray"}, {"score": {"name": "#s", "objective": "pl_tmp"}, "color": "white"},
                                            {"text": "s   Falls ", "color": "gray"}, {"score": {"name": "@s", "objective": "pl_falls"}, "color": "white"}]),
    ]
    fn["at_cp"] = [
        "scoreboard players operation #id pl_tmp = @e[type=marker,tag=pl_cp,limit=1,sort=nearest] pl_id",
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
    ]
    fn["fell"] = [
        "scoreboard players add @s pl_falls 1",
        "scoreboard players add @s pl_sfalls 1",
        "tag @s add pl_falling",
        "execute as @e[type=marker,tag=pl_cp] if score @s pl_id = @p[tag=pl_falling] pl_cp at @s run tp @p[tag=pl_falling] ~ ~ ~ -90 0",
        "tag @s remove pl_falling",
    ]

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
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    c = build()
    z, fn = datapack(c, out)
    course_json(c, out)
    print("blocks", len(c.blocks), "commands", sum(len(v) for v in fn.values()), "->", z)
    for s in c.sections:
        print(s["id"], s["name"], len(s["jumps"]), "jumps, cp", s["from"], "->", s["to"])
    for cp in c.checkpoints:
        print("cp", cp["id"], cp["name"], cp["x"], cp["y"], cp["z"])
    print("bbox", bbox(c))
