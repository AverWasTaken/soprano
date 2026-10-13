// go to tab: live status line, red boxes, nudges, recents, empty motif (GotoTab.java, model/Coords.java, GotoRecent.java).
// ?goto=empty starts with no recents, Mock.goto.setRecents([]) does it from the console
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const root = $('#gt'); if (!root) return;
  const inputs = $$('.gt-fields input'), status = $('#gt-status'), recentBox = $('#gt-recent'), head = $('#gt-head');
  const PLAYER = { x: -212.5, y: 70, z: 418.5 }, DIM = 'overworld', LIMIT = 30000000, NAMES = ['X', 'Y', 'Z'];
  const now = Date.now();
  let recents = new URLSearchParams(location.search).get('goto') === 'empty' ? [] :
    [{ x: -1205, y: null, z: 388, dim: 'overworld', t: now }, { x: 402, y: 23, z: 1190, dim: 'overworld', t: now }];
  let submitError = null, submitField = -1, submitTexts = '';

  // ---- Coords.java ----
  const TOKEN = /^(~?)([+-]?(?:\d+(?:\.\d*)?|\.\d+)([kKmM]?)|)$/;
  const block = i => Math.floor(i === 0 ? PLAYER.x : i === 1 ? PLAYER.y + 0.1251 : PLAYER.z);
  function offset(t) {
    const m = TOKEN.exec(t); if (!m) return null;
    const body = m[2]; if (body === '') return m[1] === '' ? null : 0;
    const scale = m[3], v = parseFloat(scale ? body.slice(0, -1) : body);
    return scale ? v * (scale.toLowerCase() === 'k' ? 1000 : 1000000) : v;
  }
  // "-" and "~-" are somebody half way through typing, not a mistake yet
  const pending = text => { const t = text.trim(); return t !== '' && t !== '~' && /^~?[+-]?\.?$/.test(t); };
  function fieldError(i, text) {
    const t = text.trim(); if (!t) return null;
    const off = offset(t);
    if (off === null) return NAMES[i] + ' needs a number or ~';
    const far = NAMES[i] + ' is too far. Stay within ' + LIMIT.toLocaleString('en-US') + '.';
    if (Math.abs(off) > LIMIT) return far;
    if (t.startsWith('~') && Math.abs(block(i) + off) > LIMIT) return far;
    return null;
  }
  function resolve() {
    const v = [null, null, null];
    for (let i = 0; i < 3; i++) {
      const t = inputs[i].value.trim(), err = fieldError(i, t);
      if (err) return { err, errField: i, v: [null, null, null] };
      if (!t) continue;
      const off = offset(t); v[i] = Math.floor(t.startsWith('~') ? block(i) + off : off);
    }
    return { err: null, errField: -1, v };
  }
  const complete = r => !r.err && ((r.v[0] !== null && r.v[2] !== null) || (r.v[0] === null && r.v[2] === null && r.v[1] !== null));
  function missing(r) {
    if (r.err || complete(r)) return null;
    if (r.v[0] !== null && r.v[2] === null) return 'Add a z to go with the x';
    if (r.v[2] !== null && r.v[0] === null) return 'Add an x to go with the z';
    return 'Enter x and z, or just a y level';
  }
  function missingField(r) {
    if (r.err || complete(r)) return -1;
    return r.v[0] !== null && r.v[2] === null ? 2 : r.v[2] !== null && r.v[0] === null ? 0 : -1;
  }
  function distance(r) {
    const dy = r.v[1] === null ? 0 : r.v[1] - PLAYER.y;
    if (r.v[0] === null) return Math.abs(dy);
    const dx = r.v[0] + 0.5 - PLAYER.x, dz = r.v[2] + 0.5 - PLAYER.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
  const blocks = d => { const n = Math.round(Math.max(0, d)); return n.toLocaleString('en-US') + (n === 1 ? ' block' : ' blocks'); };

  // ---- live state ----
  let resolved = resolve();
  const texts = () => inputs.map(i => i.value).join('|');
  const liveError = () => {
    const f = resolved.errField;
    if (!resolved.err || (f >= 0 && document.activeElement === inputs[f] && pending(inputs[f].value))) return null;
    return resolved.err;
  };
  function statusLine() {
    if (submitError) return submitError;
    if (liveError()) return liveError();
    if (complete(resolved)) return 'Straight line ' + blocks(distance(resolved)) + ' · ' + DIM;
    return missing(resolved) || 'Enter x and z, or just a y level';
  }
  function refresh() {
    if (submitError && texts() !== submitTexts) { submitError = null; submitField = -1; }
    resolved = resolve();
    inputs.forEach((inp, i) => {
      const bad = fieldError(i, inp.value) !== null && !(document.activeElement === inp && pending(inp.value));
      inp.closest('.text-field').classList.toggle('invalid', bad || i === submitField);
    });
    const line = statusLine();
    status.textContent = line;
    status.classList.toggle('err', !!(submitError || liveError()));
  }

  // ---- recents ----
  const ago = ms => {
    const s = Math.max(0, (Date.now() - ms) / 1000 | 0);
    if (s < 10) return 'just now'; if (s < 60) return s + ' s ago';
    const m = s / 60 | 0; if (m < 60) return m + ' min ago';
    const h = m / 60 | 0; return h < 24 ? h + ' h ago' : (h / 24 | 0) + ' d ago';
  };
  const label = e => e.x === null || e.z === null ? 'y ' + e.y : e.y === null ? e.x + ' ' + e.z : e.x + ' ' + e.y + ' ' + e.z;
  function fill(e) {
    inputs[0].value = e.x === null ? '' : e.x; inputs[1].value = e.y === null ? '' : e.y; inputs[2].value = e.z === null ? '' : e.z;
    submitError = null; submitField = -1; refresh();
  }
  function renderRecents() {
    recentBox.textContent = '';
    head.innerHTML = '<span>Recent</span><span class="c-3"></span>';
    head.lastChild.textContent = ' · ' + recents.length;
    for (const e of recents) {
      const r = document.createElement('div'); r.className = 'row';
      const dim = e.dim ? e.dim.replace(/_/g, ' ') : '';
      r.innerHTML = '<span class="coords t-body tnum"></span><span class="tail"></span><button class="go-pill">Go</button>';
      $('.coords', r).innerHTML = label(e).replace(/-/g, '<span class="mn">-</span>');
      $('.tail', r).textContent = ' · ' + (dim ? dim + ' · ' : '') + ago(e.t);
      // a spot saved without a dimension has nothing to disagree with
      if (e.dim && e.dim !== DIM) $('.go-pill', r).classList.add('off');
      r.onclick = ev => { if (!ev.target.closest('.go-pill')) fill(e); };
      // same dimension: the pill fills and goes (GotoTab recentMouse), an off pill falls through to the row and only fills
      $('.go-pill', r).onclick = () => { fill(e); if (!$('.go-pill', r).classList.contains('off')) run(); };
      recentBox.appendChild(r);
    }
    root.classList.toggle('empty', recents.length === 0);
    if (!recents.length) { root.classList.remove('draw'); void root.offsetWidth; root.classList.add('draw'); }
  }
  function setRecents(list) { recents = list; renderRecents(); }

  // ---- actions ----
  function run() {
    const r = resolve();
    if (r.err) { submitError = r.err; submitField = -1; submitTexts = texts(); return refresh(); }
    if (!complete(r)) { submitError = missing(r); submitField = missingField(r); submitTexts = texts(); return refresh(); }
    recents = recents.filter(e => !(e.x === r.v[0] && e.y === r.v[1] && e.z === r.v[2] && e.dim === DIM));
    recents.unshift({ x: r.v[0], y: r.v[1], z: r.v[2], dim: DIM, t: Date.now() });
    recents = recents.slice(0, 8);
    renderRecents(); refresh();
  }
  $('#gt-go').onclick = run; $('#gt-goal').onclick = run;
  $('#gt-here').onclick = () => { inputs.forEach((inp, i) => { inp.value = block(i); }); submitError = null; submitField = -1; refresh(); };
  $('#gt-clear').onclick = () => { inputs.forEach(i => { i.value = ''; }); submitError = null; submitField = -1; refresh(); inputs[0].focus(); };
  inputs.forEach(inp => {
    inp.addEventListener('input', () => { submitError = null; submitField = -1; refresh(); });
    inp.addEventListener('focus', refresh); inp.addEventListener('blur', refresh);
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') run(); });
  });
  // nudges: 1, shift 10, ctrl 100 (NumberField.nudge), a garbage box nudges from zero here
  $$('.nudge').forEach(b => b.addEventListener('click', e => {
    const inp = $('input', b.closest('.num-field')), dir = +b.dataset.dir, step = e.ctrlKey ? 100 : e.shiftKey ? 10 : 1;
    const t = inp.value.trim(), rel = t.startsWith('~'), cur = parseFloat(rel ? t.slice(1) : t) || 0;
    const next = cur + step * dir;
    inp.value = rel ? (next === 0 ? '~' : '~' + next) : String(next);
    inp.dispatchEvent(new Event('input'));
  }));

  // ---- motif path: start, two gentle bends, end. corners cut at 45% and rounded with a quadratic (drawMotif/samplePath) ----
  (function motif() {
    const BW = 220, BH = 88, R = 5, PATH = [[0.04, 0.80], [0.36, 0.76], [0.64, 0.26], [0.96, 0.22]];
    const iw = BW - 2 * R, ih = BH - 2 * R, p = PATH.map(([x, y]) => [R + x * iw, R + y * ih]);
    const hyp = (a, b) => Math.hypot(b[0] - a[0], b[1] - a[1]);
    let d = 'M' + p[0][0] + ' ' + p[0][1];
    for (let i = 1; i < p.length - 1; i++) {
      const inn = hyp(p[i - 1], p[i]), out = hyp(p[i], p[i + 1]), cut = Math.min(inn, out) * 0.45;
      const a = [p[i][0] + (p[i - 1][0] - p[i][0]) * cut / inn, p[i][1] + (p[i - 1][1] - p[i][1]) * cut / inn];
      const b = [p[i][0] + (p[i + 1][0] - p[i][0]) * cut / out, p[i][1] + (p[i + 1][1] - p[i][1]) * cut / out];
      d += ' L' + a[0] + ' ' + a[1] + ' Q' + p[i][0] + ' ' + p[i][1] + ' ' + b[0] + ' ' + b[1];
    }
    d += ' L' + p[p.length - 1][0] + ' ' + p[p.length - 1][1];
    $('#gt-trail').setAttribute('d', d);
    // dot: a 25% halo at 2r under the solid disc at r
    const dot = (g, pt, c) => { g.innerHTML = '<circle cx="' + pt[0] + '" cy="' + pt[1] + '" r="' + 2 * R + '" fill="' + c + '" fill-opacity="0.25"/><circle cx="' + pt[0] + '" cy="' + pt[1] + '" r="' + R + '" fill="' + c + '"/>'; };
    dot($('.gt-motif .start'), p[0], 'var(--path-start)'); dot($('.gt-motif .end'), p[p.length - 1], 'var(--path-end)');
  })();

  // the motif replays whenever the tab is shown (onShown resets motifStart)
  new MutationObserver(() => {
    if (root.closest('section').classList.contains('active') && !recents.length) { root.classList.remove('draw'); void root.offsetWidth; root.classList.add('draw'); }
  }).observe(root.closest('section'), { attributes: true, attributeFilter: ['class'] });

  renderRecents(); refresh();
  window.Mock.goto = { setRecents, refresh };
})();
