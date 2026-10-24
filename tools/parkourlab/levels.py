"""
The CubeCraft levels in Parkour Lab: whole levels rebuilt block for block from #record recordings, each a course of its own
next to the Lab (its own start, checkpoints, finish, timer and falls).

The levels come from a folder (not from this repo: they're CubeCraft's maps) holding levels.json and a course file per level,
made from a recording with `replay.py course run.zip level.json`:

    levels.json   [{"name": "aqua", "title": "Aqua", "course": "aqua.json", "notes": "...",
                    "fall_y": 30, "fail_blocks": ["sand"]}, ...]

fall_y is the level's floor (standing at or below it is a fall), fail_blocks are blocks that count as a fall to stand on.
Each level is moved to sit in a row south of the Lab (same heights as on the server), so the whole lot is one world.
"""
import json
import os

GAP = 48          # between one level's blocks and the next one's
ROW_Z = 1120      # the row of levels starts this far south (the Lab is z 987 to 1028)
ROW_X = 1000
MAX_ROW_X = 1800  # past this, a new row further south


# blocks with nothing to bump into: a cell holding one of these is somewhere to stand in
NO_COLLISION = ("torch", "pressure_plate", "button", "short_grass", "tall_grass", "fern", "flower", "dandelion", "poppy",
                "orchid", "allium", "tulip", "daisy", "cornflower", "lily_of", "sign", "banner", "rail", "lever", "tripwire",
                "dead_bush", "sapling", "mushroom", "light", "redstone_wire", "vine", "seagrass", "kelp", "carpet")


def free(state):
    name = (state or "air").split("[")[0]
    # (air by its whole name: "stairs" has "air" in it)
    return name in ("air", "cave_air", "void_air") or any(k in name for k in NO_COLLISION)


class Level:
    def __init__(self, name, title, notes, course, fall_y=None, fail_blocks=()):
        self.name = name
        self.title = title
        self.notes = notes
        self.src = course
        self.fixed_fall_y = fall_y
        self.fail_blocks = list(fail_blocks)
        self.dx = self.dz = 0
        self.blocks = {}
        self.checkpoints = []
        self.sections = []
        self.fall_y = 0

    def bounds(self):
        xs = [b[0] for b in self.src["blocks"]]
        zs = [b[2] for b in self.src["blocks"]]
        return min(xs), min(zs), max(xs), max(zs)

    def move(self, dx, dz):
        self.dx, self.dz = dx, dz
        # (leaves as they were, kept: a rebuilt level has no logs where the server's did, and they'd rot away)
        self.blocks = {(b[0] + dx, b[1], b[2] + dz): b[3].replace("persistent=false", "persistent=true") for b in self.src["blocks"]}
        # fallen: at or below fall_y (the level's floor, where CubeCraft puts you back), or standing on one of its fail blocks.
        # without one given, a little under the lowest checkpoint
        self.fall_y = self.fixed_fall_y if self.fixed_fall_y is not None else min(cp["y"] for cp in self.src["checkpoints"]) - 2
        blocks = self.blocks

        def fallen(x, y, z):
            under = blocks.get((x, y - 1, z), "").split("[")[0]
            return y <= self.fall_y or under in self.fail_blocks

        def post(state):
            name = (state or "air").split("[")[0]
            return any(k in name for k in ("fence", "_wall", "iron_bars", "pane", "chain"))

        def rim(x, y, z):
            # nothing under it, but next to a block with a post on it: you stand on that block's rim, outside the post
            # (TungstenJumps.rimNode), and that's this cell as far as the bot is concerned
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                b = blocks.get((x + dx, y - 1, z + dz)) or "air"
                if not free(b) and not post(b) and "slab" not in b and post(blocks.get((x + dx, y, z + dz))):
                    return True
            return False

        def standable(x, y, z):
            under = blocks.get((x, y - 1, z)) or "air"
            if not (free(blocks.get((x, y, z))) and free(blocks.get((x, y + 1, z)))):
                return False
            if free(under):
                return rim(x, y, z)
            return not under.startswith(("lava", "water"))

        def snap(p):
            # a spot on the rim of a block with a post on it (a fence, a wall) counts the player into the post's cell, which
            # the planner can't stand in: the nearest cell next to it that it can
            x, y, z = p
            if standable(x, y, z) or y <= self.fall_y:
                return [x, y, z]
            best = None
            for ddy in (0, 1, -1):
                for ddx in (-1, 0, 1):
                    for ddz in (-1, 0, 1):
                        if standable(x + ddx, y + ddy, z + ddz):
                            d = ddx * ddx + ddz * ddz + 2 * ddy * ddy
                            if best is None or d < best[0]:
                                best = (d, [x + ddx, y + ddy, z + ddz])
            return best[1] if best else [x, y, z]

        # checkpoints and jumps only where the route really goes: not down on the floor after a fall
        cps = []
        for cp in self.src["checkpoints"]:
            x, y, z = snap((cp["x"] + dx, cp["y"], cp["z"] + dz))
            cps.append(dict(cp, x=x, y=y, z=z))
        self.checkpoints = [dict(cp, id=k) for k, cp in enumerate(c for c in cps if not fallen(c["x"], c["y"], c["z"]))]
        jumps = []
        for s in self.src["sections"]:
            for j in s["jumps"]:
                a = snap((j["from"][0] + dx, j["from"][1], j["from"][2] + dz))
                b = snap((j["to"][0] + dx, j["to"][1], j["to"][2] + dz))
                if j.get("kind") == "recorded ladder":
                    b = [j["to"][0] + dx, j["to"][1], j["to"][2] + dz]  # a ladder cell, caught in mid air: not one to stand in
                if not fallen(*a) and not fallen(*b):
                    jumps.append(dict(j, **{"from": a, "to": b}))
        self.sections = []
        for k in range(1, len(self.checkpoints)):
            t0, t1 = self.checkpoints[k - 1]["tick"], self.checkpoints[k]["tick"]
            self.sections.append({"id": k, "name": f"{self.title}: {self.checkpoints[k - 1]['name']} to {self.checkpoints[k]['name']}",
                                  "from": k - 1, "to": k, "notes": "", "expect": "TUNGSTEN",
                                  "jumps": [j for j in jumps if t0 <= j.get("tick", 0) < t1]})

    def course_json(self):
        return {"base": self.fall_y + 2, "fallY": self.fall_y, "failBlocks": self.fail_blocks,
                "blocks": [[x, y, z, s] for (x, y, z), s in sorted(self.blocks.items())],
                "checkpoints": self.checkpoints, "sections": self.sections,
                "source": dict(self.src.get("source", {}), level=self.name, moved=[self.dx, 0, self.dz])}


def load(folder):
    if not folder:
        return []
    with open(os.path.join(folder, "levels.json")) as f:
        manifest = json.load(f)
    out = []
    for m in manifest:
        with open(os.path.join(folder, m["course"])) as f:
            out.append(Level(m["name"], m["title"], m.get("notes", ""), json.load(f), m.get("fall_y"), m.get("fail_blocks", ())))
    return out


def place(levels):
    """in rows south of the Lab, left to right, GAP apart"""
    x, z, row_depth = ROW_X, ROW_Z, 0
    for lv in levels:
        x0, z0, x1, z1 = lv.bounds()
        w, d = x1 - x0 + 1, z1 - z0 + 1
        if x > ROW_X and x + w > MAX_ROW_X:
            x, z, row_depth = ROW_X, z + row_depth + GAP, 0
        lv.move(x - x0, z - z0)
        x += w + GAP
        row_depth = max(row_depth, d)
    return levels
