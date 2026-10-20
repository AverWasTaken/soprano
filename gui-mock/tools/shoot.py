# screenshots one mock url headless, for looking at a tab without the java preview.
# usage: python -I tools/shoot.py <name> "<query>" [w] [h]   (run from gui-mock/, writes assets/shots/<name>.png)
# the query is everything after index.html? (tab=schematics&sc=empty&theme=light). w/h are the panel size in design px
import sys, subprocess, os
HERE = os.path.dirname(os.path.abspath(__file__))
MOCK = os.path.dirname(HERE)
EDGE = os.environ.get("EDGE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
name, query = sys.argv[1], sys.argv[2]
w = int(sys.argv[3]) if len(sys.argv) > 3 else 1040
h = int(sys.argv[4]) if len(sys.argv) > 4 else 680
out = os.path.join(MOCK, "assets", "shots", name + ".png")
os.makedirs(os.path.dirname(out), exist_ok=True)
if os.path.exists(out): os.remove(out)
url = "file:///" + MOCK.replace("\\", "/") + "/index.html?" + query + f"&ref=1&bare=1&w={w}&h={h}"
subprocess.run([EDGE, "--headless=new", f"--screenshot={out}", f"--window-size={w + 160},{h + 200}", "--force-device-scale-factor=1",
                "--virtual-time-budget=3000", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
print(out)
