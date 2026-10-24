#!/usr/bin/env python3
"""Look at, test with and rebuild a Soprano recording (#record start / #record stop, saved in .minecraft/baritone/recordings).

  replay.py summary  <recording.zip>                         what's in it: time, worlds, every jump, falls, marks, Soprano's chat
  replay.py render   <recording.zip> <out dir> [options]     pictures: the map from above with the run drawn on it, and a 3D close-up
                                                             of each jump with the player's flight through it
  replay.py course   <recording.zip> <course.json> [options] the map and the run as a course for ParkourLabScorecard: the start,
                                                             each mark and the end are checkpoints, every recorded jump is a test
  replay.py datapack <recording.zip> <rebuild.zip> [options] a data pack that builds the map in any world: drop it in the world's
                                                             datapacks folder, /reload, /function replay:build

options: --world N         which world (a server can send you through several: lobby, game, lobby). default: the one you spent
                           longest in
         --ticks A-B       only this part of the run (render, course)
         --jumps A-B       which jumps get a close-up (render, default 1-24)
         --offset X Y Z    build the map moved by this much (datapack)
         --every N         a checkpoint every N jumps as well as at the marks (course, default 8, 0 for none)
"""
import json
import math
import os
import sys
import zipfile
from collections import Counter, defaultdict


def load(path):
    z = zipfile.ZipFile(path)
    names = {os.path.basename(n): n for n in z.namelist()}
    meta = json.loads(z.read(names["meta.json"]))
    blocks = json.loads(z.read(names["blocks.json"]))
    header, *rows = z.read(names["trace.csv"]).decode().splitlines()
    cols = header.split(",")
    trace = []
    for r in rows:
        v = r.split(",")
        if len(v) != len(cols):
            continue
        t = dict(zip(cols, v))
        for k in ("tick", "ms", "world", "onGround", "hCollision", "sprinting", "sneaking", "forward", "back", "left", "right",
                  "jump", "shift", "sprintKey", "pathIndex", "pathSize", "climbing"):
            if k in t:
                t[k] = int(t[k])
        for k in ("x", "y", "z", "vx", "vy", "vz", "yaw", "pitch", "health"):
            t[k] = float(t[k])
        t.setdefault("world", 0)
        trace.append(t)
    events = []
    for line in z.read(names["events.txt"]).decode(errors="replace").splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3:
            events.append((int(parts[0]), int(parts[1]), parts[2]))
    pal = blocks["palette"]
    bl = [(b[0], b[1], b[2], pal[b[3]], b[4] if len(b) > 4 else 0) for b in blocks["blocks"]]
    worlds = blocks.get("worlds") or [meta.get("dimension", "world")]
    INDEX.clear()
    STATES.clear()
    for x, y, z, st, ww in bl:
        INDEX[ww].add((x, y, z))
        STATES[ww][(x, y, z)] = st
    return meta, worlds, bl, trace, events


def args_opt(argv, name, n=1, default=None):
    if name in argv:
        i = argv.index(name)
        v = argv[i + 1:i + 1 + n]
        return v if n > 1 else v[0]
    return default


def span(s, default):
    if s is None:
        return default
    a, _, b = s.partition("-")
    return int(a), int(b or a)


def pick_world(argv, trace):
    w = args_opt(argv, "--world")
    if w is not None:
        return int(w)
    c = Counter(t["world"] for t in trace)
    return c.most_common(1)[0][0] if c else 0


def short(state):
    return state.replace("minecraft:", "")


# the blocks of each world, by position, for feet()
INDEX = defaultdict(set)
STATES = defaultdict(dict)
# what you stand inside the cell of when you're on it, which Baritone counts as standing in the cell above (playerFeet):
# bottom slabs, bottom stairs (on the low step), and the small things parkour maps put about, heads, flower pots, candles...
HALF_FLOORS = ("_head", "_skull", "flower_pot", "potted_", "candle", "lantern", "sea_pickle", "turtle_egg", "daylight_detector", "cake")


def half_floor(state):
    name = state.split("[")[0]
    if name.endswith("_slab"):
        return "type=bottom" in state
    if name.endswith("_stairs"):
        return "half=bottom" in state
    return any(k in name for k in HALF_FLOORS)


def feet(t):
    """the cell the player stands in, Baritone's way (IPlayerContext.playerFeet: the floor of y + 0.1251, one up when that
    cell is a bottom slab, a stair's low step or a head), over the block that holds it up (at a takeoff from the very edge,
    the middle of the player is already over the gap)"""
    fy = math.floor(t["y"] + 1e-3)
    by = math.floor(t["y"] + 0.1251)
    cx, cz = math.floor(t["x"]), math.floor(t["z"])
    index = INDEX.get(t.get("world", 0))
    states = STATES.get(t.get("world", 0), {})

    def cell(x, z):
        return (x, by + (1 if half_floor(states.get((x, by, z), "")) else 0), z)

    if not index or (cx, fy - 1, cz) in index or (cx, fy, cz) in index:
        return cell(cx, cz)
    # the game settles the fall onto the floor before the step sideways, so on the last tick on the ground the player can
    # be a whole tick's run (up to a third of a block) past the edge as well as half its width
    best = None
    for x in range(math.floor(t["x"] - 0.65), math.floor(t["x"] + 0.65) + 1):
        for z in range(math.floor(t["z"] - 0.65), math.floor(t["z"] + 0.65) + 1):
            if (x, fy - 1, z) in index or (x, fy, z) in index:
                d = (x + 0.5 - t["x"]) ** 2 + (z + 0.5 - t["z"]) ** 2
                if best is None or d < best[0]:
                    best = (d, x, z)
    if not best:
        return (cx, by, cz)
    return cell(best[1], best[2])


