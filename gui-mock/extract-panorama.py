# copies vanilla's title panorama into assets/panorama/ for title/index.html.
# the pngs inside the client jar are 1x1 placeholders since the jar split, the real ones live in the asset store
# gradle already downloaded. run: python -I gui-mock/extract-panorama.py [version]   (default 26.3, stdlib only)
import json
import os
import shutil
import sys

VERSION = sys.argv[1] if len(sys.argv) > 1 else "26.3"
CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "unimined")
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "assets", "panorama")


def main():
    vjson = os.path.join(CACHE, "net", "minecraft", "minecraft", VERSION, "version.json")
    if not os.path.isfile(vjson):
        sys.exit("version json not found: " + vjson)
    with open(vjson) as f:
        index_id = json.load(f)["assetIndex"]["id"]
    # the index id moves every few versions, so ask the version json instead of guessing 34
    index = os.path.join(CACHE, "assets", "indexes", index_id + ".json")
    if not os.path.isfile(index):
        sys.exit("asset index not found: " + index)
    with open(index) as f:
        objects = json.load(f)["objects"]
    os.makedirs(OUT, exist_ok=True)
    for i in range(6):
        key = "minecraft/textures/gui/title/background/panorama_%d.png" % i
        if key not in objects:
            sys.exit("not in index %s: %s" % (index_id, key))
        h = objects[key]["hash"]
        src = os.path.join(CACHE, "assets", "objects", h[:2], h)
        if not os.path.isfile(src):
            sys.exit("object missing (run the game once?): " + src)
        shutil.copyfile(src, os.path.join(OUT, "panorama_%d.png" % i))
    print("wrote 6 faces from index %s to %s" % (index_id, OUT))


main()
