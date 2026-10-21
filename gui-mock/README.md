# gui-mock

Browser mock of the click gui, matched to the java renderer. Plain html/css/js, no build.

- **Open:** double-click `index.html` (file://). The sidebar switches tabs, Light|Dark switches theme. Urls take `?tab=get&theme=light&scale=2&bare=1&ov=0.5&diff=1&basket=oak_log:194,stick:20`.
- **Textures:** `python -I gui-mock/extract-items.py [client.jar]` pulls item pngs from the client jar into `assets/` (gitignored). Missing ones (bed, shield, chest) get the letter tile.
- **Dev bar (top, `h` hides it):** tab, theme, scale (0.75/1/2 via css zoom), busy/idle status, panel size readout, reference overlay.
- **Overlay:** lays `../build/gui-preview/site/<tab>_<theme>.png` over the panel (opacity, difference blend, amp brightens the diff). The pngs only exist after the java preview tests have run. Use scale 2 for an exact match, the pngs are 2x.
- **Diff:** `python -I tools/cmp.py <tab> <dark|light>` (pillow + edge) renders the mock at 2x and writes `assets/cmp/side_*.png` (java left, mock right), `diff_*.png` (x4) and prints the mean abs diff. The tabs sit at 0.5 to 1.2 on 0..255; above 2 means something moved.
- **Edit:** `theme.css` colours + sizes, `shell.css` panel/sidebar/header/status, `kit.css` widgets, `tabs/*.css` per-screen layout, `devbar.css` mock-only chrome, `icons.js` icon paths (`icons-test.html` shows them all), `mock.js` behaviour and fake data.
- **Shoot:** `python -I tools/shoot.py <name> "tab=schematics&sc=empty" [w] [h]` (edge) writes `assets/shots/<name>.png` of the mock alone, for looking at a tab before the java exists. The schematics tab takes `?sc=loading|refreshing|empty|none|noworld|counting|nether|parsing|building|builderr`, and `&off=minecraft:glass,minecraft:torch` for which item rows start unchecked (glass by default, `&off=` for none).
- **New tab:** fill its `<section data-tab=...>` in `index.html` (`data-title`, `data-search` drive the header), add `tabs/<name>.css`, and `tabs/<name>.js` using `window.Mock` if it needs behaviour.
- **Hairlines** are 1 device px like the game (`--hl`), so they stay thin at any zoom.
- **Known off:** text can sit 1 device px (half a design px) from the java at 2x, baselines round differently. The settings detail strip is 81 tall, the 2x png has it 79.5 (java ceils font metrics per scale). The light Get png was shot with basket 194/12/20, the mock defaults to 129/11/10. Get tile pin button, goto path-dot dimming on idle rows, and scroll-thumb fade timing are not mocked exactly.

## title/ (boot + title screen)

Mock of the new boot and title screens, for shots before the java exists. Sizes go design px -> device px through the game's own chain (auto gui scale -> `KitSurface.scaleFor`), all rounded to whole px, no css transforms or transitions, so the java can match it 1:1.

- **Panorama:** `python -I gui-mock/extract-panorama.py [version]` copies `panorama_0..5.png` out of gradle's asset store into `assets/panorama/` (gitignored, the jar only has 1x1 placeholders). Without it the background is black.
- **Open:** `title/index.html`. Dev bar (`h` hides it): scene, boot progress, play boot -> title (tick reload for the fade-in path), replay entry, reduced motion, blur, extra mod tiles, demo, mp off, window / gui scale / kit scale readout. Tab, Enter, Space and the mouse work like the game.
- **Params:** `?scene=boot|title&progress=0.35&fade=<ms into the 2 s fade-out>&fadein=<ms into a reload fade-in>&t=<ms into the title entry>&hover=<id>&focus=<id>&press=<id>&mods=0..3&demo=1&dw=0&mpoff=1&friends=0&card=0&blur=0&rm=1&reload=1&spin=<deg>&mx=&my=&bare=1`. Ids: `sp mp friends realms mod1..3 options quit demo reset lang a11y card copyright`. `friends=0` is offline dev mode (tile inactive, demo always has it inactive). `t`, `fade` and `fadein` freeze the frame (panorama too), use `t=5000` for fully in.
- **Shoot:** `python -I tools/shoot.py title_idle "t=5000" --page title` (1920x1080), `python -I tools/shoot.py title_small "t=5000&hover=mp" 854 480 --page title`, `python -I tools/shoot.py boot_fade "scene=boot&fade=1500" --page title`. The size is the exact viewport.
- **Glyphs:** the title screen's new icons are in the marked block at the end of `ART` in `icons.js`, same helpers as the rest, waiting to be ported to `Icons.java`.
- **Guesses:** blur is css `blur(5.48px)`, the gaussian with the same variance as vanilla's three box blur rounds at radius 5. Shadows are a blurred filled box under the thing like the kit's nine slice (it darkens under the glass too), blur from kit.css's conversion (radius 26 -> 28px), not the spec's `0 12 40`. Tiles fade gradient and glyph separately like FadeCanvas.