def jumps(trace):
    """every time the player left the ground and came down again: (takeoff row, landing row, jumped). grabbing a ladder or
    a vine is a landing too"""
    out = []
    i = 0
    n = len(trace)
    climbs = "climbing" in trace[0] if trace else False

    def landed(j):
        t = trace[j]
        if climbs and t["climbing"] and not trace[j - 1].get("climbing", 0):
            return True
        if not t["onGround"]:
            return False
        # straight back up off slime: a bounce, part of the same flight, unless it's the next jump. what Soprano was
        # doing says which (the same move from takeoff to here is one jump); without it, a bounce with no jump key is
        if j + 1 < n and trace[j + 1]["vy"] > 0.25 and not trace[j + 1]["onGround"]:
            move = (t.get("src"), t.get("dest"))
            if move[0]:
                return move != (trace[start[0]].get("src"), trace[start[0]].get("dest"))
            return bool(t["jump"] or trace[j + 1]["jump"])
        return True

    start = [0]
    while i < n - 1:
        start[0] = i
        if trace[i]["onGround"] and not trace[i + 1]["onGround"] and trace[i + 1]["world"] == trace[i]["world"] \
                and not (climbs and trace[i]["climbing"]):
            j = i + 1
            while j < n and not landed(j) and trace[j]["world"] == trace[i]["world"] and not teleported(trace, j) and j - i < 200:
                j += 1
            if j >= n or trace[j]["world"] != trace[i]["world"] or teleported(trace, j) or j - i >= 200:
                i = j
                continue
            a, b = trace[i], trace[j]
            d = math.hypot(b["x"] - a["x"], b["z"] - a["z"])
            jumped = any(t["jump"] for t in trace[max(0, i - 1):i + 2]) or trace[i + 1]["vy"] > 0.2
            # stepping off a block onto the next one along isn't a jump: a hop of at least a block and a half, or up/down a block
            if j - i >= 4 and (d >= 1.5 or abs(feet(b)[1] - feet(a)[1]) >= 1) and (jumped or d >= 1.5):
                out.append((i, j, jumped))
            # a ladder grabbed: on to the next time it's on the ground
            while climbs and j < n - 1 and not trace[j]["onGround"]:
                j += 1
            i = j
        else:
            i += 1
    return out


def flown(trace, i, j):
    """whether the player was flying (practice mode, creative) somewhere between takeoff i and landing j: falling only ever
    gets faster, so a tick in the air going up faster, or coming down slower, than the one before (and not just off a
    bounce or a ladder) is someone flying"""
    for k in range(i + 2, j):
        a, b = trace[k - 1], trace[k]
        if a["onGround"] or b["onGround"] or a.get("climbing") or b.get("climbing"):
            continue
        if b["vy"] > a["vy"] + 0.005:
            return True
    return False


def teleported(trace, j):
    if j == 0:
        return False
    a, b = trace[j - 1], trace[j]
    return a["world"] != b["world"] or abs(b["x"] - a["x"]) + abs(b["y"] - a["y"]) + abs(b["z"] - a["z"]) > 6


def falls(trace):
    """teleports back (a checkpoint on a parkour server, or Parkour Lab's) and long drops"""
    out = []
    for j in range(1, len(trace)):
        if teleported(trace, j) and trace[j]["world"] == trace[j - 1]["world"]:
            out.append((j, "teleported"))
    return out


def solid_below(index, x, y, z):
    for yy in range(y, y - 3, -1):
        if (x, yy, z) in index:
            return True
    return False


# --- summary ----------------------------------------------------------------------------------------------------------

def summary(path, argv):
    meta, worlds, bl, trace, events = load(path)
    print(f"{meta.get('name')}: {meta.get('server')}, {len(trace)} ticks ({trace[-1]['ms'] / 1000 if trace else 0:.1f} s), "
          f"{len(bl)} blocks, {len(worlds)} world(s)")
    wc = Counter(t["world"] for t in trace)
    for i, w in enumerate(worlds):
        print(f"  world {i}: {w}, {wc.get(i, 0)} ticks, {sum(1 for b in bl if b[4] == i)} blocks")
    js = jumps(trace)
    print(f"\n{len(js)} jumps (takeoff -> landing, ticks in the air, what Soprano was doing):")
    for k, (i, j, jumped) in enumerate(js, 1):
        a, b = trace[i], trace[j]
        fa, fb = feet(a), feet(b)
        d = (fb[0] - fa[0], fb[1] - fa[1], fb[2] - fa[2])
        mv = a.get("movement") or "-"
        print(f"  {k:3d}. tick {a['tick']:6d}  {fa} -> {fb}  ({d[0]:+d},{d[1]:+d},{d[2]:+d})  {j - i:2d} ticks  "
              f"{'jump' if jumped else 'fall'}  {mv} {a.get('src', '')}>{a.get('dest', '')}")
    fl = falls(trace)
    if fl:
        print(f"\n{len(fl)} teleports back (falls):")
        for j, why in fl:
            print(f"  tick {trace[j]['tick']}: {feet(trace[j - 1])} -> {feet(trace[j])}")
    marks = [e for e in events if e[2].startswith("mark")]
    if marks:
        print("\nmarks:")
        for t, ms, text in marks:
            print(f"  tick {t}: {text[5:]}")
    print("\nSoprano said (jump trouble, paths that failed):")
    keys = ("tungsten", "off the plan", "No path", "fell", "taken too long", "Section done", "couldn't", "Cancel", "stuck")
    seen = Counter()
    for t, ms, text in events:
        if text.startswith("chat ") and any(k in text for k in keys):
            seen[text[5:]] += 1
    for line, c in seen.most_common(30):
        print(f"  {c:4d}x {line}")


# --- course ------------------------------------------------------------------------------------------------------------

