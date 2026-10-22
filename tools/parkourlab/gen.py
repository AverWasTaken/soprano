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
                    "a hopper, a top trapdoor and an end rod.", "TUNGSTEN")
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
    c.end_section(3, 0, 0, "8: Fence run")

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
    c.end_section(3, 0, 0, "9: Ladders")

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
    c.end_section(3, 0, 0, "10: Ladder neos")

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
    c.end_section(3, 0, 0, "11: Ice palace")

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
    c.end_section(3, 0, 0, "12: Sky tower")

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
    c.end_section(3, 0, 0, "13: Slime factory")

    # 14. chess. the hard one: down onto the pawns, diagonal captures across them, a knight's tour climbing up the
    # knights, the bishop's diagonal, along the rook's battlements, a neo round the queen, and up the king's ladder to the
    # top. the pieces stand on a board thirty blocks down. fall off and it's back to the checkpoint
    c.start_section("Chess", "Pawn captures (diagonals) down on the board, a knight's tour climbing up, the bishop's "
                    "diagonal, the rook's battlements, a neo round the queen and up the king's ladder.", "THE BOSS")
    chess_start = c.cur
    route = []  # (x, y, z feet, piece)
    def j(dx, dy, dz, piece):
        nx, ny, nz = c.cur[0] + dx, c.cur[1] + dy, c.cur[2] + dz
        colour = "white" if len(route) % 2 == 0 else "black"
        block = "white_concrete" if colour == "white" else "black_concrete"
        c.jump(dx, dy, dz, "quartz_block" if colour == "white" else "polished_blackstone", kind=piece)
        route.append((nx, ny, nz, piece, colour))
    # pawns: down a block, then captures
    j(3, -1, 3, "pawn")
    j(3, 0, -3, "pawn")
    j(3, 0, 3, "pawn")
    j(3, 0, -3, "pawn")
    # knights: knight's moves, climbing
    j(2, 1, 1, "knight")
    j(1, 1, 2, "knight")
    j(2, 0, -1, "knight")
    j(2, 1, -1, "knight")
    # bishop: the long diagonal
    j(2, 1, 2, "bishop")
    j(2, 0, 2, "bishop")
    j(3, 0, 3, "bishop")
    # rook: along the battlements
    j(3, 0, 0, "rook")
    j(2, 0, 0, "rook")
    j(0, 0, -2, "rook")
    j(2, 0, 0, "rook")
    # the queen: a tall wall to neo round, with a bit of the rook's wall to run up on
    c.walk(2, "quartz_block")
    for i in (1, 2):
        route.append((c.cur[0] - 2 + i, c.cur[1], c.cur[2], "rook", "white"))
    x, y, z = c.cur
    c.put(x, y - 1, z - 1, "quartz_block")
    route.append((x, y, z - 1, "queen-step", "white"))
    queen_base = y
    for wz in range(z - 3, z + 1):
        for wy in range(BASE - 30, y + 4):
            c.put(x + 1, wy, wz, "white_concrete" if (wy + wz) % 2 == 0 else "quartz_pillar")
    j(2, 0, 0, "queen")
    # the king: a ladder up the side of a 3x3 tower to its top, the finish
    x, y, z = c.cur
    for kx in range(x + 3, x + 6):
        for kz in range(z - 1, z + 2):
            for ky in range(BASE - 30, y + 5):
                c.put(kx, ky, kz, "polished_blackstone_bricks")
    for ly in range(y, y + 5):
        c.put(x + 2, ly, z, "ladder[facing=west]")
    c.jumps.append({"from": [x, y, z], "to": [x + 3, y + 5, z], "kind": "king-ladder", "block": "ladder"})
    c.sections[-1]["to"] = None
    finish = {"id": len(c.checkpoints), "x": x + 4, "y": y + 5, "z": z, "name": "Checkmate"}
    for kx in range(x + 3, x + 6):
        for kz in range(z - 1, z + 2):
            c.put(kx, y + 4, kz, "emerald_block")
    c.checkpoints.append(finish)
    c.labels.append((x + 4.5, y + 7.2, z + 0.5, "Checkmate"))
    c.sections[-1]["to"] = finish["id"]

    # the pieces: a column from the board up to every route block, with a bit of shape at its foot
    # the board is far down, so falling onto it is a long fall nobody plans (and the fall reset catches you on the way)
    board_top = BASE - 30
    xs = [r[0] for r in route] + [x + 5]
    zs = [r[2] for r in route] + [z + 1]
    bx0 = (min(xs) - 3) - ((min(xs) - 3 - OX) % 3)
    bz0 = (min(zs) - 3) - ((min(zs) - 3 - OZ) % 3)
    files = max(8, math.ceil((max(xs) + 3 - bx0 + 1) / 3))
    ranks = max(8, math.ceil((max(zs) + 3 - bz0 + 1) / 3))
    for f in range(files):
        for r in range(ranks):
            block = "white_concrete" if (f + r) % 2 == 0 else "black_concrete"
            for i in range(3):
                for k in range(3):
                    c.put(bx0 + 3 * f + i, board_top - 1, bz0 + 3 * r + k, block, overwrite=False)
    for (rx, ry, rz, piece, colour) in route:
        body = "quartz_block" if colour == "white" else "polished_blackstone"
        for py in range(board_top, ry - 1):
            c.put(rx, py, rz, body, overwrite=False)
        # a wider foot, two blocks high, so it reads as a piece standing on the board
        if piece in ("pawn", "knight", "bishop", "rook"):
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx or dz:
                        c.put(rx + dx, board_top, rz + dz, body, overwrite=False)
    c.chess = {"board": [bx0, board_top - 1, bz0, bx0 + 3 * files - 1, bz0 + 3 * ranks - 1], "route": route}
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

    bx0, by, bz0, bx1, bz1 = c.chess["board"]
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
