// boot screen + title screen mock. everything is laid out here in whole device px from design px, the same way
// the java will do it, so a shot of this is a shot of the game (minus the game). no css transforms on anything
// flat, no css transitions, no wall clock when ?t= or ?fade= is set. see ../README.md for the params
(function () {
    'use strict';

    // ---------- params ----------
    var Q = new URLSearchParams(location.search);
    function qn(k, d) { var v = Q.get(k); if (v == null || v === '') return d; var f = parseFloat(v); return isNaN(f) ? d : f; }
    function qb(k, d) { var v = Q.get(k); return v == null ? d : (v === '1' || v === 'true'); }
    function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
    function clamp01(v) { return clamp(v, 0, 1); }
    // Easing.outCubic, same clamp
    function outCubic(t) { t = clamp01(t); var u = 1 - t; return 1 - u * u * u; }

    var MC_VERSION = '26.3', MOD_VERSION = '1.1.6';
    // vanilla's menu blur option default. the blur post chain is three rounds of a horizontal + vertical box blur of
    // width 2r+1 at framebuffer res, so the variance is 3 * ((2r+1)^2 - 1) / 12 and the css blur (a gaussian sigma)
    // that matches it is sqrt of that: 5.48 device px at r = 5. three boxes are close enough to a gaussian to not tell
    var BLUR_OPTION = 5;
    var BLUR_SIGMA = Math.sqrt(3 * ((2 * BLUR_OPTION + 1) * (2 * BLUR_OPTION + 1) - 1) / 12);
    // CubeMap: 85 degree vertical fov, pitched 10 degrees down. Panorama: 0.1 degrees per realtime tick = 2 deg/s
    var PANO_FOV = 85, PANO_PITCH = 10, SPIN_PER_MS = 0.002, SPIN0 = 40;
    // the baked overlay, same stops for the java bake: vertical black 0.10 -> 0.45, then an elliptical vignette
    // (farthest corner = 1) going linearly from 0 in the middle to 0.25 black in the corners, drawn over it
    var SHADE = 'radial-gradient(ellipse farthest-corner at 50% 50%, rgba(0,0,0,0) 0%, rgba(0,0,0,0.25) 100%),'
        + 'linear-gradient(to bottom, rgba(0,0,0,0.10), rgba(0,0,0,0.45))';

    var opt = {
        scene: Q.get('scene') === 'boot' ? 'boot' : 'title',
        progress: clamp01(qn('progress', Q.has('fade') ? 1 : 0.35)),
        fade: Q.has('fade') ? qn('fade', 0) : null,
        fadein: Q.has('fadein') ? qn('fadein', 0) : null,
        t: Q.has('t') ? qn('t', 0) : null,
        hover: Q.get('hover') || null,
        focus: Q.get('focus') || null,
        press: Q.get('press') || null,
        mods: clamp(Math.round(qn('mods', 0)), 0, 3),
        demo: qb('demo', false), mpoff: qb('mpoff', false), friends: qb('friends', true) /* 0 = offline dev mode, tile inactive */, dw: qb('dw', true),
        blur: qb('blur', true), rm: qb('rm', false), card: qb('card', true), reload: qb('reload', false),
        spin: qn('spin', SPIN0),
        mx: Q.has('mx') ? qn('mx', 0) : null, my: Q.has('my') ? qn('my', 0) : null
    };
    // frozen = the frame is a pure function of the url. no raf, no clock, springs sit on their targets
    var frozen = opt.t != null || opt.fade != null || opt.fadein != null;
    var frozenMs = opt.fade != null ? opt.fade : opt.t != null ? opt.t : opt.fadein != null ? opt.fadein : 0;
    if (qb('bare', false)) document.body.classList.add('nobar');
    // ?vw=&vh=: pin the stage to an exact size at the top left. headless edge's viewport comes out a bit smaller than
    // its window, so shoot.py asks for a bigger window, pins the stage and crops
    if (Q.has('vw') && Q.has('vh')) {
        var pin = document.getElementById('stage');
        pin.style.right = pin.style.bottom = 'auto';
        pin.style.top = '0'; pin.style.width = qn('vw', 1920) + 'px'; pin.style.height = qn('vh', 1080) + 'px';
    }

    function animOn() { return !opt.rm; }

    // ---------- the scale chain ----------
    // vanilla Window.calculateScale with the option on auto: the biggest whole scale that leaves 320x240 gui units
    function guiScaleFor(w, h) {
        var i = 1;
        while (i < w && i < h && Math.floor(w / (i + 1)) >= 320 && Math.floor(h / (i + 1)) >= 240) i++;
        return i;
    }
    // KitSurface.scaleFor: half the gui scale in quarter steps, 0.75..2. design px * this = framebuffer px, which is
    // what UpdateCardWidget draws in (it scales the pose by 1/guiScale and hands the kit raw pixels)
    function kitScaleFor(g) {
        var raw = Math.max(1, g) * 0.5;
        var stepped = Math.round(raw * 4) / 4;
        return Math.max(0.75, Math.min(2.0, stepped));
    }

    // ---------- a port of kit/Spring.java, analytic so frozen frames are exact ----------
    function Spring(v) { this.snap(v); }
    Spring.prototype.snap = function (v) { this.from = v; this.to = v; this.vel = 0; this.omega = 0; this.start = 0; this.tol = 0; };
    Spring.prototype.set = function (target, now, ms) {
        if (target === this.to) return;
        var v = this.velocity(now), c = this.value(now);
        if (!animOn() || ms <= 0) { this.snap(target); return; }
        this.from = c; this.to = target; this.vel = v; this.start = now;
        this.omega = 10000 / ms;
        this.tol = 0.001 * Math.max(1, Math.abs(this.to - this.from));
    };
    Spring.prototype.secs = function (now) { return Math.max(0, now - this.start) / 1000; };
    Spring.prototype.disp = function (now) {
        var t = this.secs(now), d = this.from - this.to;
        return (d + (this.vel + this.omega * d) * t) * Math.exp(-this.omega * t);
    };
    Spring.prototype.rawVel = function (now) {
        if (this.omega === 0 || !animOn()) return 0;
        var t = this.secs(now), b = this.vel + this.omega * (this.from - this.to);
        return (this.vel - this.omega * b * t) * Math.exp(-this.omega * t);
    };
    Spring.prototype.running = function (now) {
        return animOn() && this.omega > 0 && Math.abs(this.disp(now)) + Math.abs(this.rawVel(now)) / this.omega > this.tol;
    };
    Spring.prototype.velocity = function (now) { return this.running(now) ? this.rawVel(now) : 0; };
    Spring.prototype.value = function (now) { return this.running(now) ? this.to + this.disp(now) : this.to; };

    // ---------- dom helpers ----------
    var $ = function (id) { return document.getElementById(id); };
    var stage = $('stage'), ui = $('ui');
    var W = 0, H = 0, dpr = 1, gui = 1, S = 1;
    function R(design) { return Math.round(design * S); }
    function css(px) { return (px / dpr) + 'px'; }
    function box(el, x, y, w, h) {
        el.style.left = css(x); el.style.top = css(y);
        if (w != null) el.style.width = css(w);
        if (h != null) el.style.height = css(h);
    }
    function mk(cls, parent, tag) {
        var e = document.createElement(tag || 'div');
        if (cls) e.className = cls;
        (parent || ui).appendChild(e);
        return e;
    }
    function show(el, on) { el.style.display = on ? '' : 'none'; }

    // text boxes are ascent + descent tall, like a java FontMetrics box, so "8 px below the text" means the same
    var meas = document.createElement('canvas').getContext('2d');
    var WEIGHT = { regular: 400, medium: 500, semibold: 600 };
    function fontOf(weight, px) { return WEIGHT[weight] + ' ' + px + 'px Inter'; }
    function metrics(weight, px) {
        meas.font = fontOf(weight, px);
        meas.letterSpacing = '0px';
        var m = meas.measureText('Hgp');
        return { asc: m.fontBoundingBoxAscent, h: Math.round(m.fontBoundingBoxAscent + m.fontBoundingBoxDescent) };
    }
    function textW(str, weight, px) { meas.font = fontOf(weight, px); meas.letterSpacing = '0px'; return Math.ceil(meas.measureText(str).width); }
    // sets the text and font, returns its box. caller places it
    function text(el, str, weight, design, color) {
        var px = R(design), m = metrics(weight, px);
        if (el.textContent !== str) el.textContent = str;
        el.style.font = fontOf(weight, px / dpr);
        el.style.lineHeight = css(m.h);
        el.style.height = css(m.h);
        if (color) el.style.color = color;
        return { w: textW(str, weight, px), h: m.h, asc: m.asc, px: px };
    }
    // greedy word wrap for the disabled tooltip's reason
    function wrap(str, weight, px, maxW) {
        var words = str.split(' '), lines = [], cur = '';
        words.forEach(function (w) {
            var next = cur ? cur + ' ' + w : w;
            if (cur && textW(next, weight, px) > maxW) { lines.push(cur); cur = w; } else cur = next;
        });
        if (cur) lines.push(cur);
        return lines;
    }

    // ---------- brand art. java bakes these into PixelImages at device size ----------
    function appIconSvg(px) {
        return '<svg width="' + (px / dpr) + '" height="' + (px / dpr) + '" viewBox="0 0 64 64">'
            + '<rect width="64" height="64" rx="14" fill="#0a73e8"/>'
            + '<path d="M16 44C30 44 26 20 48 20" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"/>'
            + '<circle cx="16" cy="44" r="5.5" fill="#34c759"/><circle cx="48" cy="20" r="5.5" fill="#ff453a"/></svg>';
    }
    function bootMarkSvg(px) {
        return '<svg width="' + (px / dpr) + '" height="' + (px / dpr) + '" viewBox="0 0 64 64">'
            + '<path d="M16 44C30 44 26 20 48 20" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"/>'
            + '<circle cx="16" cy="44" r="5.5" fill="#34c759"/><circle cx="48" cy="20" r="5.5" fill="#ff453a"/></svg>';
    }
    // the wordmark, baked like the java will: semibold, tracking -0.04em, cropped to the ink. the crop is what the
    // gaps are measured from, otherwise the 20 px under the icon turns into 57 because soprano has no ascenders
    var wordCache = {};
    function bakeWordmark(px) {
        if (wordCache[px]) return wordCache[px];
        var scratch = document.createElement('canvas');
        scratch.width = Math.ceil(px * 5); scratch.height = Math.ceil(px * 2);
        var c = scratch.getContext('2d');
        c.font = fontOf('semibold', px);
        c.letterSpacing = (-0.04 * px) + 'px';
        c.fillStyle = '#f5f5f7';
        c.textBaseline = 'alphabetic';
        var base = Math.round(px * 1.2);
        c.fillText('soprano', Math.round(px * 0.5), base);
        var d = c.getImageData(0, 0, scratch.width, scratch.height).data;
        var x0 = scratch.width, y0 = scratch.height, x1 = -1, y1 = -1;
        for (var y = 0; y < scratch.height; y++) {
            for (var x = 0; x < scratch.width; x++) {
                if (d[(y * scratch.width + x) * 4 + 3] > 0) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
            }
        }
        var out = document.createElement('canvas');
        out.width = Math.max(1, x1 - x0 + 1); out.height = Math.max(1, y1 - y0 + 1);
        out.getContext('2d').drawImage(scratch, x0, y0, out.width, out.height, 0, 0, out.width, out.height);
        out.style.position = 'absolute';
        wordCache[px] = out;
        return out;
    }

    // ---------- the dock's tiles ----------
    var EXTRA_MODS = ['Mods', 'Replay Viewer', 'Voice Chat'];
    var MP_TIP = 'Multiplayer is disabled. Please check your Microsoft account settings.';
    // vanilla's button messages with the trailing "..." dropped, a dock label with an ellipsis looks like a typo
    function tileList() {
        var t = [];
        if (opt.demo) {
            t.push({ id: 'demo', label: 'Play Demo World', glyph: 'player', c0: '#5AC8FA', c1: '#0A84FF' });
            t.push({ id: 'reset', label: 'Reset Demo World', glyph: 'reset', c0: '#FF9F0A', c1: '#C93400', off: !opt.dw });
            // vanilla never hides friends, demo just makes it inactive
            t.push({ id: 'friends', label: 'Friends', glyph: 'people', c0: '#FF6482', c1: '#D6336C', off: true });
        } else {
            t.push({ id: 'sp', label: 'Singleplayer', glyph: 'player', c0: '#5AC8FA', c1: '#0A84FF' });
            t.push({ id: 'mp', label: 'Multiplayer', glyph: 'globe', c0: '#34C759', c1: '#248A3D', off: opt.mpoff, tip: opt.mpoff ? MP_TIP : null });
            t.push({ id: 'friends', label: 'Friends', glyph: 'people', c0: '#FF6482', c1: '#D6336C', off: !opt.friends });
            t.push({ id: 'realms', label: 'Minecraft Realms', glyph: 'cloud', c0: '#BF5AF2', c1: '#8944AB', off: opt.mpoff, tip: opt.mpoff ? MP_TIP : null });
        }
        for (var i = 0; i < opt.mods; i++) t.push({ id: 'mod' + (i + 1), label: EXTRA_MODS[i], glyph: 'puzzle', c0: '#FF9F0A', c1: '#C93400' });
        t.push({ id: 'options', label: 'Options', glyph: 'gear', c0: '#AEAEB2', c1: '#636366' });
        t.push({ id: 'quit', label: 'Quit Game', glyph: 'power', c0: '#3A3A3C', c1: '#1C1C1E' });
        return t;
    }
    // disabled tiles go grey: each stop becomes its own rec. 709 luma, so light stays light and dark stays dark
    function grey(hex) {
        var n = parseInt(hex.slice(1), 16), r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
        var l = Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
        return 'rgb(' + l + ',' + l + ',' + l + ')';
    }

    function withA(c, a) {
        var m = /^rgb\((\d+),(\d+),(\d+)\)$/.exec(c), r, g, b;
        if (m) { r = +m[1]; g = +m[2]; b = +m[3]; } else { var n = parseInt(c.slice(1), 16); r = n >> 16 & 255; g = n >> 8 & 255; b = n & 255; }
        return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
    }

    // ---------- scene graph (built once per config) ----------
    var el = {}, tiles = [], springs = {};
    function build() {
        ui.innerHTML = '';
        el.card = mk('card-ph'); el.cardTxt = mk('txt', el.card);
        el.icon = mk('');
        el.word = null;
        el.sub = mk('txt');
        el.cardSh = mk('shadow'); ui.insertBefore(el.cardSh, el.card);
        el.pillSh = mk('shadow');
        el.pill = mk('pill');
        el.divider = mk('divider');
        tiles = tileList().map(function (t) {
            var e = mk('tile');
            t.el = e;
            t.glyphEl = mk('', e);
            t.pressEl = mk('press', e);
            if (!springs[t.id]) springs[t.id] = new Spring(1);
            return t;
        });
        el.langSh = mk('shadow'); el.a11ySh = mk('shadow');
        el.lang = mk('glass'); el.a11y = mk('glass');
        el.lang.innerHTML = el.a11y.innerHTML = '';
        el.ver = mk('txt');
        el.copy = mk('txt');
        el.under = mk('under');
        el.ring = mk('ring');
        el.label = mk('label'); el.label1 = mk('txt', el.label); el.label2 = mk('txt', el.label);
    }
    function glyph(holder, key, name, px) {
        var k = key + ':' + name + ':' + px;
        if (holder._k === k) return;
        holder._k = k;
        holder.innerHTML = Icons.svg(name, px);
        var s = holder.firstChild;
        s.setAttribute('width', px / dpr); s.setAttribute('height', px / dpr);
        holder.style.position = 'absolute';
    }

    // ---------- live state ----------
    var st = {
        scene: opt.scene,
        progress: opt.progress,
        play: null,         // a running boot: {t0, cur, fadeStart, reload}
        entryStart: null,   // ms the title entry started, null = already in
        t0: performance.now()
    };
    var mouse = null, pressId = null, focusId = opt.focus, hoverId = null;
    var label = { id: null, since: 0 };
    var par = { bx: 0, by: 0, dx: 0, dy: 0, last: 0 };
    var hits = [];
    var bootUp = false;  // the boot overlay was up last frame, vanilla sends the screen no clicks then   // [{id, x, y, w, h}] from the last frame, device px

    function startPlay(now) {
        st.scene = 'boot';
        st.play = { t0: now, cur: 0, fadeStart: null, reload: !!$('dv-reload').checked };
    }

    // what the clocks say for this frame: boot overlay state, whether the title is drawn, entry ms (Infinity = in)
    function bootOut(f, progress) {
        // vanilla's 2 s fade out. first second on solid black (the screen under it isn't drawn yet), second second
        // the black goes away linearly while the title plays its entry under it
        var e = outCubic(f / 600);
        return {
            bg: f < 1000 ? 1 : clamp01(1 - (f - 1000) / 1000),
            barA: clamp01(1 - f / 250),
            markA: animOn() ? 1 - e : clamp01(1 - f / 600),
            markScale: animOn() ? 1 + 0.08 * e : 1,
            progress: progress
        };
    }
    function timeline(now) {
        if (frozen) {
            if (opt.fadein != null) {
                var a = clamp01(opt.fadein / 500);
                return { boot: { bg: a, barA: a, markA: a, markScale: 1, progress: opt.progress }, title: true, entry: Infinity };
            }
            if (opt.fade != null) {
                var f = Math.max(0, opt.fade);
                return {
                    boot: f < 2000 ? bootOut(f, opt.progress) : null,
                    title: f >= 1000,
                    entry: opt.reload ? Infinity : f - 1000
                };
            }
            if (opt.scene === 'boot') return { boot: { bg: 1, barA: 1, markA: 1, markScale: 1, progress: opt.progress }, title: false, entry: Infinity };
            return { boot: null, title: true, entry: opt.t };
        }
        var p = st.play;
        if (p) {
            var since = now - p.t0;
            var fi = p.reload ? clamp01(since / 500) : 1;
            // fake reload: done after 2.4 s. the bar smooths it the way vanilla does, 95/5 per frame
            var actual = clamp01(since / 2400);
            p.cur = p.cur * 0.95 + actual * 0.05;
            if (p.fadeStart == null && actual >= 1 && fi >= 1) p.fadeStart = now;
            if (p.fadeStart == null) {
                return { boot: { bg: fi, barA: fi, markA: fi, markScale: 1, progress: p.cur }, title: p.reload, entry: Infinity };
            }
            var ff = now - p.fadeStart;
            if (ff >= 2000) {
                st.play = null; st.scene = 'title';
                st.entryStart = p.reload ? null : p.fadeStart + 1000;
            } else {
                return { boot: bootOut(ff, p.cur), title: ff >= 1000, entry: p.reload ? Infinity : ff - 1000 };
            }
        }
        if (st.scene === 'boot') return { boot: { bg: 1, barA: 1, markA: 1, markScale: 1, progress: st.progress }, title: false, entry: Infinity };
        return { boot: null, title: true, entry: st.entryStart == null ? Infinity : now - st.entryStart };
    }

    // ---------- panorama ----------
    var FACE_T = ['', 'rotateY(-90deg)', 'rotateY(180deg)', 'rotateY(90deg)', 'rotateX(-90deg)', 'rotateX(90deg)'];
    var OVERSCAN = 48;
    function drawPanorama(now, visible) {
        var pano = $('pano'), persp = $('persp'), cube = $('cube');
        show(pano, visible);
        if (!visible) return;
        var wc = stage.clientWidth, hc = stage.clientHeight;
        pano.style.left = pano.style.top = (-OVERSCAN) + 'px';
        pano.style.width = (wc + 2 * OVERSCAN) + 'px'; pano.style.height = (hc + 2 * OVERSCAN) + 'px';
        persp.style.left = persp.style.top = OVERSCAN + 'px';
        persp.style.width = wc + 'px'; persp.style.height = hc + 'px';
        var d = (hc / 2) / Math.tan(PANO_FOV / 2 * Math.PI / 180);
        persp.style.perspective = d + 'px';
        var faces = cube.children;
        for (var i = 0; i < faces.length; i++) {
            var f = faces[i], k = +f.dataset.face;
            // a hair over 2d so the seams don't show the black behind them
            f.style.width = f.style.height = (2 * d + 2) + 'px';
            f.style.left = f.style.top = (-d - 1) + 'px';
            f.style.transform = FACE_T[k] + ' translateZ(' + (-d) + 'px)';
        }
        var spin = opt.spin + (frozen ? frozenMs : now - st.t0) * SPIN_PER_MS;
        cube.style.transform = 'translateZ(' + d + 'px) rotateX(' + (-PANO_PITCH) + 'deg) rotateY(' + (-spin) + 'deg)';
        pano.style.filter = opt.blur ? 'blur(' + (BLUR_SIGMA / dpr) + 'px)' : 'none';
    }

    // ---------- boot overlay ----------
    function drawBoot(b) {
        var boot = $('boot');
        show(boot, !!b);
        if (!b) return;
        $('boot-bg').style.opacity = b.bg;
        var markBox = R(112), cy = Math.round(H / 2 - R(36));
        var m = Math.round(markBox * b.markScale);
        var mark = $('boot-mark');
        if (mark._px !== m) { mark.innerHTML = bootMarkSvg(m); mark._px = m; }
        box(mark, Math.round(W / 2 - m / 2), Math.round(cy - m / 2), m, m);
        // during a reload fade in the mark comes in with the background; during fade out it has its own alpha
        mark.style.opacity = Math.min(b.markA, b.bg);
        var bw = R(240), bh = R(6), br = R(3);
        var track = $('boot-track'), fill = $('boot-fill');
        var top = Math.round(cy - markBox / 2) + markBox + R(64);
        box(track, Math.round(W / 2 - bw / 2), top, bw, bh);
        track.style.borderRadius = css(br);
        track.style.opacity = Math.min(b.barA, b.bg);
        var fw = Math.round(bw * clamp01(b.progress));
        box(fill, 0, 0, fw, bh);
        fill.style.borderRadius = css(Math.min(br, Math.floor(fw / 2)));
    }

    // ---------- the title screen ----------
    var GLASS_BG = 'rgba(255,255,255,0.14)', GLASS_BG_HOVER = 'rgba(255,255,255,0.22)', GLASS_LINE = 'rgba(255,255,255,0.22)';
    // the kit shadow takes the box radius: blur about 1.5x, pushed down by half. css wants it as a blur radius, kit.css
    // already settled that a radius 12 card is "0 6px 13px", same ratio here
    // a filled box under the thing, not a css box-shadow: the kit's nine slice darkens the inside too, so the glass
    // shows it through its 14% fill. the css blur here stands in for the kit's prebaked shadow texture
    function kitShadow(e, x, y, w, h, radius, alpha, opacity) {
        box(e, x, y + Math.round(radius / 2), w, h);
        e.style.borderRadius = css(radius);
        e.style.background = 'rgba(0,0,0,' + alpha + ')';
        e.style.filter = 'blur(' + css(Math.round(radius * 13 / 12) / 2) + ')';
        e.style.opacity = opacity;
    }
    function glass(e, sh, x, y, b, hovered, alpha) {
        var r = Math.floor(b / 2);
        kitShadow(sh, x, y, b, b, r, 0.35, alpha);
        box(e, x, y, b, b);
        e.style.borderRadius = css(r);
        e.style.background = hovered ? GLASS_BG_HOVER : GLASS_BG;
        e.style.boxShadow = 'inset 0 0 0 ' + css(1) + ' ' + GLASS_LINE;
        e.style.opacity = alpha;
    }

    function drawTitle(v, now) {
        show(ui, v.title);
        $('shade').style.display = v.title ? '' : 'none';
        if (!v.title) { hits = []; return; }

        var et = animOn() ? v.entry : Infinity;
        var eBrand = outCubic(et / 800), eSub = outCubic((et - 120) / 800), eDock = outCubic((et - 200) / 700),
            eCorner = outCubic((et - 400) / 600);

        // where the mouse is for this frame. frozen shots put it on the hovered thing (or the middle)
        var mx, my;
        if (mouse) { mx = mouse.x; my = mouse.y; } else { mx = W / 2; my = H / 2; }

        // parallax, with the mouse. exponential follow at 120 ms, off for reduced motion
        function parallaxTarget(x, y) {
            var nx = clamp((x - W / 2) / (W / 2), -1, 1), ny = clamp((y - H / 2) / (H / 2), -1, 1);
            return { bx: 8 * nx, by: 5 * ny, dx: 3 * nx, dy: 2 * ny };
        }
        function followPar(x, y) {
            var tg = parallaxTarget(x, y);
            if (!animOn()) { par.bx = par.by = par.dx = par.dy = 0; return; }
            if (frozen || !par.last) { par.bx = tg.bx; par.by = tg.by; par.dx = tg.dx; par.dy = tg.dy; }
            else {
                var k = 1 - Math.exp(-(now - par.last) / 120);
                par.bx += (tg.bx - par.bx) * k; par.by += (tg.by - par.by) * k;
                par.dx += (tg.dx - par.dx) * k; par.dy += (tg.dy - par.dy) * k;
            }
            par.last = now;
        }

        // ----- dock geometry, unmagnified -----
        var n = tiles.length;
        var T = 64;
        // fit rule, in design px: everything unmagnified, the divider slot is its 1 px plus 4 each side plus a gap
        var fixedD = 2 * 12 + (n - 1) * 14 + (1 + 2 * 4 + 14);
        if ((fixedD + n * T) * S > 0.9 * W) T = Math.max(40, Math.floor((0.9 * W / S - fixedD) / n));
        var tPx = R(T), pad = R(12), gap = R(14), dm = R(4);
        var pillH = 2 * pad + tPx;

        function dockRow(sizes, dx) {
            var total = 2 * pad + (n - 1) * gap + (2 * dm + 1 + gap);
            sizes.forEach(function (s) { total += s; });
            var x0 = Math.round(W / 2 - total / 2) + dx;
            var xs = [], x = x0 + pad, divX = 0;
            for (var i = 0; i < n; i++) {
                // quit sits behind the divider: gap, 4, the 1 px line, 4, gap
                if (i === n - 1) { x += gap + dm; divX = x; x += 1 + dm + gap; }
                xs.push(x);
                x += sizes[i] + (i < n - 2 ? gap : 0);
            }
            return { x0: x0, w: total, xs: xs, divX: divX };
        }

        // frozen hover: aim the mouse at the thing's resting centre. two rounds because parallax moves the dock
        var cornerB = R(36), inset = R(24), cornerGap = R(10);
        function restCentre(id, dx, dy) {
            var rest = dockRow(tiles.map(function () { return tPx; }), Math.round(dx));
            var bottom = H - R(72) + Math.round(dy);
            for (var i = 0; i < n; i++) if (tiles[i].id === id) return { x: rest.xs[i] + tPx / 2, y: bottom - pad - tPx / 2 };
            if (id === 'lang') return { x: inset + cornerB / 2, y: inset + cornerB / 2 };
            if (id === 'a11y') return { x: inset + cornerB + cornerGap + cornerB / 2, y: inset + cornerB / 2 };
            if (id === 'copyright') return { x: W - inset - R(100), y: H - inset - R(8) };
            return null;
        }
        if (!mouse && opt.hover) {
            var c = restCentre(opt.hover, 0, 0);
            if (c) {
                for (var it = 0; it < 2; it++) {
                    var pt = parallaxTarget(c.x, c.y);
                    c = restCentre(opt.hover, animOn() ? pt.dx * S : 0, animOn() ? pt.dy * S : 0) || c;
                }
                mx = c.x; my = c.y;
            }
        }
        if (!mouse && opt.mx != null) { mx = opt.mx; my = opt.my != null ? opt.my : my; }
        // the game hands the title a mouse at 0,0 while the boot overlay is up, the java holds the target at the
        // middle until it's gone. same here
        if (v.boot) followPar(W / 2, H / 2); else followPar(mx, my);

        var pbx = Math.round(par.bx * S), pby = Math.round(par.by * S), pdx = Math.round(par.dx * S), pdy = Math.round(par.dy * S);

        // ----- brand block -----
        var iconPx = R(72);
        var word = bakeWordmark(R(88));
        if (el.word !== word) { if (el.word && el.word.parentNode) el.word.parentNode.removeChild(el.word); ui.insertBefore(word, el.sub); el.word = word; }
        var sub = text(el.sub, 'for Minecraft ' + MC_VERSION, 'medium', 18, 'rgba(255,255,255,0.62)');
        var blockH = iconPx + R(20) + word.height + R(8) + sub.h;
        var top = Math.round(H * 0.38 - blockH / 2) + pby;
        var dyBrand = R(18 * (1 - eBrand)), dySub = R(18 * (1 - eSub));
        if (el.icon._px !== iconPx) { el.icon.innerHTML = appIconSvg(iconPx); el.icon._px = iconPx; }
        box(el.icon, Math.round(W / 2 - iconPx / 2) + pbx, top + dyBrand, iconPx, iconPx);
        el.icon.style.opacity = eBrand;
        var wy = top + iconPx + R(20);
        box(word, Math.round(W / 2 - word.width / 2) + pbx, wy + dyBrand, word.width, word.height);
        word.style.opacity = eBrand;
        box(el.sub, Math.round(W / 2 - sub.w / 2) + pbx, wy + word.height + R(8) + dySub, sub.w + 2, null);
        el.sub.style.opacity = eSub;

        // ----- dock -----
        var dockDy = R(40 * (1 - eDock));
        var pillBottom = H - R(72) + pdy + dockDy, pillTop = pillBottom - pillH;
        var rest = dockRow(tiles.map(function () { return tPx; }), pdx);
        // while the boot overlay is up the screen only ever sees a mouse at 0,0, so nothing grows or hovers yet
        var inBand = !v.boot && my >= pillTop - R(40) && my <= pillBottom && (mouse || opt.hover || opt.mx != null);
        var sizes = tiles.map(function (t, i) {
            var target = 1;
            if (inBand && !t.off) target = 1 + 0.35 * Math.max(0, 1 - Math.abs(mx - (rest.xs[i] + tPx / 2)) / (150 * S));
            var sp = springs[t.id];
            if (frozen) sp.snap(target); else sp.set(target, now, 180);
            return Math.round(tPx * sp.value(now));
        });
        var row = dockRow(sizes, pdx);
        var pillR = Math.min(R(26), Math.floor(pillH / 2));
        box(el.pill, row.x0, pillTop, row.w, pillH);
        el.pill.style.borderRadius = css(pillR);
        el.pill.style.background = 'rgba(255,255,255,0.14)';
        el.pill.style.boxShadow = 'inset 0 0 0 ' + css(1) + ' rgba(255,255,255,0.22)';
        kitShadow(el.pillSh, row.x0, pillTop, row.w, pillH, pillR, 0.35, eDock);
        el.pill.style.opacity = eDock;
        var divH = R(40 * T / 64);
        box(el.divider, row.divX, Math.round(pillTop + pillH / 2 - divH / 2), 1, divH);
        el.divider.style.opacity = eDock;

        var newHits = [];
        var tileRects = {};
        tiles.forEach(function (t, i) {
            var s = sizes[i], x = row.xs[i], y = pillBottom - pad - s;
            tileRects[t.id] = { x: x, y: y, w: s, h: s, r: Math.round(s * 0.234) };
            newHits.push({ id: t.id, x: x, y: y, w: s, h: s });
            var pressed = (pressId === t.id || (frozen && opt.press === t.id)) && !t.off;
            var ds = pressed ? Math.round(s * 0.94) : s;
            var ox = x + Math.round((s - ds) / 2), oy = y + Math.round((s - ds) / 2);
            box(t.el, ox, oy, ds, ds);
            t.el.style.borderRadius = css(Math.round(ds * 0.234));
            // FadeCanvas multiplies alpha into each draw, so the gradient and the glyph fade on their own. a 0.4 tile
            // shows its gradient through the white glyph, css opacity on the whole tile would hide that
            var ta = eDock * (t.off ? 0.4 : 1);
            t.el.style.background = 'linear-gradient(to bottom, ' + withA(t.off ? grey(t.c0) : t.c0, ta) + ', '
                + withA(t.off ? grey(t.c1) : t.c1, ta) + ')';
            t.glyphEl.style.opacity = ta;
            t.pressEl.style.opacity = ta;
            var g = Math.round(ds * 0.47);
            glyph(t.glyphEl, 't', t.glyph, g);
            box(t.glyphEl, Math.round((ds - g) / 2), Math.round((ds - g) / 2), g, g);
            show(t.pressEl, pressed);
        });

        // ----- corners -----
        var aLang = (hoverId === 'lang'), aA11y = (hoverId === 'a11y');
        glass(el.lang, el.langSh, inset, inset, cornerB, aLang, eCorner);
        glass(el.a11y, el.a11ySh, inset + cornerB + cornerGap, inset, cornerB, aA11y, eCorner);
        var cg = Math.round(cornerB * 0.5);
        glyph(el.lang, 'c', 'language', cg);
        glyph(el.a11y, 'c', 'accessibility', cg);
        [el.lang, el.a11y].forEach(function (e) {
            var s = e.firstChild;
            s.style.left = css(Math.round((cornerB - cg) / 2)); s.style.top = css(Math.round((cornerB - cg) / 2));
        });
        var cornerRects = {
            lang: { x: inset, y: inset, w: cornerB, h: cornerB, r: Math.floor(cornerB / 2) },
            a11y: { x: inset + cornerB + cornerGap, y: inset, w: cornerB, h: cornerB, r: Math.floor(cornerB / 2) }
        };
        newHits.push({ id: 'lang', x: inset, y: inset, w: cornerB, h: cornerB });
        newHits.push({ id: 'a11y', x: inset + cornerB + cornerGap, y: inset, w: cornerB, h: cornerB });

        var ver = text(el.ver, 'Soprano ' + MOD_VERSION, 'medium', 13, 'rgba(255,255,255,0.70)');
        box(el.ver, inset, H - inset - ver.h, ver.w + 2, null);
        el.ver.style.opacity = eCorner;
        var cp = text(el.copy, 'Copyright Mojang AB. Do not distribute!', 'regular', 13, 'rgba(255,255,255,0.50)');
        var cpx = W - inset - cp.w, cpy = H - inset - cp.h;
        box(el.copy, cpx, cpy, cp.w + 2, null);
        el.copy.style.opacity = eCorner;
        newHits.push({ id: 'copyright', x: cpx, y: cpy, w: cp.w, h: cp.h });
        var ul = (hoverId === 'copyright' || focusId === 'copyright');
        show(el.under, ul);
        if (ul) {
            var th = Math.max(1, Math.round(S));
            box(el.under, cpx, cpy + Math.round(cp.asc) + th, cp.w, th);
            el.under.style.color = 'rgba(255,255,255,0.50)';
            el.under.style.opacity = eCorner;
        }

        // the update card is its own widget and unchanged, this is only where it goes and about how big it is
        show(el.card, opt.card); show(el.cardSh, opt.card);
        var cardRect = null;
        if (opt.card) {
            var cw = R(300), ch = R(88);
            box(el.card, W - inset - cw, inset, cw, ch);
            el.card.style.borderRadius = css(R(12));
            el.card.style.boxShadow = 'inset 0 0 0 ' + css(1) + ' rgba(255,255,255,0.10)';
            kitShadow(el.cardSh, W - inset - cw, inset, cw, ch, R(12), 0.5, eCorner);
            cardRect = { x: W - inset - cw, y: inset, w: cw, h: ch, r: R(12) };
            newHits.push({ id: 'card', x: cardRect.x, y: cardRect.y, w: cw, h: ch });
            el.card.style.opacity = eCorner;
            var ct = text(el.cardTxt, 'update card (existing)', 'regular', 12);
            box(el.cardTxt, Math.round((cw - ct.w) / 2), Math.round((ch - ct.h) / 2), ct.w + 2, null);
        }

        // hover comes from what was drawn (magnified rects), frozen shots say it outright
        if (frozen || !mouse) hoverId = opt.hover;
        else hoverId = v.boot ? null : hitTest(mouse.x, mouse.y, newHits);
        bootUp = !!v.boot;
        hits = newHits;

        // ----- focus ring -----
        var fr = focusId && (tileRects[focusId] || cornerRects[focusId] || (focusId === 'card' && cardRect));
        show(el.ring, !!fr);
        if (fr) {
            var off = R(3), thk = 2;
            box(el.ring, fr.x - off - thk, fr.y - off - thk, fr.w + 2 * (off + thk), fr.h + 2 * (off + thk));
            el.ring.style.borderWidth = css(thk);
            el.ring.style.borderRadius = css(fr.r + off + thk);
            el.ring.style.opacity = focusId === 'lang' || focusId === 'a11y' || focusId === 'card' ? eCorner : eDock;
        }

        // ----- label: the hovered thing, else the focused one -----
        // only things with a label can take it off the focused one: the card and copyright have none
        var labelled = function (id) { return id && id !== 'copyright' && id !== 'card'; };
        var lid = labelled(hoverId) ? hoverId : (labelled(focusId) ? focusId : null);
        var lt = null, rect = null, below = false;
        if (lid) {
            for (var i = 0; i < n; i++) if (tiles[i].id === lid) { lt = tiles[i]; rect = tileRects[lid]; }
            if (lid === 'lang') { lt = { label: 'Language' }; rect = cornerRects.lang; below = true; }
            if (lid === 'a11y') { lt = { label: 'Accessibility Settings' }; rect = cornerRects.a11y; below = true; }
        }
        if (lid !== label.id) { label.id = lid; label.since = now; }
        show(el.label, !!lt);
        if (lt) {
            var l1 = text(el.label1, lt.label, 'medium', 14, '#fff');
            var lines = [], l2 = null;
            if (lt.tip) {
                var px13 = R(13);
                lines = wrap(lt.tip, 'regular', px13, R(260));
                l2 = text(el.label2, '', 'regular', 13, 'rgba(255,255,255,0.70)');
                el.label2.innerHTML = '';
                lines.forEach(function (s, k) { if (k) el.label2.appendChild(document.createElement('br')); el.label2.appendChild(document.createTextNode(s)); });
                el.label2.style.height = css(l2.h * lines.length);
            }
            show(el.label2, !!lt.tip);
            var px10 = R(10), py5 = R(5), lgap = R(2);
            var innerW = l1.w;
            lines.forEach(function (s) { innerW = Math.max(innerW, textW(s, 'regular', R(13))); });
            var lw = innerW + 2 * px10;
            var lh = l1.h + 2 * py5 + (lines.length ? lgap + l2.h * lines.length : 0);
            var lx = Math.round(rect.x + rect.w / 2 - lw / 2);
            lx = clamp(lx, R(8), W - R(8) - lw);
            // above the tallest tile, not just this one: a hovered disabled tile stays small while its
            // neighbours grow, and the label sat right on their heads
            var rowTop = rect.y;
            if (!below && (lid === hoverId || inBand)) for (var q in tileRects) rowTop = Math.min(rowTop, tileRects[q].y);
            var ly = below ? rect.y + rect.h + R(8) : rowTop - R(10) - lh;
            box(el.label, lx, ly, lw, lh);
            el.label.style.borderRadius = css(R(8));
            box(el.label1, px10, py5, l1.w + 2, null);
            if (lines.length) box(el.label2, px10, py5 + l1.h + lgap, innerW + 2, null);
            var la = (frozen || !animOn()) ? 1 : clamp01((now - label.since) / 120);
            el.label.style.opacity = la * (below ? eCorner : eDock);
        }
    }

    function hitTest(x, y, list) {
        for (var i = 0; i < list.length; i++) {
            var h = list[i];
            if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) return h.id;
        }
        return null;
    }

    // ---------- frame ----------
    function measure() {
        dpr = window.devicePixelRatio || 1;
        W = Math.round(stage.clientWidth * dpr); H = Math.round(stage.clientHeight * dpr);
        gui = guiScaleFor(W, H);
        S = kitScaleFor(gui);
    }
    function frame(now) {
        measure();
        var v = timeline(now);
        drawPanorama(now, v.title);
        drawBoot(v.boot);
        drawTitle(v, now);
        $('dv-size').textContent = W + 'x' + H + '  gui ' + gui + '  kit ' + S + 'x';
    }
    function loop(now) {
        frame(now);
        requestAnimationFrame(loop);
    }

    // ---------- input (live only) ----------
    function devPoint(e) {
        var r = stage.getBoundingClientRect();
        return { x: (e.clientX - r.left) * dpr, y: (e.clientY - r.top) * dpr };
    }
    function isOff(id) { for (var i = 0; i < tiles.length; i++) if (tiles[i].id === id) return !!tiles[i].off; return false; }
    function nameOf(id) {
        for (var i = 0; i < tiles.length; i++) if (tiles[i].id === id) return tiles[i].label;
        return { lang: 'Language', a11y: 'Accessibility Settings', copyright: 'credits', card: 'update card' }[id] || id;
    }
    function activate(id) {
        if (!id || isOff(id)) return;
        $('dv-said').textContent = 'pressed: ' + nameOf(id);
    }
    function focusOrder() {
        var o = [];
        tiles.forEach(function (t) { if (!t.off) o.push(t.id); });
        // the update card is a focusable vanilla widget too, it goes after the corner buttons
        return o.concat(opt.card ? ['lang', 'a11y', 'card', 'copyright'] : ['lang', 'a11y', 'copyright']);
    }
    function wire() {
        stage.addEventListener('mousemove', function (e) { mouse = devPoint(e); });
        stage.addEventListener('mouseleave', function () { mouse = null; pressId = null; });
        stage.addEventListener('mousedown', function (e) {
            if (e.button !== 0) return;
            mouse = devPoint(e);
            focusId = null;
            var id = hitTest(mouse.x, mouse.y, hits);
            pressId = id && !isOff(id) ? id : null;
            // vanilla buttons fire on press, not release. the press look only lasts while it's held
            if (bootUp) pressId = null;
            if (pressId) activate(pressId);
        });
        window.addEventListener('mouseup', function (e) {
            if (e.button !== 0 || !pressId) return;
            pressId = null;
        });
        window.addEventListener('keydown', function (e) {
            var tag = (e.target && e.target.tagName) || '';
            if (tag === 'INPUT' || tag === 'SELECT' || tag === 'BUTTON') { if (e.key !== 'Tab') return; }
            if (e.key === 'h' || e.key === 'H') { document.body.classList.toggle('nobar'); document.documentElement.classList.remove('bare'); return; }
            if (e.key === 'Tab') {
                e.preventDefault();
                var o = focusOrder(), i = o.indexOf(focusId);
                i = i < 0 ? (e.shiftKey ? o.length - 1 : 0) : (i + (e.shiftKey ? -1 : 1) + o.length) % o.length;
                focusId = o[i];
            } else if ((e.key === 'Enter' || e.key === ' ') && focusId) {
                e.preventDefault(); activate(focusId);
            } else if (e.key === 'Escape') focusId = null;
        });
        window.addEventListener('resize', function () { if (frozen) frame(0); });

        // dev bar
        var scene = $('dv-scene'), prog = $('dv-progress');
        scene.value = opt.scene; prog.value = opt.progress;
        $('dv-rm').checked = opt.rm; $('dv-blur').checked = opt.blur; $('dv-mods').value = String(opt.mods);
        $('dv-demo').checked = opt.demo; $('dv-mpoff').checked = opt.mpoff; $('dv-reload').checked = opt.reload;
        function goLive() { if (frozen) { frozen = false; opt.hover = opt.press = null; requestAnimationFrame(loop); } }
        scene.addEventListener('change', function () { goLive(); st.play = null; st.scene = scene.value; st.entryStart = null; });
        prog.addEventListener('input', function () { goLive(); st.play = null; st.scene = 'boot'; scene.value = 'boot'; st.progress = +prog.value; });
        $('dv-play').addEventListener('click', function () { goLive(); startPlay(performance.now()); scene.value = 'boot'; });
        $('dv-replay').addEventListener('click', function () { goLive(); st.play = null; st.scene = 'title'; scene.value = 'title'; st.entryStart = performance.now(); });
        $('dv-rm').addEventListener('change', function (e) { goLive(); opt.rm = e.target.checked; });
        $('dv-blur').addEventListener('change', function (e) { goLive(); opt.blur = e.target.checked; });
        $('dv-reload').addEventListener('change', function (e) { opt.reload = e.target.checked; });
        $('dv-mods').addEventListener('change', function (e) { goLive(); opt.mods = +e.target.value; build(); });
        $('dv-demo').addEventListener('change', function (e) { goLive(); opt.demo = e.target.checked; build(); });
        $('dv-mpoff').addEventListener('change', function (e) { goLive(); opt.mpoff = e.target.checked; build(); });
    }

    // ---------- boot ----------
    $('shade').style.background = SHADE;
    var faces = Array.prototype.slice.call(document.querySelectorAll('.face'));
    var missing = false;
    var waits = faces.map(function (img) {
        return new Promise(function (res) {
            function done() { if (!img.naturalWidth) missing = true; res(); }
            if (img.complete) done(); else { img.addEventListener('load', done); img.addEventListener('error', done); }
        });
    });
    ['400', '500', '600'].forEach(function (w) { waits.push(document.fonts.load(w + ' 32px Inter').catch(function () {})); });
    Promise.all(waits).then(function () {
        build();
        wire();
        if (!frozen && st.scene === 'title') st.entryStart = performance.now();
        // one frame right away either way, raf can take its time in a background tab
        frame(frozen ? 0 : performance.now());
        if (!frozen) requestAnimationFrame(loop);
        if (missing) $('dv-said').textContent = 'no panorama: python -I gui-mock/extract-panorama.py';
        // shoot.py and anyone scripting this waits on these
        document.documentElement.dataset.ready = missing ? 'nopano' : '1';
        document.title = 'ready';
    });
})();
