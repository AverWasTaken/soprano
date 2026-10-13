# pulls the item/block icons the html mock needs out of the local minecraft client jar.
# same lookup as src/test/.../preview/ItemTextures.java so the mock and the java previews agree.
# run: python -I gui-mock/extract-items.py   (needs Pillow; the jar is never copied, only icons)
import json
import os
import sys
import zipfile
from io import BytesIO

from PIL import Image

# jar: argv[1] or $MC_CLIENT_JAR wins, else the 1.21.4 client jar in the unimined cache, else the newest one there
def find_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    if os.environ.get("MC_CLIENT_JAR"):
        return os.environ["MC_CLIENT_JAR"]
    base = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "unimined", "net", "minecraft", "minecraft")
    pref = os.path.join(base, "1.21.4", "minecraft-1.21.4-client.jar")
    if os.path.exists(pref):
        return pref
    import glob
    found = sorted(glob.glob(os.path.join(base, "*", "minecraft-*-client.jar")), key=os.path.getmtime)
    return found[-1] if found else pref


JAR = find_jar()
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "assets", "items")

# FakeFacade gettable + mineable lists, then the follow tab's spawn eggs. keep in sync by hand
GETTABLE = """oak_log oak_planks stick crafting_table wooden_pickaxe stone_pickaxe iron_pickaxe
diamond_pickaxe cobblestone stone furnace coal charcoal iron_ingot iron_ore raw_iron
gold_ingot diamond obsidian bucket water_bucket lava_bucket flint flint_and_steel
bread cooked_beef cooked_porkchop cooked_chicken apple wheat wheat_seeds bed
white_wool string bow arrow shield iron_sword diamond_sword iron_helmet
iron_chestplate iron_leggings iron_boots torch ladder chest ender_pearl blaze_rod
blaze_powder ender_eye glass sand gravel dirt netherrack gold_block iron_block
diamond_block redstone lapis_lazuli emerald book paper sugar_cane leather fishing_rod""".split()
MINEABLE = """stone cobblestone coal_ore iron_ore deepslate_iron_ore gold_ore diamond_ore
deepslate_diamond_ore redstone_ore lapis_ore emerald_ore ancient_debris oak_log birch_log
spruce_log dirt sand gravel clay obsidian netherrack nether_quartz_ore glowstone""".split()
# every mob in FakeWorld's entity rows (the item entity shows oak_log, already above)
FOLLOW_MOBS = "zombie cow sheep wolf creeper skeleton villager chicken pig bat".split()

# true = match the java previews exactly (grey textures come out brighter), false = real game colours
JAVA_GREY_QUIRK = True
BLOCK_SUFFIXES = ["", "_front", "_top", "_side", "_still"]

# vanilla's egg colour pairs, straight from ItemTextures.EGG_COLOURS
EGG_COLOURS = {
    "zombie": (0x00AFAF, 0x799C65), "skeleton": (0xC1C1C1, 0x494949),
    "creeper": (0x0DA70B, 0x000000), "spider": (0x342D27, 0xA80E0E),
    "enderman": (0x161616, 0x000000), "cow": (0x443626, 0xA1A1A1),
    "pig": (0xF0A5A2, 0xDB635F), "sheep": (0xE7E7E7, 0xFFB5B5),
    "chicken": (0xA1A1A1, 0xFF0000), "wolf": (0xD7D3D3, 0xCEAF96),
    "villager": (0x563C33, 0xBD8B72), "bat": (0x4C3E30, 0x0F0F0F),
    "horse": (0xC09E7D, 0xEEE500), "cat": (0xEFC88E, 0x957256),
    "blaze": (0xF6B201, 0xFFF87E), "piglin": (0x995F40, 0xF9F3A4),
}


def java_hash(s):
    # String.hashCode as a 32 bit pattern; java's >>> on an int is a plain shift of this
    h = 0
    for ch in s:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return h


def load(jar, name):
    try:
        data = jar.read("assets/minecraft/textures/" + name + ".png")
    except KeyError:
        return None
    img = Image.open(BytesIO(data))
    grey = img.mode in ("L", "LA")
    img = img.convert("RGBA")
    if grey and JAVA_GREY_QUIRK:
        # ImageIO reads grey pngs as linear grey and getRGB brightens them to srgb (stone: 8f -> c5).
        # the java previews show that, so the mock does too. flip the flag for the real game colour
        lut = [round(255 * (v / 255 * 12.92 if v / 255 <= 0.0031308 else 1.055 * (v / 255) ** (1 / 2.4) - 0.055)) for v in range(256)]
        img = Image.merge("RGBA", [img.getchannel(c).point(lut) for c in "RGB"] + [img.getchannel("A")])
    # everything else: getRGB gives plain non-premultiplied argb, which is what RGBA is
    return img


def tint(px, rgb):
    r, g, b, a = px
    if a == 0:
        return (0, 0, 0, 0)
    # integer division like the java, no rounding
    return (r * (rgb >> 16 & 255) // 255, g * (rgb >> 8 & 255) // 255, b * (rgb & 255) // 255, a)


def egg(jar, mob):
    base = load(jar, "item/spawn_egg")
    overlay = load(jar, "item/spawn_egg_overlay")
    if base is None:
        return None
    pair = EGG_COLOURS.get(mob)
    if pair is None:
        h = java_hash(mob)
        # 0x404040 | (h & 0x7F7F7F), 0x202020 | ((h >>> 8) & 0xBFBFBF) - ints, so the low 24 bits
        pair = (0x404040 | (h & 0x7F7F7F), 0x202020 | ((h >> 8) & 0xBFBFBF))
    w, hh = base.size
    out = Image.new("RGBA", (w, hh), (0, 0, 0, 0))
    bp = base.load()
    op = overlay.load() if overlay is not None else None
    ow, oh = overlay.size if overlay is not None else (0, 0)
    xp = out.load()
    for y in range(hh):
        for x in range(w):
            p = tint(bp[x, y], pair[0])
            if op is not None and x < ow and y < oh:
                o = tint(op[x, y], pair[1])
                if o[3] > 0:
                    p = o
            xp[x, y] = p
    return out


def get(jar, item_id):
    path = item_id.split(":", 1)[-1]
    found = load(jar, "item/" + path)
    for suffix in BLOCK_SUFFIXES:
        if found is not None:
            break
        found = load(jar, "block/" + path + suffix)
    if found is None and path.endswith("_spawn_egg"):
        found = egg(jar, path[: -len("_spawn_egg")])
    # animated textures are a vertical strip of frames, the first one is the icon
    if found is not None and found.height > found.width:
        found = found.crop((0, 0, found.width, found.width))
    return found


def main():
    if not os.path.isfile(JAR):
        sys.exit("client jar not found: " + JAR)
    ids = []
    for i in GETTABLE + MINEABLE + [m + "_spawn_egg" for m in FOLLOW_MOBS]:
        if i not in ids:
            ids.append(i)
    os.makedirs(OUT, exist_ok=True)
    ok, missing = [], []
    with zipfile.ZipFile(JAR) as jar:
        for i in ids:
            img = get(jar, i)
            if img is None:
                missing.append(i)
                continue
            img.save(os.path.join(OUT, i + ".png"))
            ok.append(i)
    with open(os.path.join(OUT, "index.json"), "w") as f:
        json.dump({"ok": ok, "missing": missing}, f, indent=1)
    print("wrote %d, missing %d: %s" % (len(ok), len(missing), ", ".join(missing)))


main()