def course(path, out, argv):
    meta, worlds, bl, trace, events = load(path)
    w = pick_world(argv, trace)
    lo, hi = span(args_opt(argv, "--ticks"), (0, 1 << 60))
    tr = [t for t in trace if t["world"] == w and lo <= t["tick"] <= hi]
    blocks = [[x, y, z, short(s)] for (x, y, z, s, ww) in bl if ww == w]
    index = {(b[0], b[1], b[2]) for b in blocks}
    grounded = [t for t in tr if t["onGround"]]
    columns = defaultdict(list)
    for (x, y, z) in index:
        columns[(x, z)].append(y)

    def dropped(tk, mx, my, mz, note):
        # a mark made in the air (flying round the map): the top of the block under it
        ys = [y for y in columns.get((math.floor(mx), math.floor(mz)), ()) if y < my]
        if not ys:
            return None
        return {"tick": tk, "x": math.floor(mx) + 0.5, "y": max(ys) + 1.0, "z": math.floor(mz) + 0.5, "onGround": 1, "world": w,
                "note": note}

    # checkpoints: where it started, each mark (where you stood, or the block under you if you were in the air), and where
    # it ended
    stops = [grounded[0]] if grounded else []
    for tk, ms, text in events:
        if text.startswith("mark") and lo <= tk <= hi:
            parts = text.split(" ")
            note = " ".join(parts[4:])
            near = min(grounded, key=lambda t: abs(t["tick"] - tk)) if grounded else None
            if near is not None and abs(near["tick"] - tk) <= 2:
                stop = dict(near, note=note)
            else:
                try:
                    stop = dropped(tk, float(parts[1]), float(parts[2]), float(parts[3]), note)
                except (ValueError, IndexError):
                    stop = None
            if stop is not None and (not stops or feet(stop) != feet(stops[-1])):
                stops.append(stop)
    if grounded and feet(grounded[-1]) != feet(stops[-1]) and grounded[-1]["tick"] > stops[-1]["tick"]:
        stops.append(grounded[-1])
    if len(stops) < 2:
        sys.exit("nothing to go between: never on the ground, and fewer than two marks (#record mark) to stand in for it")
    # and a checkpoint every so many jumps in between (where one comes down on solid ground), so a long stretch is a few
    # sections the planner does one at a time, and a failure says roughly where
    every = int(args_opt(argv, "--every", 1, "8"))
    if every > 0:
        landings = [tr[j] for i, j, jumped in jumps(tr) if tr[j]["onGround"]]
        more = []
        for a_, b_ in zip(stops, stops[1:]):
            inside = [t for t in landings if a_["tick"] < t["tick"] < b_["tick"]]
            more.append(a_)
            more.extend(inside[every - 1::every][:-1] if len(inside) > every else [])
        more.append(stops[-1])
        stops = more
    # a stretch done over (a fall that put you back at the start, another go at a jump): the checkpoints along the first go
    # are near ones along the last, and only the last ones count (a course's checkpoints have to come in order)
    kept = []
    for t in stops:
        x, y, z = feet(t)
        kept = [k for k in kept if not (abs(feet(k)[0] - x) <= 3 and abs(feet(k)[1] - y) <= 2 and abs(feet(k)[2] - z) <= 3)] + [t]
    if len(kept) >= 2:
        stops = kept
    cps = []
    for k, t in enumerate(stops):
        x, y, z = feet(t)
        cps.append({"id": k, "x": x, "y": y, "z": z, "name": t.get("note") or ("start" if k == 0 else f"stop {k}"), "tick": t["tick"]})
    js = jumps(tr)
    sections = []
    for k in range(1, len(cps)):
        t0, t1 = cps[k - 1]["tick"], cps[k]["tick"]
        sj = []
        for i, j, jumped in js:
            if t0 <= tr[i]["tick"] < t1:
                if flown(tr, i, j):
                    continue  # flew there (practice mode): not a jump to test
                fa, fb = feet(tr[i]), feet(tr[j])
                if not solid_below(index, *fa):
                    continue
                under = next((b for b in blocks if (b[0], b[1], b[2]) in ((fb[0], fb[1], fb[2]), (fb[0], fb[1] - 1, fb[2]))), None)
                grab = bool(tr[j].get("climbing")) and not tr[j]["onGround"]
                if grab:
                    # where on the ladder: the cell Soprano was going for, if it was Soprano, or the one it caught at
                    dest = (tr[i].get("dest") or "").split()
                    fb = tuple(int(v) for v in dest) if len(dest) == 3 else (math.floor(tr[j]["x"]), math.floor(tr[j]["y"] + 0.5), math.floor(tr[j]["z"]))
                elif not solid_below(index, *fb):
                    continue
                sj.append({"from": list(fa), "to": list(fb), "kind": "recorded ladder" if grab else "recorded", "tick": tr[i]["tick"],
                           "block": under[3].split("[")[0] if under else "?"})
        sections.append({"id": k, "name": f"recording {cps[k - 1]['name']} to {cps[k]['name']}", "from": k - 1, "to": k,
                         "notes": f"from {meta.get('name')}, ticks {t0}-{t1}", "expect": "TUNGSTEN", "jumps": sj})
    ys = [t["y"] for t in tr]
    data = {"base": int(min(ys)), "fallY": int(min(ys)) - 6, "blocks": blocks, "checkpoints": cps, "sections": sections,
            "source": {"recording": meta.get("name"), "server": meta.get("server"), "world": worlds[w] if w < len(worlds) else w}}
    with open(out, "w") as f:
        json.dump(data, f)
    print(f"{out}: {len(blocks)} blocks, {len(cps)} checkpoints, {sum(len(s['jumps']) for s in sections)} jumps")


# --- render ------------------------------------------------------------------------------------------------------------

COLORS = [
    ("emerald_block", "#2ecc71"), ("gold_block", "#f5c542"), ("snow", "#f2f6f8"), ("blue_ice", "#5b93f0"), ("packed_ice", "#8db4f0"),
    ("ice", "#a9cbf7"), ("purpur", "#a77ba7"), ("slime", "#77c84f"), ("honey", "#e8a33c"), ("glass", "#cfe8ef"),
    ("leaves", "#3f7a2f"), ("grass_block", "#6aa84f"), ("dirt", "#866043"), ("sand", "#dccf9a"), ("water", "#3f76e4"),
    ("lava", "#e25822"), ("quartz", "#ece6dc"), ("white", "#f0f0f0"), ("black", "#1b1b22"), ("red", "#b03030"),
    ("orange", "#d87f33"), ("yellow", "#e5c535"), ("lime", "#7fcc19"), ("green", "#4b6b23"), ("cyan", "#4c7f86"),
    ("light_blue", "#6699d8"), ("blue", "#334cb2"), ("purple", "#7f3fb2"), ("magenta", "#b24cd8"), ("pink", "#f27fa5"),
    ("brown", "#664c33"), ("gray", "#4c4c4c"), ("terracotta", "#9e6a52"), ("dark_oak", "#4a3420"), ("spruce", "#6b4e2e"),
    ("birch", "#d7c185"), ("oak", "#b48a52"), ("ladder", "#a8803e"), ("vine", "#3f8f2f"), ("deepslate", "#4a4a52"),
    ("blackstone", "#2c272b"), ("andesite", "#8f9494"), ("diorite", "#c8c8c8"), ("granite", "#9a6b5a"), ("prismarine", "#5aa59a"),
    ("stone_bricks", "#7f7f7f"), ("smooth_stone", "#a0a0a0"), ("cobblestone", "#7a7a7a"), ("stone", "#8a8a8a"),
    ("iron", "#d8d8d8"), ("copper", "#c0704a"), ("wool", "#e0e0e0"), ("concrete", "#9a9a9a"), ("barrier", "#ff000000"),
]


