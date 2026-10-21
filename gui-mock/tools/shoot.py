# screenshots one mock url headless, for looking at a tab without the java preview.
# usage: python -I tools/shoot.py <name> "<query>" [w] [h] [--page title] [--exact]   (writes assets/shots/<name>.png)
# the query is everything after index.html? (tab=schematics&sc=empty&theme=light). w/h are the panel size in design px.
# --page title shoots title/index.html instead: w/h are then the exact viewport (default 1920x1080), bare=1 is added
# and nothing else. --exact is that viewport mode on its own (it passes vw/vh and crops, only the title page reads them).
# the title page freezes on ?t= / ?fade= and the virtual time budget lets the panorama pngs and fonts land first
import sys, subprocess, os
HERE = os.path.dirname(os.path.abspath(__file__))
MOCK = os.path.dirname(HERE)
EDGE = os.environ.get("EDGE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")

args = [a for a in sys.argv[1:]]
page, exact = "index.html", False
if "--page" in args:
    i = args.index("--page")
    if i + 1 >= len(args) or args[i + 1].startswith("--"): sys.exit("--page wants a value, e.g. --page title")
    page = args[i + 1]
    del args[i:i + 2]
    if page in ("title", "title/"):
        page = "title/index.html"
    exact = page != "index.html"
if "--exact" in args:
    args.remove("--exact")
    exact = True

name, query = args[0], args[1]
dw, dh = (1920, 1080) if exact else (1040, 680)
w = int(args[2]) if len(args) > 2 else dw
h = int(args[3]) if len(args) > 3 else dh
out = os.path.join(MOCK, "assets", "shots", name + ".png")
os.makedirs(os.path.dirname(out), exist_ok=True)
if os.path.exists(out): os.remove(out)
base = "file:///" + MOCK.replace("\\", "/") + "/" + page + "?" + query
if exact:
    # new headless gives the page a viewport a little under the window, so ask for room, pin and crop
    url, size = base + f"&bare=1&vw={w}&vh={h}", f"{w + 200},{h + 200}"
else:
    url, size = base + f"&ref=1&bare=1&w={w}&h={h}", f"{w + 160},{h + 200}"
subprocess.run([EDGE, "--headless=new", f"--screenshot={out}", f"--window-size={size}", "--force-device-scale-factor=1",
                "--hide-scrollbars", "--virtual-time-budget=5000", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
if exact:
    from PIL import Image
    Image.open(out).crop((0, 0, w, h)).save(out)
print(out)
