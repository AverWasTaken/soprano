# gui-mock

Browser mock of the click gui, matched to the java renderer. Plain html/css/js, no build.

- **Open:** double-click `index.html` (file://). The sidebar switches tabs, Light|Dark switches theme. Urls take `?tab=get&theme=light&scale=2&bare=1&ov=0.5&diff=1&basket=oak_log:194,stick:20`.
- **Textures:** `python -I gui-mock/extract-items.py [client.jar]` pulls item pngs from the client jar into `assets/` (gitignored). Missing ones (bed, shield, chest) get the letter tile.
- **Dev bar (top, `h` hides it):** tab, theme, scale (0.75/1/2 via css zoom), busy/idle status, panel size readout, reference overlay.
- **Overlay:** lays `../build/gui-preview/site/<tab>_<theme>.png` over the panel (opacity, difference blend, amp brightens the diff). The pngs only exist after the java preview tests have run. Use scale 2 for an exact match, the pngs are 2x.
- **Edit:** `theme.css` colours + sizes, `shell.css` panel/sidebar/header/status, `kit.css` widgets, `tabs/*.css` per-screen layout, `devbar.css` mock-only chrome, `icons.js` icon paths (`icons-test.html` shows them all), `mock.js` behaviour and fake data.
- **New tab:** fill its `<section data-tab=...>` in `index.html` (`data-title`, `data-search` drive the header), add `tabs/<name>.css`, and `tabs/<name>.js` using `window.Mock` if it needs behaviour.
- **Hairlines** are 1 device px like the game (`--hl`), so they stay thin at any zoom.
- **Known off:** text can sit 1 device px (half a design px) from the java at 2x, baselines round differently. The settings detail strip is 81 tall, the 2x png has it 79.5 (java ceils font metrics per scale). The light Get png was shot with basket 194/12/20, the mock defaults to 129/11/10. Get tile pin button, goto path-dot dimming on idle rows, and scroll-thumb fade timing are not mocked exactly.