def color(state):
    s = short(state).split("[")[0]
    base = "#b0b0b0"
    for k, v in COLORS:
        if k in s:
            base = v
            break
    # stairs and slabs tinted, so a dark stair doesn't pass for a block: orange for stairs, blue for slabs
    tint = "#ff9a30" if s.endswith("_stairs") else "#3fa9ff" if s.endswith("_slab") else None
    if tint is None:
        return base
    b = [int(base[i:i + 2], 16) for i in (1, 3, 5)]
    t = [int(tint[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join("%02x" % int(0.45 * x + 0.55 * y) for x, y in zip(b, t))


def props(state):
    s = short(state)
    if "[" not in s:
        return s, {}
    name, rest = s.split("[", 1)
    return name, dict(kv.split("=", 1) for kv in rest.rstrip("]").split(",") if "=" in kv)


NO_COLLISION = ("grass", "fern", "flower", "torch", "sapling", "button", "lever", "sign", "banner", "rail", "wire",
                "tripwire", "string", "water", "lava", "light", "barrier", "structure_void", "kelp", "seagrass", "vine",
                "dandelion", "poppy", "tulip", "orchid", "allium", "bluet", "daisy", "cornflower", "lily_of", "rose", "bush",
                "mushroom", "pressure_plate", "carpet_x")


def shapes(state):
    """the block's collision boxes in its cell, (x0, y0, z0, x1, y1, z1), the way the game has them (near enough: stair
    corners and the like are left square). vines and ladders get a thin slab so they show"""
    name, p = props(state)
    if name.endswith("ladder"):
        f = p.get("facing", "north")
        return [{"east": (0, 0, 0, .1875, 1, 1), "west": (.8125, 0, 0, 1, 1, 1), "south": (0, 0, 0, 1, 1, .1875),
                 "north": (0, 0, .8125, 1, 1, 1)}[f]]
    if name == "vine":
        out = []
        for side, box in (("east", (.9375, 0, 0, 1, 1, 1)), ("west", (0, 0, 0, .0625, 1, 1)), ("south", (0, 0, .9375, 1, 1, 1)),
                          ("north", (0, 0, 0, 1, 1, .0625))):
            if p.get(side) == "true":
                out.append(box)
        return out
    # (air by its whole name: "stairs" has "air" in it)
    if name in ("air", "cave_air", "void_air") or any(k in name for k in NO_COLLISION):
        return []
    if name.endswith("_slab"):
        t = p.get("type", "bottom")
        return [(0, 0, 0, 1, .5, 1)] if t == "bottom" else [(0, .5, 0, 1, 1, 1)] if t == "top" else [(0, 0, 0, 1, 1, 1)]
    if name.endswith("_stairs"):
        top = p.get("half") == "top"
        base = (0, .5, 0, 1, 1, 1) if top else (0, 0, 0, 1, .5, 1)
        y0, y1 = (0, .5) if top else (.5, 1)
        f = p.get("facing", "north")
        step = {"east": (.5, y0, 0, 1, y1, 1), "west": (0, y0, 0, .5, y1, 1), "south": (0, y0, .5, 1, y1, 1),
                "north": (0, y0, 0, 1, y1, .5)}[f]
        return [base, step]
    def arms(lo, hi, h, post):
        out = [post] if post else []
        if p.get("north", "false") not in ("false", "none"):
            out.append((lo, 0, 0, hi, h, lo))
        if p.get("south", "false") not in ("false", "none"):
            out.append((lo, 0, hi, hi, h, 1))
        if p.get("west", "false") not in ("false", "none"):
            out.append((0, 0, lo, lo, h, hi))
        if p.get("east", "false") not in ("false", "none"):
            out.append((hi, 0, lo, 1, h, hi))
        return out
    if name.endswith("_wall"):
        return arms(.3125, .6875, 1.5, (.25, 0, .25, .75, 1.5, .75) if p.get("up", "true") == "true" else None)
    if name.endswith("fence"):
        return arms(.375, .625, 1.5, (.375, 0, .375, .625, 1.5, .625))
    if name.endswith("pane") or name == "iron_bars":
        return arms(.4375, .5625, 1, (.4375, 0, .4375, .5625, 1, .5625))
    if name.endswith("fence_gate"):
        return [] if p.get("open") == "true" else ([(0, 0, .375, 1, 1.5, .625)] if p.get("facing") in ("north", "south")
                                                   else [(.375, 0, 0, .625, 1.5, 1)])
    if name.endswith("carpet"):
        return [(0, 0, 0, 1, .0625, 1)]
    if name.endswith("trapdoor"):
        if p.get("open") == "true":
            f = p.get("facing", "north")
            return [{"north": (0, 0, .8125, 1, 1, 1), "south": (0, 0, 0, 1, 1, .1875), "west": (.8125, 0, 0, 1, 1, 1),
                     "east": (0, 0, 0, .1875, 1, 1)}[f]]
        return [(0, .8125, 0, 1, 1, 1)] if p.get("half") == "top" else [(0, 0, 0, 1, .1875, 1)]
    if name in ("end_rod", "chain", "lightning_rod") or name.endswith("chain"):
        return [(.375, 0, .375, .625, 1, .625)]
    if name == "snow":
        return [(0, 0, 0, 1, (int(p.get("layers", "1")) - 1) / 8, 1)]
    if name.endswith("_bed"):
        return [(0, 0, 0, 1, .5625, 1)]
    if name in ("soul_sand", "mud"):
        return [(0, 0, 0, 1, .875, 1)]
    if name in ("dirt_path", "farmland"):
        return [(0, 0, 0, 1, .9375, 1)]
    if name == "hopper":
        return [(0, .625, 0, 1, 1, 1), (.25, .25, .25, .75, .625, .75)]
    if name.endswith("_head") or name.endswith("_skull"):
        return [(.25, 0, .25, .75, .5, .75)]
    if name.endswith("lantern"):
        return [(.3125, 0, .3125, .6875, .5625, .6875)]
    if name in ("cactus",):
        return [(.0625, 0, .0625, .9375, .9375, .9375)]
    if name.endswith("_door"):
        f, o = p.get("facing", "north"), p.get("open") == "true"
        hinge = p.get("hinge", "left")
        if o:
            f = {"north": "east", "east": "south", "south": "west", "west": "north"}[f] if hinge == "left" else \
                {"north": "west", "west": "south", "south": "east", "east": "north"}[f]
        return [{"north": (0, 0, .8125, 1, 1, 1), "south": (0, 0, 0, 1, 1, .1875), "west": (.8125, 0, 0, 1, 1, 1),
                 "east": (0, 0, 0, .1875, 1, 1)}[f]]
    return [(0, 0, 0, 1, 1, 1)]


def cuboid_faces(x0, y0, z0, x1, y1, z1):
    # in plot coordinates: (x, z, y), so up is up
    c = [(x0, z0, y0), (x1, z0, y0), (x1, z1, y0), (x0, z1, y0), (x0, z0, y1), (x1, z0, y1), (x1, z1, y1), (x0, z1, y1)]
    return [[c[0], c[1], c[2], c[3]], [c[4], c[5], c[6], c[7]], [c[0], c[1], c[5], c[4]], [c[2], c[3], c[7], c[6]],
            [c[1], c[2], c[6], c[5]], [c[0], c[3], c[7], c[4]]]


def draw_area(ax, blocks, box, seg, title, marks=()):
    """blocks round box = (x0, y0, z0, x1, y1, z1) in 3D, with the player's path (seg: trace rows) through it"""
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    from matplotlib.colors import to_rgba
    bx0, by0, bz0, bx1, by1, bz1 = box
    faces, cols = [], []
    for x, y, z, s in blocks:
        if bx0 <= x <= bx1 and by0 <= y <= by1 and bz0 <= z <= bz1:
            c = to_rgba(color(s))
            for (a, b, d, e, f, g) in shapes(s):
                # a box that's only a sliver is see-through, so what's behind a ladder or a pane still shows
                alpha = 0.95 if min(e - a, f - b, g - d) > 0.3 else 0.6
                for face in cuboid_faces(x + a, y + b, z + d, x + e, y + f, z + g):
                    faces.append(face)
                    cols.append((c[0], c[1], c[2], alpha))
    if faces:
        pc = Poly3DCollection(faces, facecolors=cols, edgecolors=(0, 0, 0, 0.25), linewidths=0.2)
        ax.add_collection3d(pc)
    if seg:
        ax.plot([t["x"] for t in seg], [t["z"] for t in seg], [t["y"] for t in seg], color="red", lw=1.4, zorder=10)
        ground = [t for t in seg if t["onGround"]]
        if ground:
            ax.scatter([t["x"] for t in ground], [t["z"] for t in ground], [t["y"] for t in ground], color="#d01010", s=3)
    for (mx, my, mz, label) in marks:
        ax.scatter([mx], [mz], [my], color="cyan", s=30, marker="*")
    ax.set_xlim(bx0, bx1 + 1)
    ax.set_ylim(bz0, bz1 + 1)
    ax.set_zlim(by0, by1 + 1)
    ax.set_box_aspect((bx1 - bx0 + 1, bz1 - bz0 + 1, by1 - by0 + 1))
    ax.invert_yaxis()  # z grows south: north at the back
    ax.view_init(elev=38, azim=-62)
    ax.set_axis_off()
    ax.set_title(title, fontsize=10)


def render(path, outdir, argv):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    from matplotlib.collections import PatchCollection
    from matplotlib.colors import to_rgba

    meta, worlds, bl, trace, events = load(path)
    w = pick_world(argv, trace)
    lo, hi = span(args_opt(argv, "--ticks"), (0, 1 << 60))
    tr = [t for t in trace if t["world"] == w and lo <= t["tick"] <= hi]
    blocks = [(x, y, z, s) for (x, y, z, s, ww) in bl if ww == w]
    os.makedirs(outdir, exist_ok=True)
    if not tr:
        sys.exit("nothing in that part of the run")
    px = [t["x"] for t in tr]
    pz = [t["z"] for t in tr]
    py = [t["y"] for t in tr]
    marks = []
    for tk, ms, text in events:
        if text.startswith("mark") and lo <= tk <= hi:
            p = text.split(" ")
            try:
                marks.append((float(p[1]), float(p[2]), float(p[3]), " ".join(p[4:]) or "mark"))
            except (ValueError, IndexError):
                pass

    # the map from above, round the run: the top of each column (up to just over the player's head), each block drawn the
    # size it really is (a wall post is half a block, a fence a quarter), darker the further down it is
    m = 6
    x0, x1 = int(min(px)) - m, int(max(px)) + m
    z0, z1 = int(min(pz)) - m, int(max(pz)) + m
    ytop = int(max(py)) + 2
    ymin = min(py) - 8
    cols = defaultdict(list)
    for x, y, z, s in blocks:
        if x0 <= x <= x1 and z0 <= z <= z1 and y <= ytop:
            cols[(x, z)].append((y, s))
    rects, colors = [], []
    for (x, z), stack in cols.items():
        stack.sort()
        # the highest full-ish thing, and anything thin standing on it
        drawn = 0
        for y, s in reversed(stack):
            boxes = shapes(s)
            if not boxes:
                continue
            c = list(to_rgba(color(s)))
            f = max(0.25, min(1.0, 0.45 + (y - ymin) / 14))
            c[:3] = [v * f for v in c[:3]]
            for (a, b, d, e, g, h) in boxes:
                rects.append(Rectangle((x + a, z + d), e - a, h - d))
                colors.append(c)
            drawn += 1
            if any(e - a > 0.9 and h - d > 0.9 for (a, b, d, e, g, h) in boxes) or drawn >= 2:
                break
    W = max(8, min(80, (x1 - x0) / 5))
    H = max(5, min(80, (z1 - z0) / 5))
    fig, ax = plt.subplots(figsize=(W, H), dpi=110)
    ax.set_facecolor("#1e1e1e")
    # lower first, so the top of each column ends up on top
    order = sorted(range(len(rects)), key=lambda i: colors[i][0] + colors[i][1] + colors[i][2])
    ax.add_collection(PatchCollection([rects[i] for i in order], facecolors=[colors[i] for i in order], edgecolors=(0, 0, 0, 0.3),
                                      linewidths=0.3))
    ax.plot(px, pz, color="white", lw=0.6, alpha=0.7)
    air = [(t["x"], t["z"]) for t in tr if not t["onGround"]]
    if air:
        ax.scatter([a[0] for a in air], [a[1] for a in air], s=2, color="#ff5050", zorder=3)
    js = jumps(tr)
    for k, (i, j, jumped) in enumerate(js, 1):
        ax.annotate(str(k), (tr[i]["x"], tr[i]["z"]), color="yellow", fontsize=7, zorder=4)
    for mx, my, mz, label in marks:
        ax.plot(mx, mz, "c*", ms=10)
        ax.annotate(label, (mx, mz), color="cyan", fontsize=8)
    ax.set_xlim(x0, x1 + 1)
    ax.set_ylim(z1 + 1, z0)
    ax.set_aspect("equal")
    ax.set_title(f"{meta.get('name')} ({meta.get('server')}, world {w}): from above (north up), red = in the air, numbers = jumps")
    fig.savefig(os.path.join(outdir, "map_top.png"), facecolor="white", bbox_inches="tight")
    plt.close(fig)

    # height along the way
    fig, ax = plt.subplots(figsize=(14, 4), dpi=100)
    ax.plot([t["tick"] for t in tr], py, lw=0.8)
    for k, (i, j, jumped) in enumerate(js, 1):
        ax.annotate(str(k), (tr[i]["tick"], tr[i]["y"]), fontsize=6)
    ax.set_xlabel("tick")
    ax.set_ylabel("y")
    ax.set_title("height over time")
    fig.savefig(os.path.join(outdir, "height.png"), facecolor="white", bbox_inches="tight")
    plt.close(fig)

    index = defaultdict(list)
    for x, y, z, s in blocks:
        index[(x >> 3, z >> 3)].append((x, y, z, s))

    def near(box):
        bx0, by0, bz0, bx1, by1, bz1 = box
        out = []
        for cx in range(bx0 >> 3, (bx1 >> 3) + 1):
            for cz in range(bz0 >> 3, (bz1 >> 3) + 1):
                out.extend(index.get((cx, cz), ()))
        return out

    # one spot in 3D: --around X Y Z [R]
    around = args_opt(argv, "--around", 3)
    if around:
        ax_, ay_, az_ = (int(float(v)) for v in around)
        r = 6
        box = (ax_ - r, ay_ - 4, az_ - r, ax_ + r, ay_ + 4, az_ + r)
        seg = [t for t in tr if box[0] - 1 <= t["x"] <= box[3] + 2 and box[2] - 1 <= t["z"] <= box[5] + 2 and box[1] <= t["y"] <= box[4] + 1]
        fig = plt.figure(figsize=(10, 9), dpi=110)
        draw_area(fig.add_subplot(1, 1, 1, projection="3d"), near(box), box, seg, f"round {ax_} {ay_} {az_}",
                  [mk for mk in marks if abs(mk[0] - ax_) <= r and abs(mk[2] - az_) <= r])
        fig.savefig(os.path.join(outdir, f"around_{ax_}_{ay_}_{az_}.png"), facecolor="white", bbox_inches="tight")
        plt.close(fig)

    # each jump close up: the blocks round the takeoff and landing, and the player's path through the air (and a second
    # before) as a line
    ja, jb = span(args_opt(argv, "--jumps"), (1, 24))
    chosen = [(k, js[k - 1]) for k in range(ja, min(jb, len(js)) + 1)]
    if not chosen:
        print(f"{outdir}: map_top.png, height.png (no jumps)")
        return
    per = 4
    rows = (len(chosen) + per - 1) // per
    fig = plt.figure(figsize=(per * 5, rows * 4.6), dpi=90)
    for n, (k, (i, j, jumped)) in enumerate(chosen):
        seg = tr[max(0, i - 20):min(len(tr), j + 6)]
        sx = [t["x"] for t in seg]
        sz = [t["z"] for t in seg]
        sy = [t["y"] for t in seg]
        box = (math.floor(min(sx)) - 2, math.floor(min(sy)) - 3, math.floor(min(sz)) - 2,
               math.floor(max(sx)) + 2, math.floor(max(sy)) + 2, math.floor(max(sz)) + 2)
        fa, fb = feet(tr[i]), feet(tr[j])
        mv = (tr[i].get("movement") or "").replace("Movement", "")
        draw_area(fig.add_subplot(rows, per, n + 1, projection="3d"), near(box), box, seg,
                  f"{k}. tick {tr[i]['tick']} ({fb[0] - fa[0]:+d},{fb[1] - fa[1]:+d},{fb[2] - fa[2]:+d}) {mv}")
    plt.tight_layout()
    fig.savefig(os.path.join(outdir, f"jumps_{ja}-{min(jb, len(js))}.png"), facecolor="white")
    plt.close(fig)
    print(f"{outdir}: map_top.png, height.png, jumps_{ja}-{min(jb, len(js))}.png ({len(js)} jumps in all)")


# --- playback ----------------------------------------------------------------------------------------------------------

FRAMES_PER_LIST = 500
# the planner talking to itself (chatDebug): not worth replaying
CHAT_NOISE = ("cost coefficient", "Favoring size", "movements considered", "Path ends within", "Starting to search",
              "Planning ahead", "Even with a cost", "No path found", "Found path segment", "Finished finding a path",
              "slow tick", "Teleported ", "Running function")


def playback(meta, tr, events, off, P):
    """the run played back in the world, Flashback style: a mannequin (a player-shaped entity) walks it tick by tick.
    the frames go into command storage in lists of FRAMES_PER_LIST, and a tick function moves the mannequin to the next one"""
    fn = {}
    lists = []
    for b in range(0, len(tr), FRAMES_PER_LIST):
        frames = []
        for t in tr[b:b + FRAMES_PER_LIST]:
            pose = "crouching" if t["sneaking"] else "standing"
            frames.append(f'{{x:{t["x"] + off[0]:.4f}d,y:{t["y"] + off[1]:.4f}d,z:{t["z"] + off[2]:.4f}d,'
                          f'yr:{t["yaw"]:.2f}f,xr:{t["pitch"]:.2f}f,p:"{pose}"}}')
        lists.append(f"data modify storage {P}:trace f{b // FRAMES_PER_LIST} set value [{','.join(frames)}]")
    fn["trace"] = [f"data remove storage {P}:trace f{i}" for i in range(len(lists))] + lists
    # the chat lines and marks, said when the replay gets to them
    said = defaultdict(list)
    first_tick = tr[0]["tick"] if tr else 0
    for tk, ms, text in events:
        if text.startswith("chat ") and any(n in text for n in CHAT_NOISE):
            continue
        if text.startswith("chat ") or text.startswith("mark "):
            k = tk - first_tick
            if 0 <= k < len(tr):
                said[k].append(("[mark] " + text[5:]) if text.startswith("mark") else text[5:])
    n = len(tr)
    profile = meta.get("player") or ""
    ghost = f"@e[type=minecraft:mannequin,tag={P}_ghost]"
    fn["load"] = [f"scoreboard objectives add {P}_tmp dummy", f"scoreboard players set #frames {P}_tmp {FRAMES_PER_LIST}"]
    fn["tick"] = [f"execute if score #playing {P}_tmp matches 1 run function {P}:step"]
    start = tr[0] if tr else None
    if start:
        fn["play"] = [
            f"function {P}:load",
            f"execute unless data storage {P}:trace f0 run function {P}:trace",
            # watching from far off: go to the start first (the ghost only moves where the world's loaded)
            f'execute positioned {start["x"] + off[0]:.2f} {start["y"] + off[1]:.2f} {start["z"] + off[2]:.2f} as @s unless entity @s[distance=..48] run function {P}:tp',
            f"kill {ghost}",
            f'summon minecraft:mannequin {start["x"] + off[0]:.4f} {start["y"] + off[1]:.4f} {start["z"] + off[2]:.4f} '
            f'{{Tags:["{P}_ghost"],NoGravity:1b,Invulnerable:1b,Silent:1b,immovable:1b,hide_description:1b'
            + (f',profile:"{profile}"' if profile else "") + f',CustomName:{json.dumps(meta.get("name", "replay"))},CustomNameVisible:1b}}',
            f"scoreboard players set #t {P}_tmp 0",
            f"scoreboard players set #playing {P}_tmp 1",
            'tellraw @s ' + json.dumps([{"text": "[Replay] ", "color": "gold"},
                                        {"text": f"Playing {meta.get('name')} ({n} ticks). ", "color": "white"},
                                        {"text": "[Pause]", "color": "yellow", "click_event": {"action": "run_command", "command": f"/function {P}:pause"}},
                                        {"text": " "},
                                        {"text": "[Watch from its eyes]", "color": "aqua", "click_event": {"action": "run_command", "command": f"/function {P}:watch"}},
                                        {"text": " "},
                                        {"text": "[Stop]", "color": "red", "click_event": {"action": "run_command", "command": f"/function {P}:stop"}}]),
        ]
        # /function replay:play_at {t:1200} starts from that tick
        fn["play_at"] = [f"function {P}:play", f"$scoreboard players set #t {P}_tmp $(t)"]
    fn["step"] = [
        f"execute if score #t {P}_tmp matches {n}.. run return run function {P}:stop",
        f"scoreboard players operation #b {P}_tmp = #t {P}_tmp",
        f"scoreboard players operation #b {P}_tmp /= #frames {P}_tmp",
        f"scoreboard players operation #i {P}_tmp = #t {P}_tmp",
        f"scoreboard players operation #i {P}_tmp %= #frames {P}_tmp",
        f"execute store result storage {P}:now args.b int 1 run scoreboard players get #b {P}_tmp",
        f"execute store result storage {P}:now args.i int 1 run scoreboard players get #i {P}_tmp",
        f"function {P}:frame with storage {P}:now args",
        f'title @a[tag={P}_watching] actionbar [{{"text":"tick ","color":"gray"}},{{"score":{{"name":"#t","objective":"{P}_tmp"}},"color":"white"}},{{"text":" / {n}","color":"gray"}}]',
    ]
    if said:
        fn["step"].append(f"function {P}:say")
        # a binary tree of ticks would be quicker, but there's only ever a few hundred lines
        fn["say"] = []
        for k in sorted(said):
            for line in said[k]:
                fn["say"].append(f'execute if score #t {P}_tmp matches {k} run tellraw @a ' + json.dumps({"text": line, "color": "gray"}))
    fn["step"].append(f"scoreboard players add #t {P}_tmp 1")
    fn["frame"] = [f"$data modify storage {P}:now f set from storage {P}:trace f$(b)[$(i)]",
                   f"function {P}:move with storage {P}:now f"]
    fn["move"] = [f"$tp {ghost} $(x) $(y) $(z) $(yr) $(xr)",
                  f'$execute as {ghost} run data merge entity @s {{pose:"$(p)"}}']
    fn["pause"] = [f"execute if score #playing {P}_tmp matches 1 run return run scoreboard players set #playing {P}_tmp 2",
                   f"execute if score #playing {P}_tmp matches 2 run scoreboard players set #playing {P}_tmp 1"]
    fn["watch"] = [f"tag @s add {P}_watching", "gamemode spectator @s", f"spectate {ghost.replace(']', ',limit=1]')} @s"]
    fn["stop"] = [f"scoreboard players set #playing {P}_tmp 0", f"kill {ghost}",
                  f"execute as @a[tag={P}_watching] run spectate", f"tag @a remove {P}_watching",
                  'tellraw @a {"text":"[Replay] Stopped. /function replay:play to watch it again.","color":"gold"}']
    return fn


# --- datapack ----------------------------------------------------------------------------------------------------------

DATA_FORMAT = 121  # 26.3
ATTACHED = ("ladder", "vine", "torch", "button", "lever", "sign", "banner", "carpet", "pressure_plate", "rail", "door",
            "bed", "flower", "sapling", "grass", "fern", "bush", "kelp", "seagrass", "lantern", "chain", "bell",
            "tripwire", "redstone_wire", "repeater", "comparator", "snow[", "cocoa", "amethyst_cluster", "bud", "head",
            "skull", "candle", "scaffolding", "pointed_dripstone", "glow_lichen", "sculk_vein", "item_frame", "string")


def datapack(path, out, argv):
    meta, worlds, bl, trace, events = load(path)
    w = pick_world(argv, trace)
    off = [int(v) for v in args_opt(argv, "--offset", 3, ["0", "0", "0"])]
    blocks = {}
    for x, y, z, s, ww in bl:
        if ww == w:
            blocks[(x + off[0], y + off[1], z + off[2])] = s
    # chunk by chunk, so each part only loads a few chunks at once; solid blocks first and the things that hang off them after
    chunks = defaultdict(list)
    for (x, y, z), s in blocks.items():
        chunks[(x >> 4, z >> 4)].append((x, y, z, s))
    keys = sorted(chunks)
    groups = [keys[i:i + 16] for i in range(0, len(keys), 16)]
    fn = {}
    P = "replay"
    fn["build"] = ['tellraw @a {"text":"[Replay] Building %s: %d blocks in %d parts..."}' % (meta.get("name"), len(blocks), len(groups)),
                   "scoreboard objectives add replay_tmp dummy", f"function {P}:part_0"]
    for g, group in enumerate(groups):
        fx0 = min(k[0] for k in group) * 16
        fz0 = min(k[1] for k in group) * 16
        fx1 = max(k[0] for k in group) * 16 + 15
        fz1 = max(k[1] for k in group) * 16 + 15
        lines = [f"forceload add {fx0} {fz0} {fx1} {fz1}", "scoreboard players set #wait replay_tmp 0",
                 f"schedule function {P}:wait_{g} 5t"]
        fn[f"part_{g}"] = lines
        cond = " ".join(f"if loaded {cx * 16} 0 {cz * 16}" for cx, cz in group)
        fn[f"wait_{g}"] = ["scoreboard players add #wait replay_tmp 1",
                           f"execute {cond} run return run function {P}:now_{g}",
                           f"execute if score #wait replay_tmp matches 100.. run return run function {P}:now_{g}",
                           f"schedule function {P}:wait_{g} 5t"]
        now = []
        later = []
        for k in group:
            cells = chunks[k]
            ylo = min(c[1] for c in cells)
            yhi = max(c[1] for c in cells)
            # clear what was air in the recording (16 x 16 x 128 at most per fill)
            for y in range(ylo, yhi + 1, 128):
                now.append(f"fill {k[0] * 16} {y} {k[1] * 16} {k[0] * 16 + 15} {min(yhi, y + 127)} {k[1] * 16 + 15} air strict")
            # runs along x of the same block as one fill
            cells.sort(key=lambda c: (c[1], c[2], c[0]))
            run = None
            for x, y, z, s in cells + [(None, None, None, None)]:
                if run and s == run[4] and y == run[1] and z == run[2] and x == run[3] + 1:
                    run[3] = x
                    continue
                if run:
                    rx, ry, rz, rx1, rs = run
                    target = later if any(a in short(rs) for a in ATTACHED) else now
                    target.append(f"setblock {rx} {ry} {rz} {rs} strict" if rx == rx1 else f"fill {rx} {ry} {rz} {rx1} {ry} {rz} {rs} strict")
                run = [x, y, z, x, s] if x is not None else None
        nxt = [f"function {P}:part_{g + 1}"] if g + 1 < len(groups) else [
            'tellraw @a ' + json.dumps([{"text": "[Replay] Built. ", "color": "green"},
                                         {"text": "[Go to the start]", "color": "aqua", "click_event": {"action": "run_command", "command": f"/function {P}:tp"}},
                                         {"text": " "},
                                         {"text": "[Play the run]", "color": "yellow", "click_event": {"action": "run_command", "command": f"/function {P}:play"}}])]
        fn[f"now_{g}"] = now + later + [f"forceload remove {fx0} {fz0} {fx1} {fz1}"] + nxt
    first = next((t for t in trace if t["world"] == w), None)
    if first:
        fn["tp"] = [f"tp @s {first['x'] + off[0]:.3f} {first['y'] + off[1]:.3f} {first['z'] + off[2]:.3f} {first['yaw']:.1f} {first['pitch']:.1f}"]
    fn.update(playback(meta, [t for t in trace if t["world"] == w], events, off, P))
    fn["build"].insert(2, f"function {P}:trace")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps({"pack": {"description": f"Rebuilds the recording {meta.get('name')} (/function replay:build)",
                                                       "min_format": DATA_FORMAT, "max_format": DATA_FORMAT}}, indent=2))
        for name, lines in fn.items():
            z.writestr(f"data/{P}/function/{name}.mcfunction", "\n".join(lines) + "\n")
        for tag, f_ in (("load", "load"), ("tick", "tick")):
            z.writestr(f"data/minecraft/tags/function/{tag}.json", json.dumps({"values": [f"{P}:{f_}"]}))
    print(f"{out}: {len(blocks)} blocks in {len(groups)} parts")


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in ("summary", "render", "course", "datapack"):
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "summary":
        summary(sys.argv[2], sys.argv[3:])
    else:
        if len(sys.argv) < 4:
            print(__doc__)
            sys.exit(1)
        {"render": render, "course": course, "datapack": datapack}[cmd](sys.argv[2], sys.argv[3], sys.argv[4:])
