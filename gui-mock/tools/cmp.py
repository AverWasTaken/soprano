# renders one tab of the mock headless at 2x and diffs it against the java hero png.
# usage: python -I tools/cmp.py <tab> <dark|light> [outdir]   (run from gui-mock/)
# needs pillow and edge. the pngs only exist after the java preview tests ran.
import sys, subprocess, os
from PIL import Image, ImageChops
HERE = os.path.dirname(os.path.abspath(__file__))
MOCK = os.path.dirname(HERE)
WT = os.path.dirname(MOCK)
OUT = sys.argv[3] if len(sys.argv) > 3 else os.path.join(MOCK, "assets", "cmp")
os.makedirs(OUT, exist_ok=True)
EDGE = os.environ.get("EDGE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
tab, theme = sys.argv[1], sys.argv[2]
shot = os.path.join(OUT, f"shot_{tab}_{theme}.png")
if os.path.exists(shot): os.remove(shot)
# ref=1 draws finished animations so the shot is stable, scale 2 because the pngs are 2x
url = "file:///" + MOCK.replace("\\", "/") + f"/index.html?tab={tab}&theme={theme}&ref=1&scale=2"
subprocess.run([EDGE, "--headless=new", f"--screenshot={shot}", "--window-size=2272,1700", "--force-device-scale-factor=1",
                "--default-background-color=00000000", "--virtual-time-budget=3000", url],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
a = Image.open(shot).convert("RGBA").crop((0, 0, 2272, 1552))
b = Image.open(os.path.join(WT, "build", "gui-preview", "site", f"{tab}_{theme}.png")).convert("RGBA")
bg = (128, 128, 128, 255)
def flat(i):
    c = Image.new("RGBA", i.size, bg); c.alpha_composite(i); return c.convert("RGB")
fa, fb = flat(a), flat(b)
# x4 so a one px slip is visible to a human
ImageChops.difference(fa, fb).point(lambda v: min(255, v * 4)).save(os.path.join(OUT, f"diff_{tab}_{theme}.png"))
side = Image.new("RGB", (fa.width * 2, fa.height)); side.paste(fb, (0, 0)); side.paste(fa, (fa.width, 0))
side.save(os.path.join(OUT, f"side_{tab}_{theme}.png"))  # java left, mock right
px = list(ImageChops.difference(fa, fb).convert("L").getdata())
print(f"{tab} {theme}: mean abs diff {sum(px) / len(px):.2f} (0..255)")
