#!/usr/bin/env python3
# pictures of the course from course.json: a contact sheet with every section on its own, and the finale on its own
import json
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

d = json.load(open(sys.argv[1]))
out = sys.argv[2]

# first match wins, so the specific names go before the general ones
COLORS = [
    ("emerald_block", "#2ecc71"), ("white_concrete", "#f4f4f4"), ("black_concrete", "#1b1b22"), ("gold_block", "#f5c542"), ("snow", "#f2f6f8"), ("blue_ice", "#5b93f0"),
    ("packed_ice", "#8db4f0"), ("ice", "#a9cbf7"), ("purpur", "#a77ba7"), ("slime", "#77c84f"),
    ("green_concrete", "#4b6b23"), ("dark_oak", "#4a3420"), ("stained_glass", "#d9e4e8"), ("quartz", "#ece6dc"),
    ("white_concrete", "#f4f4f4"), ("black_concrete", "#1b1b22"), ("blackstone_bricks", "#2c272b"),
    ("polished_blackstone", "#3a3438"), ("cyan_terracotta", "#4c7f86"), ("lime_terracotta", "#7a9a3d"),
    ("orange_terracotta", "#b5602c"), ("polished_andesite", "#9aa0a0"), ("mossy", "#6b7b55"), ("stone_bricks", "#7f7f7f"),
    ("deepslate", "#4a4a52"), ("spruce", "#6b4e2e"), ("andesite", "#8f9494"), ("smooth_stone", "#a0a0a0"), ("tuff", "#6d6f63"), ("copper", "#c0704a"), ("lime_concrete", "#5ec42e"), ("dark_prismarine", "#2f5e57"), ("prismarine", "#5aa59a"), ("ladder", "#a8803e"), ("vine", "#3f8f2f"),
    ("stone", "#8a8a8a"), ("oak", "#b48a52"), ("cobblestone", "#7a7a7a"), ("glass", "#cfe8ef"), ("iron_bars", "#9a9a9a"),
    ("hopper", "#4a4a4a"), ("end_rod", "#f0eadf"),
]


def color(state):
    s = state.split("[")[0]
    for k, v in COLORS:
        if k in s:
            return v
    return "#b0b0b0"


def voxels(ax, blocks, elev, azim):
    xs = [b[0] for b in blocks]
    ys = [b[1] for b in blocks]
    zs = [b[2] for b in blocks]
    x0, y0, z0 = min(xs), min(ys), min(zs)
    nx, ny, nz = max(xs) - x0 + 1, max(ys) - y0 + 1, max(zs) - z0 + 1
    filled = np.zeros((nx, nz, ny), dtype=bool)
    cols = np.empty((nx, nz, ny), dtype=object)
    for x, y, z, s in blocks:
        filled[x - x0, z - z0, y - y0] = True
        cols[x - x0, z - z0, y - y0] = color(s)
    ax.voxels(filled, facecolors=cols, edgecolor=(0, 0, 0, 0.15), linewidth=0.2)
    ax.set_box_aspect((nx, nz, ny))
    ax.view_init(elev=elev, azim=azim)
    ax.set_axis_off()


blocks = d["blocks"]
base = d["base"]
cps = d["checkpoints"]
secs = d["sections"][:-1]  # the finale gets a picture of its own
cols_n = 4
rows_n = (len(secs) + cols_n - 1) // cols_n
fig = plt.figure(figsize=(cols_n * 6, rows_n * 4.6), dpi=100)
for i, sec in enumerate(secs):
    a, b = cps[sec["from"]], cps[sec["to"]]
    lo = min(a["y"], b["y"]) - 12
    part = [x for x in blocks if a["x"] - 1 <= x[0] <= b["x"] + 1 and x[1] >= lo]
    ax = fig.add_subplot(rows_n, cols_n, i + 1, projection="3d")
    voxels(ax, part, 30, -70)
    ax.set_title(f'{sec["id"]}. {sec["name"]}', fontsize=15)
plt.tight_layout()
fig.savefig(out + "/course_sections.png", facecolor="white")
plt.close(fig)

fin_x0 = cps[-2]["x"]
fin = [b for b in blocks if b[0] >= fin_x0 - 2]
mid = (fin_x0 + max(b[0] for b in fin)) // 2
fig = plt.figure(figsize=(16, 13), dpi=110)
for k, part in enumerate(([b for b in fin if b[0] <= mid + 1], [b for b in fin if b[0] >= mid - 1])):
    ax = fig.add_subplot(2, 1, k + 1, projection="3d")
    voxels(ax, part, 28, -70)
    ax.set_title("20. Finale: every section in one run" + (" (first half)" if k == 0 else " (second half)"), fontsize=14)
plt.tight_layout()
fig.savefig(out + "/course_finale.png", facecolor="white")
plt.close(fig)
print("ok")
