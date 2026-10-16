// every icon from kit/Icons.java as svg, transcribed call for call (same helpers, same coordinates) so the mock
// can't drift from the mod. plain script, no imports, works from file://.
//
// the mapping, straight out of Icons.draw: stroke sw = max(1.25, size*2/24) px, lo = 0.5 + sw/2 (the stroke's outer
// edge sits half a pixel inside the box, not just half a stroke), scale = (size - 2*lo) / 20 px per grid unit, and grid
// coordinate g lands at lo + (g - 2) * scale. svg does the same thing if the viewBox starts at 2 - lo/scale and is
// size/scale units wide, rendered at size px. so the viewBox is computed per size (16px: 0.2927 0.2927 23.4146 etc),
// and the stroke in grid units is w = sw / scale, exactly the w the java hands to BasicStroke.
(function () {
    'use strict';

    var GRID_LO = 2, GRID_SPAN = 20, STROKE_UNITS = 2;

    function strokePx(size) { return Math.max(1.25, size * STROKE_UNITS / 24); }
    function n(v) { return String(Math.round(v * 1000) / 1000); }

    // ---- path builder, mirrors Path2D.Float ----
    function P() {
        var d = [];
        var o = {
            moveTo: function (x, y) { d.push('M' + n(x) + ' ' + n(y)); return o; },
            lineTo: function (x, y) { d.push('L' + n(x) + ' ' + n(y)); return o; },
            quadTo: function (x1, y1, x, y) { d.push('Q' + n(x1) + ' ' + n(y1) + ' ' + n(x) + ' ' + n(y)); return o; },
            curveTo: function (x1, y1, x2, y2, x, y) {
                d.push('C' + n(x1) + ' ' + n(y1) + ' ' + n(x2) + ' ' + n(y2) + ' ' + n(x) + ' ' + n(y)); return o;
            },
            close: function () { d.push('Z'); return o; },
            // Path2D.append(new Arc2D.Float(x, y, w, h, start, extent, OPEN), connect). java angles are degrees,
            // counter clockwise on screen, positive extent = counter clockwise = svg sweep 0
            arc: function (x, y, w, h, start, extent, connect) {
                var rx = w / 2, ry = h / 2, cx = x + rx, cy = y + ry;
                var a0 = start * Math.PI / 180, a1 = (start + extent) * Math.PI / 180;
                var sx = cx + rx * Math.cos(a0), sy = cy - ry * Math.sin(a0);
                var ex = cx + rx * Math.cos(a1), ey = cy - ry * Math.sin(a1);
                d.push((connect ? 'L' : 'M') + n(sx) + ' ' + n(sy));
                d.push('A' + n(rx) + ' ' + n(ry) + ' 0 ' + (Math.abs(extent) > 180 ? 1 : 0) + ' ' + (extent < 0 ? 1 : 0)
                    + ' ' + n(ex) + ' ' + n(ey));
                return o;
            },
            str: function () { return d.join(''); }
        };
        return o;
    }
    function path(p) { return '<path d="' + p.str() + '"/>'; }

    // ---- the java helpers ----
    function line(x1, y1, x2, y2) { return '<path d="M' + n(x1) + ' ' + n(y1) + 'L' + n(x2) + ' ' + n(y2) + '"/>'; }
    function poly(close, xy) {
        var p = P().moveTo(xy[0], xy[1]);
        for (var i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        if (close) p.close();
        return path(p);
    }
    // Ellipse2D.Float(x, y, w, h), drawn and filled
    function ell(x, y, w, h) {
        return '<ellipse cx="' + n(x + w / 2) + '" cy="' + n(y + h / 2) + '" rx="' + n(w / 2) + '" ry="' + n(h / 2) + '"/>';
    }
    function fillEll(x, y, w, h) {
        return '<ellipse fill="currentColor" stroke="none" cx="' + n(x + w / 2) + '" cy="' + n(y + h / 2)
            + '" rx="' + n(w / 2) + '" ry="' + n(h / 2) + '"/>';
    }
    // RoundRectangle2D.Float(x, y, w, h, arcw, arch): the arc sizes are full corner widths, svg wants radii
    function rrect(x, y, w, h, aw, ah) {
        return '<rect x="' + n(x) + '" y="' + n(y) + '" width="' + n(w) + '" height="' + n(h)
            + '" rx="' + n(aw / 2) + '" ry="' + n(ah / 2) + '"/>';
    }
    function fillRrect(x, y, w, h, aw, ah) {
        return rrect(x, y, w, h, aw, ah).replace('<rect ', '<rect fill="currentColor" stroke="none" ');
    }
    // a stroke wide dot, w is the stroke in grid units
    function dot(x, y, w) {
        return '<circle fill="currentColor" stroke="none" cx="' + n(x) + '" cy="' + n(y) + '" r="' + n(w / 2) + '"/>';
    }
    // open v arrowhead, tip at (x, y), pointing along unit vector (tx, ty)
    function head(x, y, tx, ty, len, spread) {
        var bx = x - tx * len, by = y - ty * len, nx = -ty, ny = tx;
        return poly(false, [bx + nx * spread, by + ny * spread, x, y, bx - nx * spread, by - ny * spread]);
    }
    function cube() {
        return poly(true, [12, 3, 20.5, 7.5, 20.5, 16.5, 12, 21, 3.5, 16.5, 3.5, 7.5])
            + poly(false, [3.5, 7.5, 12, 12, 20.5, 7.5])
            + line(12, 12, 12, 21);
    }

    // ---- the art, case for case from Icons.art. w = stroke in grid units ----
    var ART = {
        get: function () {
            return cube() + line(7.75, 5.25, 16.25, 9.75);
        },
        goto: function () {
            // the coloured dots are added by svg() since their size depends on the pixel size
            return path(P().moveTo(5, 19).lineTo(9, 19).quadTo(12, 19, 12, 16).lineTo(12, 8).quadTo(12, 5, 15, 5).lineTo(19, 5));
        },
        follow: function () {
            var body = P().moveTo(3, 20.5).lineTo(3, 18.5).quadTo(3, 14.5, 7, 14.5).lineTo(12, 14.5)
                .quadTo(16, 14.5, 16, 18.5).lineTo(16, 20.5);
            return ell(6, 3.5, 7, 7) + path(body) + line(15.5, 9.5, 21, 9.5) + poly(false, [18.5, 7, 21, 9.5, 18.5, 12]);
        },
        waypoints: function () {
            return line(6, 3, 6, 21) + poly(true, [6, 4, 18, 4, 15, 8.5, 18, 13, 6, 13]);
        },
        help: function (w) {
            var q = P().arc(9, 6.5, 6, 6, 200, -230, false).lineTo(12, 12.6).lineTo(12, 14.2);
            return ell(3, 3, 18, 18) + path(q) + dot(12, 17.6, w);
        },
        settings: function () {
            return line(3, 8, 10, 8) + ell(11, 5, 6, 6) + line(18, 8, 21, 8)
                + line(3, 16, 6, 16) + ell(7, 13, 6, 6) + line(14, 16, 21, 16);
        },
        stats: function () {
            return line(5, 20, 5, 15) + line(12, 20, 12, 9) + line(19, 20, 19, 4);
        },
        schematics: function () {
            // a wall of bricks: two courses, the joints offset. the thing a schematic turns into
            return rrect(3, 6, 18, 12, 2, 2) + line(3, 12, 21, 12) + line(12, 6, 12, 12) + line(7.5, 12, 7.5, 18) + line(16.5, 12, 16.5, 18);
        },
        warning: function (w) {
            return poly(true, [12, 3.5, 21, 19.5, 3, 19.5]) + line(12, 9, 12, 13.5) + dot(12, 16.5, w);
        },
        search: function () {
            return ell(3.5, 3.5, 14, 14) + line(15.5, 15.5, 20.5, 20.5);
        },
        close: function () {
            return line(7, 7, 17, 17) + line(17, 7, 7, 17);
        },
        plus: function () {
            return line(12, 5, 12, 19) + line(5, 12, 19, 12);
        },
        minus: function () {
            return line(5, 12, 19, 12);
        },
        chevron_down: function () {
            return poly(false, [6, 9, 12, 15, 18, 9]);
        },
        chevron_right: function () {
            return poly(false, [9, 6, 15, 12, 9, 18]);
        },
        check: function () {
            return poly(false, [5, 12, 10, 17, 19, 7]);
        },
        copy: function () {
            var back = P().moveTo(9, 15).lineTo(5.5, 15).quadTo(3, 15, 3, 12.5).lineTo(3, 5.5).quadTo(3, 3, 5.5, 3)
                .lineTo(12.5, 3).quadTo(15, 3, 15, 5.5).lineTo(15, 9);
            return rrect(9, 9, 12, 12, 4, 4) + path(back);
        },
        reset: function () {
            // open on the upper right, arrow on the loose end, goes round the wrong way on purpose
            var cx = 12, cy = 12, r = 7.5;
            var th = 10 * Math.PI / 180;
            var px = cx + r * Math.cos(th), py = cy - r * Math.sin(th);
            return path(P().arc(cx - r, cy - r, 2 * r, 2 * r, 100, 270, false))
                + head(px, py, -Math.sin(th), -Math.cos(th), 3.5, 2.75);
        },
        stop: function () {
            return fillRrect(6, 6, 12, 12, 3, 3);
        },
        key: function () {
            return rrect(3, 5.5, 18, 13, 5, 5) + line(8, 14.5, 16, 14.5);
        },
        dot: function () {
            return fillEll(6, 6, 12, 12);
        },
        ring: function () {
            return ell(6, 6, 12, 12);
        },
        trash: function () {
            var bin = P().moveTo(6, 6.5).lineTo(6.9, 19.3).quadTo(7, 21, 8.7, 21).lineTo(15.3, 21)
                .quadTo(17, 21, 17.1, 19.3).lineTo(18, 6.5);
            return line(4, 6.5, 20, 6.5) + poly(false, [9, 6.5, 9, 4, 15, 4, 15, 6.5]) + path(bin)
                + line(10, 10.5, 10, 17) + line(14, 10.5, 14, 17);
        },
        pin: function () {
            var pin = P().moveTo(19.5, 10).curveTo(19.5, 15.5, 12, 21, 12, 21).curveTo(12, 21, 4.5, 15.5, 4.5, 10)
                .arc(4.5, 2.5, 15, 15, 180, -180, true);
            return path(pin) + ell(9, 7, 6, 6);
        },
        player: function () {
            var body = P().moveTo(4, 21).lineTo(4, 19.5).quadTo(4, 15, 8.5, 15).lineTo(15.5, 15)
                .quadTo(20, 15, 20, 19.5).lineTo(20, 21);
            return ell(7.5, 3.5, 9, 9) + path(body);
        },
        entity: function () {
            // four filled toes, the pad outlined
            var pad = P().moveTo(12, 11).curveTo(9, 11, 6.5, 14, 6.5, 17).curveTo(6.5, 19.5, 8.2, 21, 10, 21)
                .curveTo(11, 21, 11.4, 20.5, 12, 20.5).curveTo(12.6, 20.5, 13, 21, 14, 21)
                .curveTo(15.8, 21, 17.5, 19.5, 17.5, 17).curveTo(17.5, 14, 15, 11, 12, 11).close();
            return fillEll(3, 8, 4, 4) + fillEll(7.5, 3, 4, 4) + fillEll(12.5, 3, 4, 4) + fillEll(17, 8, 4, 4) + path(pad);
        },
        boat: function () {
            return poly(true, [3, 14, 6, 20, 18, 20, 21, 14]) + line(12, 3, 12, 14) + poly(true, [12, 3, 19, 10.5, 12, 10.5]);
        },
        parkour: function () {
            var arc = P().moveTo(5.5, 12).quadTo(12, 1, 18.5, 10);
            return poly(false, [3, 20, 3, 15, 8, 15]) + poly(false, [16, 20, 16, 13, 21, 13]) + path(arc)
                + head(18.5, 10, 0.585, 0.81, 3, 2.4);
        },
        bolt: function () {
            return poly(true, [13, 3, 5, 13.5, 11, 13.5, 10.5, 21, 19, 10.5, 13, 10.5]);
        },
        run: function () {
            return fillEll(13.5, 3, 4, 4)
                + poly(false, [5, 11.5, 9, 7.5, 13.5, 8.5, 17, 12.5, 21, 12.5])
                + poly(false, [13.5, 8.5, 10, 15, 14.5, 17, 16.5, 21])
                + poly(false, [10, 15, 6.5, 19.5, 3, 19.5]);
        },
        pickaxe: function () {
            // crescent head off shared tips, handle stops at the inner curve
            return path(P().moveTo(6, 4).quadTo(20, 4, 20, 18).quadTo(15, 9, 6, 4)) + line(4, 20, 14, 10);
        },
        block: function () {
            return cube();
        },
        wrench: function () {
            return poly(true, [15, 3.5, 13, 8, 16, 11, 20.5, 9, 19.5, 13.5, 15, 15.5, 7.5, 21, 3.5, 17.5, 10, 10.5, 10, 5]);
        },
        backpack: function () {
            // Arc2D(8.5, 3.5, 7, 6, 0, 180): the grab handle over the top. no straps, see Icons.java
            return rrect(5, 6, 14, 15, 6, 6) + path(P().arc(8.5, 3.5, 7, 6, 0, 180, false)) + rrect(8.5, 13, 7, 5, 2, 2);
        },
        water: function () {
            var drop = P().moveTo(12, 3).curveTo(8, 8.5, 5, 11.5, 5, 15.5).curveTo(5, 22.5, 19, 22.5, 19, 15.5)
                .curveTo(19, 11.5, 16, 8.5, 12, 3);
            return path(drop);
        },
        ladder: function () {
            return line(6, 3, 6, 21) + line(18, 3, 18, 21) + line(6, 7, 18, 7) + line(6, 12, 18, 12) + line(6, 17, 18, 17);
        },
        eye: function () {
            var eye = P().moveTo(3, 12).quadTo(12, 1, 21, 12).quadTo(12, 23, 3, 12);
            return path(eye) + ell(8.5, 8.5, 7, 7);
        },
        chevron_up: function () {
            return poly(false, [6, 15, 12, 9, 18, 15]);
        },
        chevron_left: function () {
            return poly(false, [15, 6, 9, 12, 15, 18]);
        },
        // a turn about a dot: the dot is what tells it apart from reset, which is the same arc without a pivot
        rotate_left: function () {
            var cx = 12, cy = 12, r = 7;
            var th = 45 * Math.PI / 180;
            var px = cx + r * Math.cos(th), py = cy - r * Math.sin(th);
            return path(P().arc(cx - r, cy - r, 2 * r, 2 * r, 135, 270, false))
                + head(px, py, -Math.sin(th), -Math.cos(th), 3.5, 2.75) + dot(cx, cy, 3);
        },
        rotate_right: function () {
            return '<g transform="translate(24 0) scale(-1 1)">' + ART.rotate_left() + '</g>';
        },
        // two wings either side of a dashed axis
        flip: function () {
            return line(12, 4, 12, 7) + line(12, 10.5, 12, 13.5) + line(12, 17, 12, 20)
                + poly(true, [9, 6, 9, 18, 3.5, 12]) + poly(true, [15, 6, 15, 18, 20.5, 12]);
        },
        level_up: function () {
            return poly(false, [6, 13, 12, 7, 18, 13]) + line(6, 18, 18, 18);
        },
        level_down: function () {
            return line(6, 6, 18, 6) + poly(false, [6, 11, 12, 17, 18, 11]);
        },
        // four arrows out of a cross, the move gizmo
        move: function () {
            return line(12, 4, 12, 20) + line(4, 12, 20, 12)
                + poly(false, [9.5, 6.5, 12, 4, 14.5, 6.5]) + poly(false, [9.5, 17.5, 12, 20, 14.5, 17.5])
                + poly(false, [6.5, 9.5, 4, 12, 6.5, 14.5]) + poly(false, [17.5, 9.5, 20, 12, 17.5, 14.5]);
        }
    };

    var NAMES = ['get', 'goto', 'follow', 'waypoints', 'help', 'settings', 'stats', 'search', 'close', 'plus', 'minus',
        'chevron_down', 'chevron_right', 'check', 'copy', 'reset', 'stop', 'key', 'dot', 'ring', 'trash', 'pin',
        'player', 'entity', 'boat', 'parkour', 'bolt', 'run', 'pickaxe', 'block', 'wrench', 'backpack', 'water',
        'ladder', 'eye', 'schematics', 'warning', 'chevron_up', 'chevron_left', 'rotate_left', 'rotate_right', 'flip',
        'level_up', 'level_down', 'move'];

    // css: the ring behind the goto dots only shows when the glyph sits on the accent (TexturedCanvas.pathDots checks
    // the tint is ON_ACCENT). .on-accent on the icon or any ancestor turns it on
    var CSS = 'svg.ic{display:inline-block;flex:none;vertical-align:middle}'
        + 'svg.ic .ic-ring{display:none}'
        + '.on-accent svg.ic .ic-ring,svg.ic.on-accent .ic-ring{display:inline}';

    function injectCss() {
        if (typeof document === 'undefined' || document.getElementById('icons-js-style')) return;
        var s = document.createElement('style');
        s.id = 'icons-js-style';
        s.textContent = CSS;
        (document.head || document.documentElement).appendChild(s);
    }

    function has(name) { return Object.prototype.hasOwnProperty.call(ART, name); }
    function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;'); }

    // opts: { class, style, title }
    function svg(name, size, opts) {
        opts = opts || {};
        var art = has(name) && ART[name];
        if (!has(name)) throw new Error('no icon named ' + name);
        var px = Math.max(1, Math.round(size == null ? 16 : size));
        var cls = 'ic ic-' + name.replace(/_/g, '-') + (opts['class'] ? ' ' + opts['class'] : '');
        var open = '<svg xmlns="http://www.w3.org/2000/svg" class="' + esc(cls) + '" data-icon="' + name
            + '" data-size="' + px + '" width="' + px + '" height="' + px + '"';
        if (opts.style) open += ' style="' + esc(opts.style) + '"';
        // under about 4px the grid maths flips the art inside out, java draws nothing
        if (px < 4) return open + ' aria-hidden="true"></svg>';

        var sw = strokePx(px);
        var lo = 0.5 + sw * 0.5;
        var scale = (px - 2 * lo) / GRID_SPAN;
        var w = sw / scale;
        var v0 = GRID_LO - lo / scale, vs = px / scale;
        var out = open + ' viewBox="' + n(v0) + ' ' + n(v0) + ' ' + n(vs) + ' ' + n(vs) + '"'
            + (opts.title ? ' role="img"' : ' aria-hidden="true"') + '>';
        if (opts.title) out += '<title>' + esc(opts.title) + '</title>';
        out += '<g fill="none" stroke="currentColor" stroke-width="' + n(w) + '" stroke-linecap="round" stroke-linejoin="round">'
            + art(w) + '</g>';

        if (name === 'goto') {
            // TexturedCanvas.pathDots: diameter max(3, round(sw*2.5)) px, ring = max(1, round(sw*0.75)) px of
            // on-accent white around each, all in grid units here (px / scale)
            var d = Math.max(3, Math.round(sw * 2.5));
            var ring = Math.max(1, Math.round(sw * 0.75));
            var r = d / 2 / scale, rr = (d / 2 + ring) / scale;
            var ends = [[5, 19, 'start'], [19, 5, 'end']];
            ends.forEach(function (e) {
                out += '<circle class="ic-ring" fill="currentColor" cx="' + e[0] + '" cy="' + e[1] + '" r="' + n(rr) + '"/>';
                out += '<circle class="ic-dot-' + e[2] + '" fill="var(--path-' + e[2] + ', ' + (e[2] === 'start' ? '#30D158' : '#FF453A')
                    + ')" cx="' + e[0] + '" cy="' + e[1] + '" r="' + n(r) + '"/>';
            });
        }
        return out + '</svg>';
    }

    // swaps <i data-icon="x" data-size="16"> and <span class="icon" data-icon=...> for the svg, classes kept. the svg
    // keeps data-icon but hydrate skips svgs, so calling it twice is fine
    function hydrate(root) {
        injectCss();
        root = root || document;
        var els = root.querySelectorAll('i[data-icon], span.icon[data-icon]');
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (el.namespaceURI === 'http://www.w3.org/2000/svg' || !el.parentNode) continue;
            var name = el.getAttribute('data-icon');
            if (!has(name)) { if (window.console) console.warn('icons.js: no icon named ' + name); continue; }
            var size = parseFloat(el.getAttribute('data-size')) || 16;
            var tpl = document.createElement('template');
            tpl.innerHTML = svg(name, size, { title: el.getAttribute('title') || el.getAttribute('aria-label') || '' });
            var node = tpl.content.firstChild;
            node.classList.add('icon');
            // keep the element's own classes (on-accent, tints, margins) and inline style
            for (var c = 0; c < el.classList.length; c++) node.classList.add(el.classList[c]);
            if (el.id) node.id = el.id;
            if (el.getAttribute('style')) node.setAttribute('style', el.getAttribute('style'));
            el.parentNode.replaceChild(node, el);
        }
    }

    window.Icons = { names: NAMES.slice(), svg: svg, hydrate: hydrate, strokePx: strokePx };

    injectCss();
})();
