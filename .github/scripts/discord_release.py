# posts a release to discord as an embed, pinging everyone.
# usage: DISCORD_WEBHOOK=... python discord_release.py <tag> [owner/repo]
# reads the release through the gh cli, so it needs GH_TOKEN (or a logged in gh) and nothing else

import json
import subprocess
import sys
import urllib.request

LOADERS = [("fabric", "Fabric"), ("forge", "Forge"), ("neoforge", "NeoForge")]


def main():
    tag = sys.argv[1]
    repo = sys.argv[2] if len(sys.argv) > 2 else "AverWasTaken/soprano"
    webhook = __import__("os").environ["DISCORD_WEBHOOK"]

    release = json.loads(subprocess.check_output(
        ["gh", "release", "view", tag, "-R", repo, "--json", "name,url,body,assets,publishedAt"]))
    assets = {a["name"]: a["url"] for a in release["assets"]}

    # one link per loader jar, matched by name so a missing loader just drops out
    links = []
    for key, label in LOADERS:
        for name, url in assets.items():
            if name.startswith(f"soprano-api-{key}-"):
                links.append(f"[{label}]({url})")
    downloads = " · ".join(links) if links else "see the release page"

    body = (release.get("body") or "").strip()
    first, _, rest = body.partition("\n")
    # the first line is the "for minecraft x" line, it reads better as its own field.
    # the any version build says "this version is base 26.3 mc..." there instead
    mc_line = first if first.lower().startswith(("for minecraft", "this version is base")) else ""
    notes = (rest if mc_line else body).strip()
    # discord caps descriptions at 4096
    if len(notes) > 3900:
        notes = notes[:3900].rsplit("\n", 1)[0] + "\n…"

    embed = {
        "title": f"{release['name'] or tag} is out",
        "url": release["url"],
        "description": notes or "New release.",
        "color": 0x2EA44F,
        "fields": [
            {"name": "Download", "value": downloads, "inline": False},
            {"name": "All files", "value": f"[Release page]({release['url']})", "inline": False},
        ],
        "footer": {"text": "Soprano"},
        "timestamp": release.get("publishedAt"),
    }
    if mc_line:
        embed["fields"].insert(0, {"name": "Supports", "value": mc_line, "inline": False})

    payload = {
        "content": "@everyone",
        "allowed_mentions": {"parse": ["everyone"]},
        "embeds": [embed],
    }
    req = urllib.request.Request(webhook, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": "soprano-release"})
    with urllib.request.urlopen(req) as resp:
        print("discord said", resp.status)


if __name__ == "__main__":
    main()
